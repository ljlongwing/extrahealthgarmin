package com.zamotkin.extrahealthgarmin.health

import android.util.Log
import com.zamotkin.extrahealthgarmin.garmin.GarminBloodPressureReading
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** A Health Connect blood pressure reading (mmHg) and when it was taken. */
data class HcBloodPressure(
    val id: String,
    val epochMs: Long,
    /** The reading's UTC offset, so Garmin gets the local wall-clock time. */
    val zoneOffsetSeconds: Int,
    val systolic: Int,
    val diastolic: Int
) {
    val localEpochSeconds: Long get() = epochMs / 1000 + zoneOffsetSeconds

    fun inGarminRange(): Boolean =
        systolic in GarminBloodPressureReading.SYSTOLIC_RANGE &&
            diastolic in GarminBloodPressureReading.DIASTOLIC_RANGE
}

/** One drained changes round: upserts, deleted record ids, the next token. */
data class BloodPressureChanges(
    val upserts: List<HcBloodPressure>,
    val deletedIds: Set<String>,
    val nextToken: String,
    val tokenExpired: Boolean
)

/** Health Connect seam for [BloodPressureSync]; faked in unit tests. */
interface BloodPressureSource {
    suspend fun canReadBloodPressure(): Boolean
    suspend fun readBloodPressure(since: Instant, to: Instant): List<HcBloodPressure>
    suspend fun pullBloodPressureChanges(token: String): BloodPressureChanges
    suspend fun newBloodPressureToken(): String
}

/** What the Garmin side made of one batch of waiting readings. */
sealed class BloodPressureUploadOutcome {
    /**
     * [handledIds] are done for good: uploaded now ([uploadedCount] of them)
     * or already in Garmin. Every other reading is still waiting for its pulse.
     */
    data class Done(val handledIds: Set<String>, val uploadedCount: Int) : BloodPressureUploadOutcome()
    object LoginRequired : BloodPressureUploadOutcome()
    object Retryable : BloodPressureUploadOutcome()
}

/** Garmin seam for [BloodPressureSync]; never throws. */
fun interface BloodPressureGateway {
    suspend fun upload(readings: List<HcBloodPressure>): BloodPressureUploadOutcome
}

/** Outcome of one blood pressure pass. */
data class BloodPressureResult(
    val enabled: Boolean,
    val uploaded: Int = 0,
    /** Readings still waiting (usually for the watch's heart rate to reach Garmin). */
    val waiting: Int = 0,
    /** Readings given up on: no pulse within [BloodPressureSync.GIVE_UP_AFTER], or out of range. */
    val dropped: Int = 0
) {
    companion object {
        val NOT_ENABLED = BloodPressureResult(enabled = false)
    }
}

/** Persisted between passes. Waiting readings live here so the changes token can advance. */
data class BloodPressureState(
    val changesToken: String? = null,
    val waiting: List<WaitingBloodPressure> = emptyList(),
    /** Recently handled record ids, so a re-read never re-queues them. */
    val handledIds: List<String> = emptyList()
)

data class WaitingBloodPressure(val reading: HcBloodPressure, val firstSeenEpochMs: Long)

/**
 * Health Connect blood pressure -> Garmin Connect. Runs after the weigh-in
 * pass, independently of it (its own changes token and state file), and only
 * when the user granted READ_BLOOD_PRESSURE.
 *
 * Health Connect blood pressure carries no pulse (Samsung Health does not
 * pass one on) but Garmin requires it, so the gateway uses the Garmin
 * watch's own heart rate at that minute. That sample only exists once the
 * watch has synced, so readings wait across passes and are given up after
 * [GIVE_UP_AFTER] (reported as dropped for a notification).
 */
class BloodPressureSync(
    private val source: BloodPressureSource,
    private val store: BloodPressureStateStore,
    private val gateway: BloodPressureGateway,
    private val clock: () -> Instant
) {

    // shortcut: no cross-process lock like SyncEngine's; a worker and a manual
    // pass racing could both upload, Garmin-side dedup in the gateway catches it.
    suspend fun sync(): BloodPressureResult {
        if (!source.canReadBloodPressure()) return BloodPressureResult.NOT_ENABLED
        val now = clock()
        val state = store.load()

        val (incoming, deleted, nextToken) = fetch(state.changesToken, now)
        val known = state.handledIds.toSet() + state.waiting.map { it.reading.id }
        val fresh = incoming.distinctBy { it.id }.filter { it.id !in known }
        val (valid, invalid) = fresh.partition { it.inGarminRange() }
        invalid.forEach { Log.w(TAG, "Dropping out-of-range reading ${it.systolic}/${it.diastolic}") }

        // Deleted in Health Connect before upload (e.g. a mistyped entry): never send it.
        var waiting = (state.waiting + valid.map { WaitingBloodPressure(it, now.toEpochMilli()) })
            .filter { it.reading.id !in deleted }
        var handled = state.handledIds + invalid.map { it.id }
        var uploaded = 0
        if (waiting.isNotEmpty()) {
            when (val outcome = gateway.upload(waiting.map { it.reading })) {
                is BloodPressureUploadOutcome.Done -> {
                    waiting = waiting.filter { it.reading.id !in outcome.handledIds }
                    handled = handled + outcome.handledIds
                    uploaded = outcome.uploadedCount
                }
                // Readings stay waiting; the weigh-in pass reports login/failures.
                BloodPressureUploadOutcome.LoginRequired, BloodPressureUploadOutcome.Retryable -> Unit
            }
        }
        val (expired, kept) = waiting.partition {
            now.toEpochMilli() - it.firstSeenEpochMs >= GIVE_UP_AFTER.toMillis()
        }
        expired.forEach { Log.w(TAG, "Giving up on reading at ${Instant.ofEpochMilli(it.reading.epochMs)}: no pulse") }

        store.save(
            BloodPressureState(
                changesToken = nextToken,
                waiting = kept,
                handledIds = (handled + expired.map { it.reading.id }).takeLast(MAX_HANDLED_IDS)
            )
        )
        return BloodPressureResult(
            enabled = true,
            uploaded = uploaded,
            waiting = kept.size,
            dropped = expired.size + invalid.size
        )
    }

    /** New readings, deleted ids and the token to store next. */
    private suspend fun fetch(
        token: String?,
        now: Instant
    ): Triple<List<HcBloodPressure>, Set<String>, String> {
        if (token != null) {
            val changes = source.pullBloodPressureChanges(token)
            if (!changes.tokenExpired) {
                return Triple(changes.upserts, changes.deletedIds, changes.nextToken)
            }
        }
        // First run or expired token: token first, so nothing between the
        // read and the token is missed (overlap is deduped by id).
        val fresh = source.newBloodPressureToken()
        return Triple(source.readBloodPressure(now - INITIAL_WINDOW, now), emptySet(), fresh)
    }

    companion object {
        private const val TAG = "BloodPressureSync"
        val GIVE_UP_AFTER: Duration = Duration.ofHours(24)
        val INITIAL_WINDOW: Duration = Duration.ofDays(30)
        const val MAX_HANDLED_IDS = 500
    }
}

interface BloodPressureStateStore {
    fun load(): BloodPressureState
    fun save(state: BloodPressureState)
}

/** [BloodPressureStateStore] in one JSON file, written atomically like [FileSyncStateStore]. */
class FileBloodPressureStateStore(private val file: File) : BloodPressureStateStore {

    override fun load(): BloodPressureState = try {
        if (!file.isFile) BloodPressureState() else fromJson(JSONObject(file.readText()))
    } catch (e: IOException) {
        Log.w(TAG, "Failed to read ${file.name}; starting empty", e)
        BloodPressureState()
    } catch (e: JSONException) {
        Log.w(TAG, "Corrupted ${file.name}; starting empty", e)
        BloodPressureState()
    }

    override fun save(state: BloodPressureState) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(toJson(state).toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun fromJson(json: JSONObject): BloodPressureState {
        val waiting = json.optJSONArray(KEY_WAITING) ?: JSONArray()
        val handled = json.optJSONArray(KEY_HANDLED) ?: JSONArray()
        return BloodPressureState(
            changesToken = json.optString(KEY_TOKEN).ifEmpty { null },
            waiting = (0 until waiting.length()).map { i ->
                val w = waiting.getJSONObject(i)
                WaitingBloodPressure(
                    HcBloodPressure(
                        id = w.getString("id"),
                        epochMs = w.getLong("epochMs"),
                        zoneOffsetSeconds = w.getInt("zoneOffsetSeconds"),
                        systolic = w.getInt("systolic"),
                        diastolic = w.getInt("diastolic")
                    ),
                    firstSeenEpochMs = w.getLong("firstSeenEpochMs")
                )
            },
            handledIds = (0 until handled.length()).map { handled.getString(it) }
        )
    }

    private fun toJson(state: BloodPressureState): JSONObject = JSONObject().apply {
        state.changesToken?.let { put(KEY_TOKEN, it) }
        put(KEY_WAITING, JSONArray(state.waiting.map { w ->
            JSONObject()
                .put("id", w.reading.id)
                .put("epochMs", w.reading.epochMs)
                .put("zoneOffsetSeconds", w.reading.zoneOffsetSeconds)
                .put("systolic", w.reading.systolic)
                .put("diastolic", w.reading.diastolic)
                .put("firstSeenEpochMs", w.firstSeenEpochMs)
        }))
        put(KEY_HANDLED, JSONArray(state.handledIds))
    }

    private companion object {
        const val TAG = "BloodPressureState"
        const val KEY_TOKEN = "changesToken"
        const val KEY_WAITING = "waiting"
        const val KEY_HANDLED = "handledIds"
    }
}
