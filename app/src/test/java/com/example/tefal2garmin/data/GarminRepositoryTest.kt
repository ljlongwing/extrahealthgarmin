package com.example.tefal2garmin.data

import com.example.tefal2garmin.garmin.GarminAuthError
import com.example.tefal2garmin.garmin.GarminConnectionError
import com.example.tefal2garmin.garmin.GarminTokenStore
import com.example.tefal2garmin.garmin.GarminTokens
import com.example.tefal2garmin.garmin.GarminTooManyRequestsError
import com.example.tefal2garmin.garmin.GarminWeighInRecord
import com.example.tefal2garmin.garmin.JournalPersistence
import com.example.tefal2garmin.garmin.LoginResult
import com.example.tefal2garmin.garmin.MfaClientState
import com.example.tefal2garmin.garmin.TokenCrypto
import com.example.tefal2garmin.garmin.TokenPersistence
import com.example.tefal2garmin.garmin.UploadJournal
import com.example.tefal2garmin.garmin.UploadResult
import com.example.tefal2garmin.utils.GarminCsvSchema
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [GarminRepository] orchestration tests. Robolectric cannot run on this
 * repo's JDK 26 toolchain (see app/build.gradle), so the network seam is a
 * counting [FakeRemote] and the token store / journal ride their own
 * in-memory persistence fakes — the repository itself is the real thing,
 * constructed through its internal collaborators constructor.
 *
 * Test names mirror the task's scenario list 1:1. Zero-network paths are
 * proven with the fake's call counters, and every Partial asserts the
 * counts identity uploadedCount + residualCount == new records.
 */
class GarminRepositoryTest {

    // ------------------------------------------------------------------ //
    //  Fakes (plain JUnit — no mocking frameworks)                        //
    // ------------------------------------------------------------------ //

    private class FakeRemote : GarminRemote {
        var loginResult: LoginResult = LoginResult.Success(TOKENS)
        var loginException: Exception? = null
        var resumeTokens: GarminTokens = TOKENS
        var resumeException: Exception? = null
        var uploadResult: UploadResult = UploadResult.Success(0)
        var uploadException: Exception? = null

        var loginCalls = 0
            private set
        var resumeCalls = 0
            private set
        var uploadCalls = 0
            private set
        var lastResumeState: MfaClientState? = null
            private set
        var lastResumeCode: String? = null
            private set
        var lastUploadRecords: List<GarminWeighInRecord>? = null
            private set
        var lastUploadTokens: GarminTokens? = null
            private set

        override suspend fun login(email: String, password: String): LoginResult {
            loginCalls++
            loginException?.let { throw it }
            return loginResult
        }

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens {
            resumeCalls++
            lastResumeState = clientState
            lastResumeCode = code
            resumeException?.let { throw it }
            return resumeTokens
        }

        override suspend fun uploadWeighIns(
            records: List<GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult {
            uploadCalls++
            lastUploadRecords = records
            lastUploadTokens = tokens
            uploadException?.let { throw it }
            return uploadResult
        }
    }

    private class PlainTextCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(stored: String): String = stored
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        var stored: String? = null
        var writeCount = 0
        var removeCount = 0

        override fun read(): String? = stored
        override fun write(value: String) {
            stored = value
            writeCount++
        }

        override fun remove() {
            stored = null
            removeCount++
        }
    }

    private class InMemoryJournalPersistence : JournalPersistence {
        var stored: String? = null
        var savedCount = 0

        override fun load(): String? = stored
        override fun save(json: String) {
            stored = json
            savedCount++
        }
    }

    private val remote = FakeRemote()
    private val tokenPersistence = InMemoryTokenPersistence()
    private val tokenStore = GarminTokenStore(tokenPersistence, PlainTextCrypto())
    private val journalPersistence = InMemoryJournalPersistence()
    private val journal = UploadJournal(journalPersistence)
    private val repository = GarminRepository(remote, tokenStore, journal)

    // ------------------------------------------------------------------ //
    //  Fixtures                                                           //
    // ------------------------------------------------------------------ //

    private fun garminCsv(vararg rows: String): String =
        GarminCsvSchema.generateHeader() + "\n" + rows.joinToString("\n")

    private fun row(
        date: String,
        weight: String = "79.65",
        fat: String = "21.90",
        bmi: String = "24.60"
    ): String = listOf(date, weight, fat, bmi).joinToString(",")

    /** Same primitive the parser uses: local midnight of the date, epoch seconds. */
    private fun epochOf(date: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.isLenient = false
        format.timeZone = TimeZone.getDefault()
        return format.parse(date)!!.time / 1000L
    }

    // ------------------------------------------------------------------ //
    //  Login                                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `login_success_stores_tokens`() = runTest {
        remote.loginResult = LoginResult.Success(TOKENS)

        val status = repository.login("dmitry@example.com", "s3cret")

        assertTrue("expected Success, was $status", status is LoginStatus.Success)
        assertEquals(1, remote.loginCalls)
        assertEquals("tokens must be persisted on success", TOKENS, tokenStore.load())
        assertTrue(repository.hasStoredToken())
    }

    @Test
    fun `login_needs_mfa_then_resume_with_code`() = runTest {
        val mfaState = MfaClientState(mfaMethod = "EMAIL")
        remote.loginResult = LoginResult.NeedsMfa(mfaState)

        // Without a code the flow stops at NeedsMfa and stores nothing.
        val first = repository.login("dmitry@example.com", "s3cret")
        assertTrue("expected NeedsMfa, was $first", first is LoginStatus.NeedsMfa)
        assertNull("no tokens before MFA completion", tokenStore.load())
        assertEquals(0, remote.resumeCalls)

        // With a code the same login call resumes the pending MFA flow.
        val second = repository.login("dmitry@example.com", "s3cret", "123456")
        assertTrue("expected Success after MFA, was $second", second is LoginStatus.Success)
        assertEquals(1, remote.resumeCalls)
        assertEquals(mfaState, remote.lastResumeState)
        assertEquals("123456", remote.lastResumeCode)
        assertEquals(TOKENS, tokenStore.load())
    }

    @Test
    fun `login_wrong_password_returns_failed`() = runTest {
        remote.loginException = GarminAuthError("Invalid username or password")

        val status = repository.login("dmitry@example.com", "wrong")

        assertTrue("expected Failed, was $status", status is LoginStatus.Failed)
        assertEquals(
            "reason must carry the client's message",
            "Invalid username or password",
            (status as LoginStatus.Failed).reason
        )
        assertNull("failed login must not store tokens", tokenStore.load())
        assertFalse(repository.hasStoredToken())
        assertEquals(0, remote.resumeCalls)
    }

    @Test
    fun `login_transport_error_returns_failed_not_throw`() = runTest {
        remote.loginException = GarminConnectionError("Connection reset by peer")

        val status = try {
            repository.login("dmitry@example.com", "s3cret")
        } catch (e: Exception) {
            fail("login must never throw across the interface, got $e")
        }

        assertTrue("expected Failed, was $status", status is LoginStatus.Failed)
        assertEquals("Connection reset by peer", (status as LoginStatus.Failed).reason)
    }

    // ------------------------------------------------------------------ //
    //  Upload                                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `upload_not_needed_when_journal_covers_all`() = runTest {
        // No token stored on purpose: NotNeeded must be decided BEFORE the
        // token check, and must touch no network at all.
        journal.markSent(listOf(epochOf("2025-10-25"), epochOf("2025-10-26")))

        val outcome = repository.uploadNewFromCsv(
            garminCsv(row("2025-10-25"), row("2025-10-26"))
        )

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("zero network calls expected", 0, remote.uploadCalls)
        assertEquals("zero network calls expected", 0, remote.loginCalls)
        assertEquals(0, remote.resumeCalls)
    }

    @Test
    fun `upload_login_required_when_no_token`() = runTest {
        val outcome = repository.uploadNewFromCsv(garminCsv(row("2025-10-25")))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertEquals("no upload may happen without tokens", 0, remote.uploadCalls)
        assertEquals("nothing journaled", 0, journal.sentCount())
    }

    @Test
    fun `upload_success_marks_journal_and_returns_count`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadResult = UploadResult.Success(2)
        val csv = garminCsv(row("2025-10-25"), row("2025-10-26"))

        val outcome = repository.uploadNewFromCsv(csv)

        assertTrue("expected Success, was $outcome", outcome is UploadOutcome.Success)
        assertEquals(2, (outcome as UploadOutcome.Success).uploadedCount)
        assertEquals(1, remote.uploadCalls)
        assertEquals(
            "records passed to the client must be the new ones in order",
            listOf(epochOf("2025-10-25"), epochOf("2025-10-26")),
            remote.lastUploadRecords!!.map { it.epochSecondsUtc }
        )
        assertEquals("stored tokens drive the upload", TOKENS, remote.lastUploadTokens)
        assertEquals("all confirmed records journaled", 2, journal.sentCount())
        assertTrue(journalPersistence.stored!!.contains("\"${epochOf("2025-10-25")}\""))
        assertTrue(journalPersistence.stored!!.contains("\"${epochOf("2025-10-26")}\""))

        // Re-sharing the same CSV is now a no-op.
        val second = repository.uploadNewFromCsv(csv)
        assertTrue("journal must now cover everything", second is UploadOutcome.NotNeeded)
        assertEquals("no second upload", 1, remote.uploadCalls)
    }

    @Test
    fun `upload_partial_marks_only_succeeded_and_residual_has_rest`() = runTest {
        tokenStore.save(TOKENS)
        val d1 = epochOf("2025-10-25")
        val d2 = epochOf("2025-10-26")
        val d3 = epochOf("2025-10-27")
        remote.uploadResult = UploadResult.Partial(
            succeededKeys = listOf(d1),
            failureMessages = listOf("Row 2 rejected", "Row 3 rejected")
        )

        val outcome = repository.uploadNewFromCsv(
            garminCsv(row("2025-10-25"), row("2025-10-26"), row("2025-10-27"))
        )

        assertTrue("expected Partial, was $outcome", outcome is UploadOutcome.Partial)
        val partial = outcome as UploadOutcome.Partial
        assertEquals(1, partial.uploadedCount)
        assertEquals(2, partial.residualCount)
        assertEquals(
            "counts identity: uploadedCount + residualCount == newRecordCount",
            3,
            partial.uploadedCount + partial.residualCount
        )
        assertEquals(listOf("Row 2 rejected", "Row 3 rejected"), partial.messages)

        // Only the provably imported record is journaled.
        assertEquals(1, journal.sentCount())
        assertTrue("confirmed key must be journaled", journalPersistence.stored!!.contains("\"$d1\""))
        assertFalse("unconfirmed keys must NOT be journaled: ${journalPersistence.stored}", journalPersistence.stored!!.contains("\"$d2\""))
        assertFalse(journalPersistence.stored!!.contains("\"$d3\""))

        // The residual CSV round-trips through the schema to exactly the unmarked rows.
        assertEquals(
            "residual must open with the schema header",
            GarminCsvSchema.generateHeader(),
            partial.residualCsv.lines().take(2).joinToString("\n")
        )
        val residualRows = GarminCsvParser.parse(partial.residualCsv)
        assertEquals(
            "residual parses back to the unmarked rows",
            listOf(d2, d3),
            residualRows.map { it.record.epochSecondsUtc }
        )
        assertEquals(
            "residual rows keep their values",
            "2025-10-26,79.65,21.90,24.60",
            GarminCsvSchema.formatRecord(residualRows[0].fields)
        )
    }

    @Test
    fun `upload_429_returns_fallback_with_all_unmarked`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminTooManyRequestsError("Upload returned HTTP 429 - rate limited")

        val outcome = repository.uploadNewFromCsv(garminCsv(row("2025-10-25"), row("2025-10-26")))

        assertTrue("expected Fallback, was $outcome", outcome is UploadOutcome.Fallback)
        val fallback = outcome as UploadOutcome.Fallback
        assertEquals(2, fallback.residualCount)
        assertTrue("reason must mention the rate limit, was ${fallback.reason}", fallback.reason.contains("429"))
        assertEquals(
            "all new rows stay available for manual import",
            listOf(epochOf("2025-10-25"), epochOf("2025-10-26")),
            GarminCsvParser.parse(fallback.residualCsv).map { it.record.epochSecondsUtc }
        )
        assertEquals("nothing journaled on fallback", 0, journal.sentCount())
        assertEquals(
            "clients own retries — the orchestrator tries exactly once",
            1,
            remote.uploadCalls
        )
    }

    @Test
    fun `upload_transport_exhausted_returns_fallback`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminConnectionError("Upload failed: retries exhausted")

        val outcome = repository.uploadNewFromCsv(garminCsv(row("2025-10-25")))

        assertTrue("expected Fallback, was $outcome", outcome is UploadOutcome.Fallback)
        val fallback = outcome as UploadOutcome.Fallback
        assertEquals(1, fallback.residualCount)
        assertTrue(fallback.reason.contains("retries exhausted"))
        assertEquals(listOf(epochOf("2025-10-25")), GarminCsvParser.parse(fallback.residualCsv).map { it.record.epochSecondsUtc })
        assertEquals(0, journal.sentCount())
        assertEquals(1, remote.uploadCalls)
    }

    @Test
    fun `upload_auth_error_clears_token_and_returns_login_required`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminAuthError("Upload rejected: HTTP 401 even after token refresh")

        val outcome = repository.uploadNewFromCsv(garminCsv(row("2025-10-25")))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertNull("dead tokens must be cleared", tokenStore.load())
        assertEquals("token persistence must be emptied", 1, tokenPersistence.removeCount)
        assertFalse(repository.hasStoredToken())
        assertEquals("nothing journaled", 0, journal.sentCount())
    }

    @Test
    fun `upload_range_violation_returns_fallback_zero_network`() = runTest {
        tokenStore.save(TOKENS)

        val outcome = repository.uploadNewFromCsv(
            garminCsv(row("2025-10-25", weight = "80.0"), row("2025-10-26", weight = "900"))
        )

        assertTrue("expected Fallback, was $outcome", outcome is UploadOutcome.Fallback)
        val fallback = outcome as UploadOutcome.Fallback
        assertEquals("ALL new records stay unmarked", 2, fallback.residualCount)
        assertTrue(
            "reason must name the range problem, was ${fallback.reason}",
            fallback.reason.contains("range")
        )
        assertEquals(
            "both rows stay available for manual import",
            listOf(epochOf("2025-10-25"), epochOf("2025-10-26")),
            GarminCsvParser.parse(fallback.residualCsv).map { it.record.epochSecondsUtc }
        )
        assertEquals("range check must run BEFORE any network call", 0, remote.uploadCalls)
        assertEquals(0, remote.loginCalls)
        assertEquals(0, journal.sentCount())
    }

    // ------------------------------------------------------------------ //
    //  CSV parsing vs the schema                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `garmin_csv_parsing_matches_schema`() {
        val csv = listOf(
            "Body",
            "Date,Weight,Fat,BMI",
            // Schema-canonical row exactly as GarminCsvSchema.formatRecord emits it.
            "2025-10-25,79.65,21.90,24.60",
            // Russian-source normalization path: quoted Tefal date + comma
            // decimals (fixtures follow the CsvFieldMapper/Goodvibes tests).
            "\"окт. 21, 2025 08:20:53\",\"80,25\",\"21,70\",\"24,80\"",
            // Blank optional columns become absent fields, never zeros.
            "2024-01-15,155.5,,"
        ).joinToString("\n")

        val rows = GarminCsvParser.parse(csv)

        assertEquals(3, rows.size)

        val canonical = rows[0]
        assertEquals(epochOf("2025-10-25"), canonical.record.epochSecondsUtc)
        assertEquals(79.65f, canonical.record.weightKg)
        assertEquals(21.9f, canonical.record.percentFat)
        assertEquals(24.6f, canonical.record.bmi)
        assertEquals(
            "normalized row must survive formatRecord unchanged",
            "2025-10-25,79.65,21.90,24.60",
            GarminCsvSchema.formatRecord(canonical.fields)
        )
        assertTrue(GarminCsvSchema.validateRecord(canonical.fields).isValid)

        val russian = rows[1]
        assertEquals("Russian date normalizes to the same epoch", epochOf("2025-10-21"), russian.record.epochSecondsUtc)
        assertEquals(80.25f, russian.record.weightKg)
        assertEquals(21.7f, russian.record.percentFat)
        assertEquals(24.8f, russian.record.bmi)
        assertEquals(
            "comma decimals normalize to dot and round-trip through the schema",
            "2025-10-21,80.25,21.70,24.80",
            GarminCsvSchema.formatRecord(russian.fields)
        )
        assertTrue(GarminCsvSchema.validateRecord(russian.fields).isValid)

        val optional = rows[2]
        assertNull("blank Fat becomes absent", optional.record.percentFat)
        assertNull("blank BMI becomes absent", optional.record.bmi)
        assertEquals(155.5f, optional.record.weightKg)
        assertEquals("2024-01-15,155.5,,", GarminCsvSchema.formatRecord(optional.fields))
    }

    companion object {
        private val TOKENS = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )
    }
}
