package com.zamotkin.extrahealthgarmin.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Read seam between [SyncEngine] and Health Connect. Kept free of
 * connect-client types so engine tests fake it with plain Kotlin.
 */
interface HealthDataSource {
    /** All synced record types within `[since, to]`. */
    suspend fun readRecords(since: Instant, to: Instant): List<WeighInRawRecord>

    /**
     * One round of change delivery for [token]. Implementations drain
     * `hasMore` pages themselves; [ChangesPage.tokenExpired] tells the engine
     * to fall back to a range re-read.
     */
    suspend fun pullChanges(token: String): ChangesPage

    /** A fresh changes token covering all synced record types. */
    suspend fun newToken(): String
}

/**
 * Flattened result of one changes round (connect-client
 * [ChangesResponse] in DTO form).
 *
 * @property hasMore false when the provider has no further changes right
 *   now; the engine persists [nextToken] either way.
 * @property tokenExpired the token was invalid/expired — records are partial
 *   at best and the caller must re-read a time range instead.
 */
data class ChangesPage(
    val records: List<WeighInRawRecord>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean
)

/**
 * Thin [HealthDataSource] over a [HealthConnectClient] — no caching, no
 * writes, just reads and token plumbing. Deletion changes are ignored:
 * uploaded weigh-ins are append-only on the Garmin side.
 *
 * Construct via [create] (null when Health Connect is unavailable on the
 * device); the UI-facing members ([sdkStatus], [grantedPermissions],
 * [requestPermissionsContract]) are for the later wiring phases, not for
 * [SyncEngine].
 */
class HealthConnectSource private constructor(
    private val client: HealthConnectClient
) : HealthDataSource, BloodPressureSource {

    override suspend fun readRecords(since: Instant, to: Instant): List<WeighInRawRecord> {
        val filter = TimeRangeFilter.between(since, to)
        return SYNCED_RECORD_TYPES.flatMap { type ->
            client.readRecords(ReadRecordsRequest(type, filter))
                .records
                .mapNotNull { it.toWeighInRawRecord() }
        }
    }

    override suspend fun pullChanges(token: String): ChangesPage {
        var currentToken = token
        val collected = mutableListOf<WeighInRawRecord>()
        var pages = 0
        while (pages < MAX_CHANGE_PAGES) {
            val response = client.getChanges(currentToken)
            if (response.changesTokenExpired) {
                return ChangesPage(
                    records = collected,
                    nextToken = currentToken,
                    hasMore = false,
                    tokenExpired = true
                )
            }
            collected += response.changes.mapNotNull { change ->
                (change as? UpsertionChange)?.record?.toWeighInRawRecord()
            }
            if (response.changes.isEmpty()) {
                // An empty page means the provider is drained; its token is
                // the one the next sync starts from.
                return ChangesPage(
                    records = collected,
                    nextToken = response.nextChangesToken,
                    hasMore = false,
                    tokenExpired = false
                )
            }
            currentToken = response.nextChangesToken
            pages++
        }
        // Page budget exhausted mid-stream: hand back the continuation token
        // with hasMore=true; the next periodic sync picks up where this
        // round stopped (already-collected records are deduped by id).
        return ChangesPage(collected, currentToken, hasMore = true, tokenExpired = false)
    }

    override suspend fun newToken(): String =
        client.getChangesToken(ChangesTokenRequest(SYNCED_RECORD_TYPES))

    // ------------------------------------------------------------------ //
    //  Blood pressure (optional permission, own changes token)           //
    // ------------------------------------------------------------------ //

    override suspend fun canReadBloodPressure(): Boolean =
        HealthPermissions.READ_BLOOD_PRESSURE in grantedPermissions()

    override suspend fun readBloodPressure(since: Instant, to: Instant): List<HcBloodPressure> =
        client.readRecords(
            ReadRecordsRequest(BloodPressureRecord::class, TimeRangeFilter.between(since, to))
        ).records.mapNotNull { it.toHcBloodPressure() }

    override suspend fun pullBloodPressureChanges(token: String): BloodPressureChanges {
        var currentToken = token
        val upserts = mutableListOf<HcBloodPressure>()
        val deleted = mutableSetOf<String>()
        repeat(MAX_CHANGE_PAGES) {
            val response = client.getChanges(currentToken)
            if (response.changesTokenExpired) {
                return BloodPressureChanges(upserts, deleted, currentToken, tokenExpired = true)
            }
            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange ->
                        (change.record as? BloodPressureRecord)?.toHcBloodPressure()?.let { upserts += it }
                    is DeletionChange -> deleted += change.recordId
                }
            }
            currentToken = response.nextChangesToken
            if (!response.hasMore) {
                return BloodPressureChanges(upserts, deleted, currentToken, tokenExpired = false)
            }
        }
        return BloodPressureChanges(upserts, deleted, currentToken, tokenExpired = false)
    }

    override suspend fun newBloodPressureToken(): String =
        client.getChangesToken(ChangesTokenRequest(setOf(BloodPressureRecord::class)))

    // ------------------------------------------------------------------ //
    //  UI-facing members (later wiring phases)                           //
    // ------------------------------------------------------------------ //

    /** Permissions the app currently holds in Health Connect. */
    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** `true` when every permission in [READ_PERMISSIONS] is granted. */
    suspend fun hasReadPermissions(): Boolean =
        grantedPermissions().containsAll(READ_PERMISSIONS)

    /**
     * Contract that launches the Health Connect permission screen; register
     * it with the Activity Result API and pass [READ_PERMISSIONS] as input.
     */
    fun requestPermissionsContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    companion object {
        /**
         * Record types the sync consumes, in read order. All are
         * instantaneous, so no aggregation requests are needed.
         */
        val SYNCED_RECORD_TYPES: Set<KClass<out Record>> = setOf(
            WeightRecord::class,
            BodyFatRecord::class,
            BoneMassRecord::class,
            LeanBodyMassRecord::class,
            BodyWaterMassRecord::class,
            BasalMetabolicRateRecord::class,
            HeightRecord::class
        )

        /** Health Connect read permissions matching [SYNCED_RECORD_TYPES]. */
        val READ_PERMISSIONS: Set<String> = SYNCED_RECORD_TYPES
            .mapTo(mutableSetOf()) { androidx.health.connect.client.permission.HealthPermission.getReadPermission(it) }

        /** Raw SDK status — [HealthConnectClient.SDK_AVAILABLE] when usable. */
        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

        fun isAvailable(context: Context): Boolean =
            sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        /**
         * `null` when Health Connect is not installed/up to date on the
         * device — callers show their update prompt instead of syncing.
         */
        suspend fun create(context: Context): HealthConnectSource? {
            if (!isAvailable(context)) return null
            return HealthConnectSource(HealthConnectClient.getOrCreate(context))
        }

        /** Hard cap on change pages per pull so a runaway loop cannot hang. */
        private const val MAX_CHANGE_PAGES = 1_000
    }
}

/**
 * Flattens one connect-client record; `null` for types the sync ignores.
 * Unit reads use the Kotlin `inXxx` properties (Java getters are
 * `getXxx()`, but Kotlin property synthesis is suppressed by the
 * same-named member conversion functions).
 */
private fun Record.toWeighInRawRecord(): WeighInRawRecord? = when (this) {
    is WeightRecord -> raw(RawMeasurementType.WEIGHT, time, weight.inKilograms)
    is BodyFatRecord -> raw(RawMeasurementType.BODY_FAT, time, percentage.value)
    is BoneMassRecord -> raw(RawMeasurementType.BONE_MASS, time, mass.inKilograms)
    is LeanBodyMassRecord -> raw(RawMeasurementType.LEAN_BODY_MASS, time, mass.inKilograms)
    is BodyWaterMassRecord -> raw(RawMeasurementType.BODY_WATER_MASS, time, mass.inKilograms)
    is BasalMetabolicRateRecord -> raw(
        RawMeasurementType.BASAL_METABOLIC_RATE,
        time,
        basalMetabolicRate.inKilocaloriesPerDay
    )
    is HeightRecord -> raw(RawMeasurementType.HEIGHT, time, height.inMeters)
    else -> null
}

private fun BloodPressureRecord.toHcBloodPressure(): HcBloodPressure? {
    // Garmin Connect's own readings would echo straight back into Garmin.
    if (metadata.dataOrigin.packageName == GARMIN_CONNECT_PACKAGE) return null
    return HcBloodPressure(
        id = metadata.id,
        epochMs = time.toEpochMilli(),
        zoneOffsetSeconds = (zoneOffset ?: ZoneId.systemDefault().rules.getOffset(time)).totalSeconds,
        systolic = systolic.inMillimetersOfMercury.roundToInt(),
        diastolic = diastolic.inMillimetersOfMercury.roundToInt()
    )
}

private const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"

private fun Record.raw(
    type: RawMeasurementType,
    instant: Instant,
    value: Double
): WeighInRawRecord = WeighInRawRecord(
    metadataId = metadata.id,
    type = type,
    instant = instant,
    dataOriginPackage = metadata.dataOrigin.packageName,
    value = value
)
