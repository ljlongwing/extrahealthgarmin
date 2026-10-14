package com.example.tefal2garmin.data

import android.content.Context
import android.util.Log
import com.example.tefal2garmin.garmin.GarminAuthClient
import com.example.tefal2garmin.garmin.GarminAuthError
import com.example.tefal2garmin.garmin.GarminConnectionError
import com.example.tefal2garmin.garmin.GarminTooManyRequestsError
import com.example.tefal2garmin.garmin.GarminTokenStore
import com.example.tefal2garmin.garmin.GarminTokens
import com.example.tefal2garmin.garmin.GarminUploadClient
import com.example.tefal2garmin.garmin.GarminValidationError
import com.example.tefal2garmin.garmin.GarminWeighInRecord
import com.example.tefal2garmin.garmin.KeystoreAesCrypto
import com.example.tefal2garmin.garmin.LoginResult
import com.example.tefal2garmin.garmin.MfaClientState
import com.example.tefal2garmin.garmin.SharedPreferencesTokenPersistence
import com.example.tefal2garmin.garmin.UploadJournal
import com.example.tefal2garmin.garmin.UploadResult
import com.example.tefal2garmin.utils.CsvFieldMapper
import com.example.tefal2garmin.utils.CsvLineParser
import com.example.tefal2garmin.utils.GarminCsvSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Status of a Garmin login attempt, for the UI layer. [Failed.reason] is a
 * human-readable message (English; the UI decides on presentation).
 */
sealed class LoginStatus {
    object Success : LoginStatus()
    object NeedsMfa : LoginStatus()
    data class Failed(val reason: String) : LoginStatus()
}

/**
 * Outcome of uploading the not-yet-journaled records of a Garmin-format CSV.
 *
 *  - [NotNeeded] — the journal already covers every record; zero network.
 *  - [Success] — every new record was imported ([uploadedCount] of them).
 *  - [Partial] — some records were confirmed imported; [residualCsv] holds
 *    the schema-formatted rows for the rest. Always satisfies
 *    `uploadedCount + residualCount == total new records`.
 *  - [Fallback] — nothing was uploaded (rate limit, transport failure, range
 *    violation); [residualCsv] holds ALL new rows for manual import.
 *  - [LoginRequired] — no usable stored tokens (or they were rejected);
 *    the UI must run [GarminUploadOrchestrator.login] first.
 */
sealed class UploadOutcome {
    object NotNeeded : UploadOutcome()
    data class Success(val uploadedCount: Int) : UploadOutcome()
    data class Partial(
        val uploadedCount: Int,
        val residualCsv: String,
        val residualCount: Int,
        val messages: List<String>
    ) : UploadOutcome()
    data class Fallback(
        val residualCsv: String,
        val residualCount: Int,
        val reason: String
    ) : UploadOutcome()
    object LoginRequired : UploadOutcome()
}

/**
 * The UI-facing contract for the Garmin API import feature. Implementations
 * never throw: every failure maps onto [LoginStatus.Failed] or a
 * [UploadOutcome] variant.
 */
interface GarminUploadOrchestrator {
    suspend fun login(email: String, password: String, mfaCode: String? = null): LoginStatus
    suspend fun uploadNewFromCsv(csvData: String): UploadOutcome

    /**
     * Cancel/exception fallback: the journal-filtered residual of [csvData]
     * (only the rows that were never confirmed uploaded), wrapped as a
     * [UploadOutcome.Fallback] with [reason]. Reads the journal but never
     * touches the network and never journals anything.
     */
    suspend fun fallbackResidual(csvData: String, reason: String): UploadOutcome.Fallback

    fun hasStoredToken(): Boolean
}

/**
 * Network seam over the task-1/2 clients so [GarminRepository] can be tested
 * with plain fakes (the real clients are final classes doing live HTTP).
 */
internal interface GarminRemote {
    suspend fun login(email: String, password: String): LoginResult

    suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens

    suspend fun uploadWeighIns(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult

    /**
     * Discards any pending MFA flow on the underlying auth client, exactly
     * like [com.example.tefal2garmin.garmin.GarminAuthClient.logout]. Needed
     * before a fresh [login] can restart an MFA flow (the "resend code" path).
     */
    fun logout()
}

/** Production [GarminRemote]: the real HTTP clients over one shared auth session. */
internal class HttpGarminRemote(
    private val authClient: GarminAuthClient,
    private val uploadClient: GarminUploadClient
) : GarminRemote {

    override suspend fun login(email: String, password: String): LoginResult =
        authClient.login(email, password)

    override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
        authClient.resumeLogin(clientState, code)

    override suspend fun uploadWeighIns(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult = uploadClient.uploadWeighIns(records, tokens)

    override fun logout() = authClient.logout()
}

/**
 * One parsed data row of the Garmin-format CSV: the upload-ready record plus
 * the normalized schema strings, kept so a residual CSV can round-trip the
 * original values through [GarminCsvSchema.formatRecord].
 */
internal data class ParsedWeighInRow(
    val record: GarminWeighInRecord,
    val fields: Map<String, String?>
)

/**
 * Quote-aware parser for the Garmin CSV format produced by this app
 * (`Body`, `Date,Weight,Fat,BMI`, then data rows). Fields are normalized
 * through [CsvFieldMapper] — dates (including Russian Tefal source forms)
 * to `YYYY-MM-DD` and comma decimals to dot — then converted to
 * [GarminWeighInRecord]s with epoch-second UTC timestamps at the local
 * midnight of the date (documented epic limitation: the Garmin CSV carries
 * no time-of-day). Total function: malformed rows are skipped with a log,
 * never thrown.
 */
internal object GarminCsvParser {

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    private val DEFAULT_COLUMNS = mapOf(
        GarminCsvSchema.HEADER_DATE to 0,
        GarminCsvSchema.HEADER_WEIGHT to 1,
        GarminCsvSchema.HEADER_FAT to 2,
        GarminCsvSchema.HEADER_BMI to 3
    )

    fun parse(csvData: String): List<ParsedWeighInRow> {
        val lineParser = CsvLineParser()
        val lines = csvData.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        var index = 0
        if (lines[0].equals(BODY_FIRST_LINE, ignoreCase = true)) index = 1

        val header = headerColumns(lines.getOrNull(index), lineParser)
        val columns: Map<String, Int>
        val expectedFieldCount: Int
        if (header != null) {
            index++
            columns = header.columns
            expectedFieldCount = header.fieldCount
        } else {
            columns = DEFAULT_COLUMNS
            expectedFieldCount = DEFAULT_COLUMNS.size
        }

        val rows = mutableListOf<ParsedWeighInRow>()
        while (index < lines.size) {
            parseRow(lines[index], index, columns, expectedFieldCount, lineParser)?.let { rows += it }
            index++
        }
        return rows
    }

    // ------------------------------------------------------------------ //
    //  Row parsing                                                        //
    // ------------------------------------------------------------------ //

    private data class Header(val columns: Map<String, Int>, val fieldCount: Int)

    /** Maps a `Date,Weight,Fat,BMI`-style header to column indices, or null if the line is data. */
    private fun headerColumns(headerLine: String?, lineParser: CsvLineParser): Header? {
        if (headerLine == null) return null
        val names = lineParser.parseCsvLine(headerLine).map { canonicalHeaderName(it.trim()) }
        if (GarminCsvSchema.HEADER_DATE !in names) return null
        return Header(names.withIndex().associate { (i, name) -> name to i }, names.size)
    }

    private fun canonicalHeaderName(name: String): String = when {
        name.equals(GarminCsvSchema.HEADER_DATE, ignoreCase = true) -> GarminCsvSchema.HEADER_DATE
        name.equals(GarminCsvSchema.HEADER_WEIGHT, ignoreCase = true) -> GarminCsvSchema.HEADER_WEIGHT
        name.equals(GarminCsvSchema.HEADER_FAT, ignoreCase = true) -> GarminCsvSchema.HEADER_FAT
        name.equals(GarminCsvSchema.HEADER_BMI, ignoreCase = true) -> GarminCsvSchema.HEADER_BMI
        else -> name
    }

    private fun parseRow(
        line: String,
        rowIndex: Int,
        columns: Map<String, Int>,
        expectedFieldCount: Int,
        lineParser: CsvLineParser
    ): ParsedWeighInRow? {
        val values = lineParser.parseCsvLine(line)
        if (values.size != expectedFieldCount) {
            Log.w(TAG, "Skipping row $rowIndex: ${values.size} fields (expected $expectedFieldCount)")
            return null
        }

        fun field(name: String): String? =
            columns[name]?.let { values.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        val date = field(GarminCsvSchema.HEADER_DATE)
            ?.let { CsvFieldMapper.normalizeDate(it) }
            ?: return null
        val epochSecondsUtc = dateToEpochSecondsUtc(date) ?: run {
            // Health PII: log the row index and reason only, never row content.
            Log.w(TAG, "Skipping row $rowIndex: unparseable date")
            return null
        }

        val weightText = field(GarminCsvSchema.HEADER_WEIGHT)
            ?.let { CsvFieldMapper.normalizeWeight(it) }
            ?: return null
        val weightKg = weightText.toFloatOrNull() ?: run {
            // Health PII: log the row index and reason only, never row content.
            Log.w(TAG, "Skipping row $rowIndex: unparseable weight")
            return null
        }

        // Optional columns: blank or unparseable values become absent (never 0).
        val fatText = field(GarminCsvSchema.HEADER_FAT)?.let { CsvFieldMapper.normalizeFat(it) }
        val percentFat = fatText?.toFloatOrNull()
        val bmiText = field(GarminCsvSchema.HEADER_BMI)?.let { CsvFieldMapper.normalizeBmi(it) }
        val bmi = bmiText?.toFloatOrNull()

        return ParsedWeighInRow(
            record = GarminWeighInRecord(epochSecondsUtc, weightKg, percentFat, bmi),
            fields = mapOf(
                GarminCsvSchema.HEADER_DATE to date,
                GarminCsvSchema.HEADER_WEIGHT to weightText,
                GarminCsvSchema.HEADER_FAT to fatText?.takeIf { percentFat != null },
                GarminCsvSchema.HEADER_BMI to bmiText?.takeIf { bmi != null }
            )
        )
    }

    /** Local midnight of a strict `YYYY-MM-DD` date, in epoch seconds UTC. */
    private fun dateToEpochSecondsUtc(date: String): Long? {
        if (!ISO_DATE.matches(date)) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.isLenient = false
            format.timeZone = TimeZone.getDefault()
            format.parse(date)?.time?.let { it / 1000L }
        } catch (e: ParseException) {
            null
        }
    }

    private const val TAG = "GarminCsvParser"
    private const val BODY_FIRST_LINE = "Body"
}

/**
 * Data-layer orchestration behind [GarminUploadOrchestrator]: parses the
 * Garmin CSV produced by the conversion pipeline, deduplicates against the
 * local [UploadJournal], drives login/MFA-resume/upload through the task-1/2
 * clients, and on failure hands back a residual CSV the user can import via
 * the Garmin Connect web flow.
 *
 * Never throws across the interface: exceptions become [LoginStatus.Failed]
 * or [UploadOutcome] variants. Retry/backoff is owned by the clients — this
 * class makes at most one upload attempt per call.
 */
class GarminRepository internal constructor(
    private val remote: GarminRemote,
    private val tokenStore: GarminTokenStore,
    private val journal: UploadJournal
) : GarminUploadOrchestrator {

    constructor(context: Context) : this(
        remote = defaultRemote(),
        tokenStore = GarminTokenStore(
            SharedPreferencesTokenPersistence(context),
            KeystoreAesCrypto()
        ),
        journal = UploadJournal.inFilesDir(context.filesDir)
    )

    /**
     * Client state of the MFA flow returned by the last [login] that answered
     * NeedsMfa, or null. Remembered here because the real client keeps the
     * flow alive in its own `pendingMfa` (a second `login` throws) — the
     * resume must reuse THIS state, and a resend must `logout()` first.
     */
    @Volatile
    private var pendingMfaClientState: MfaClientState? = null

    // ------------------------------------------------------------------ //
    //  GarminUploadOrchestrator                                           //
    // ------------------------------------------------------------------ //

    override fun hasStoredToken(): Boolean = tokenStore.load() != null

    override suspend fun login(email: String, password: String, mfaCode: String?): LoginStatus =
        withContext(Dispatchers.IO) {
            try {
                val pending = pendingMfaClientState
                when {
                    // Code entry after MFA_REQUIRED: resume the STORED flow —
                    // calling login() again is deadlocked by the auth client's
                    // pendingMfa gate ("MFA login already in progress").
                    mfaCode != null && pending != null -> {
                        val tokens = remote.resumeLogin(pending, mfaCode)
                        pendingMfaClientState = null
                        tokenStore.save(tokens)
                        LoginStatus.Success
                    }
                    // Resend code with a pending flow: the client gate must be
                    // cleared first, then a fresh login restarts the MFA flow.
                    pending != null -> {
                        remote.logout()
                        pendingMfaClientState = null
                        freshLogin(email, password, mfaCode)
                    }
                    else -> freshLogin(email, password, mfaCode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Login failed: ${reasonOf(e)}")
                LoginStatus.Failed(reasonOf(e))
            }
        }

    /**
     * Plain login attempt (no pending MFA flow). A NeedsMfa answer stores the
     * client state so a later code entry can resume this exact flow; a failed
     * resume keeps it, so the user may retry with a corrected code.
     */
    private suspend fun freshLogin(email: String, password: String, mfaCode: String?): LoginStatus {
        return when (val result = remote.login(email, password)) {
            is LoginResult.NeedsMfa -> {
                pendingMfaClientState = result.clientState
                if (mfaCode == null) {
                    LoginStatus.NeedsMfa
                } else {
                    // A code was supplied together with the credentials: resume
                    // the flow this very login just started.
                    val tokens = remote.resumeLogin(result.clientState, mfaCode)
                    pendingMfaClientState = null
                    tokenStore.save(tokens)
                    LoginStatus.Success
                }
            }
            is LoginResult.Success -> {
                pendingMfaClientState = null
                tokenStore.save(result.tokens)
                LoginStatus.Success
            }
        }
    }

    override suspend fun uploadNewFromCsv(csvData: String): UploadOutcome =
        withContext(Dispatchers.IO) {
            try {
                uploadNewRecords(csvData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable by construction (every step below handles its own
                // failures) — kept so the interface can never throw.
                Log.w(TAG, "Upload failed unexpectedly", e)
                UploadOutcome.Fallback(
                    residualCsv = csvData,
                    residualCount = 0,
                    reason = "Unexpected error: ${reasonOf(e)}"
                )
            }
        }

    override suspend fun fallbackResidual(csvData: String, reason: String): UploadOutcome.Fallback =
        withContext(Dispatchers.IO) {
            val rows = GarminCsvParser.parse(csvData)
            val newRecords = journal.filterNew(rows.map { it.record })
            val newRows = rowsMatchingNewRecords(rows, newRecords)
            Log.d(TAG, "Fallback residual: ${newRows.size} of ${rows.size} rows not covered by the journal")
            UploadOutcome.Fallback(
                residualCsv = residualCsv(newRows),
                residualCount = newRows.size,
                reason = reason
            )
        }

    // ------------------------------------------------------------------ //
    //  Upload orchestration                                               //
    // ------------------------------------------------------------------ //

    private suspend fun uploadNewRecords(csvData: String): UploadOutcome {
        val rows = GarminCsvParser.parse(csvData)
        val newRecords = journal.filterNew(rows.map { it.record })
        if (newRecords.isEmpty()) {
            Log.d(TAG, "Upload: journal covers every record - skipping network")
            return UploadOutcome.NotNeeded
        }

        val tokens = tokenStore.load()
        if (tokens == null) {
            Log.d(TAG, "Upload: no stored DI tokens - login required")
            return UploadOutcome.LoginRequired
        }

        val newRows = rowsMatchingNewRecords(rows, newRecords)

        // Range validation BEFORE any network call: the FIT uint16 field
        // scales would silently truncate out-of-range floats.
        try {
            GarminWeighInRecord.validateRanges(newRecords)
        } catch (e: GarminValidationError) {
            return fallbackAll(newRows, e.message ?: "Value outside plausible range")
        }

        return try {
            when (val result = remote.uploadWeighIns(newRecords, tokens)) {
                is UploadResult.Success -> {
                    journal.markSent(newRecords.map { it.journalKey() })
                    Log.d(TAG, "Upload: ${newRecords.size} records confirmed by Garmin")
                    UploadOutcome.Success(newRecords.size)
                }
                is UploadResult.Partial -> partialOutcome(newRows, result)
            }
        } catch (e: GarminAuthError) {
            Log.w(TAG, "Upload: auth rejected (${reasonOf(e)}) - clearing stored tokens")
            tokenStore.clear()
            UploadOutcome.LoginRequired
        } catch (e: GarminTooManyRequestsError) {
            fallbackAll(newRows, reasonOf(e))
        } catch (e: GarminConnectionError) {
            fallbackAll(newRows, reasonOf(e))
        } catch (e: GarminValidationError) {
            // Defensive: the client re-validates before encoding; nothing was sent.
            fallbackAll(newRows, reasonOf(e))
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed unexpectedly", e)
            fallbackAll(newRows, reasonOf(e))
        }
    }

    /**
     * Partial outcome: journals ONLY the provably imported keys and builds the
     * residual CSV from the remaining rows. `uploadedCount + residualCount`
     * equals the new-record count by construction.
     */
    private fun partialOutcome(
        newRows: List<ParsedWeighInRow>,
        result: UploadResult.Partial
    ): UploadOutcome {
        journal.markSent(result.succeededKeys)
        val succeeded = result.succeededKeys.toHashSet()
        val residualRows = newRows.filter { it.record.journalKey() !in succeeded }
        return UploadOutcome.Partial(
            uploadedCount = newRows.size - residualRows.size,
            residualCsv = residualCsv(residualRows),
            residualCount = residualRows.size,
            messages = result.failureMessages
        )
    }

    /** Residual CSV with every new row — nothing was uploaded, nothing journaled. */
    private fun fallbackAll(newRows: List<ParsedWeighInRow>, reason: String): UploadOutcome =
        UploadOutcome.Fallback(
            residualCsv = residualCsv(newRows),
            residualCount = newRows.size,
            reason = reason
        )

    private fun residualCsv(rows: List<ParsedWeighInRow>): String =
        StringBuilder(GarminCsvSchema.generateHeader())
            .apply {
                rows.forEach { row ->
                    append('\n')
                    append(GarminCsvSchema.formatRecord(row.fields))
                }
            }
            .toString()

    /** Rows whose records survived [UploadJournal.filterNew] (first occurrence per key, like the journal). */
    private fun rowsMatchingNewRecords(
        rows: List<ParsedWeighInRow>,
        newRecords: List<GarminWeighInRecord>
    ): List<ParsedWeighInRow> {
        val newKeys = newRecords.map { it.journalKey() }.toHashSet()
        val claimed = HashSet<String>()
        val newRows = mutableListOf<ParsedWeighInRow>()
        for (row in rows) {
            val key = row.record.journalKey()
            if (key in newKeys && claimed.add(key)) newRows += row
        }
        return newRows
    }

    companion object {
        private const val TAG = "GarminRepository"

        /** One auth client shared by login and upload (the upload 401-refresh uses its session state). */
        private fun defaultRemote(): GarminRemote {
            val authClient = GarminAuthClient()
            return HttpGarminRemote(authClient, GarminUploadClient(authClient))
        }
    }
}

private fun reasonOf(e: Exception): String =
    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
