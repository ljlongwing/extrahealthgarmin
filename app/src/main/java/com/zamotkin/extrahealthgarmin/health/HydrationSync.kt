package com.zamotkin.extrahealthgarmin.health

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A Health Connect hydration record: [volumeMl] drunk, logged at its start time. */
data class HcHydration(
    val id: String,
    val epochMs: Long,
    /** The record's UTC offset, so Garmin files it under the local day. */
    val zoneOffsetSeconds: Int,
    val volumeMl: Double
)

/** One Garmin hydration log call: [valueMl] (negative to take an amount back) on a local day. */
data class HydrationLog(val calendarDate: String, val timestampLocal: String, val valueMl: Double) {
    companion object {
        private val LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.0'")

        fun of(entry: HcHydration, sign: Int = 1): HydrationLog {
            val local = Instant.ofEpochMilli(entry.epochMs)
                .atOffset(ZoneOffset.ofTotalSeconds(entry.zoneOffsetSeconds))
                .toLocalDateTime()
            return HydrationLog(local.toLocalDate().toString(), local.format(LOCAL_TIME), sign * entry.volumeMl)
        }
    }
}

/** One drained changes round: upserts, deleted record ids, the next token. */
data class HydrationChanges(
    val upserts: List<HcHydration>,
    val deletedIds: Set<String>,
    val nextToken: String,
    val tokenExpired: Boolean
)

/** Health Connect seam for [HydrationSync]; faked in unit tests. */
interface HydrationSource {
    suspend fun canReadHydration(): Boolean
    suspend fun readHydration(since: Instant, to: Instant): List<HcHydration>
    suspend fun pullHydrationChanges(token: String): HydrationChanges
    suspend fun newHydrationToken(): String
}

/**
 * Garmin seam for [HydrationSync]: sends in order, stops at the first failure
 * and returns how many went through. Never throws.
 */
fun interface HydrationGateway {
    suspend fun send(logs: List<HydrationLog>): Int
}

/** Outcome of one hydration pass. */
data class HydrationResult(
    val enabled: Boolean,
    /** Log calls that went through (additions and take-backs). */
    val sent: Int = 0,
    /** Log calls still queued for the next pass. */
    val queued: Int = 0
) {
    companion object {
        val NOT_ENABLED = HydrationResult(enabled = false)
    }
}

/** Persisted between passes. */
data class HydrationState(
    val changesToken: String? = null,
    /** When the last pass ran: the re-read start if the changes token expires. */
    val lastPassEpochMs: Long? = null,
    /** New or edited entries not yet added in Garmin. */
    val pending: List<HcHydration> = emptyList(),
    /** Entries already added in Garmin but since deleted/edited: their amount is still to take back. */
    val takeBacks: List<HcHydration> = emptyList(),
    /** Entries added in Garmin, as sent (so a deletion or edit can be taken back). */
    val sent: List<HcHydration> = emptyList()
)

/**
 * Health Connect hydration -> Garmin Connect's daily hydration log. Runs
 * after the weigh-in pass, independently of it (own changes token and state
 * file), and only when the user granted READ_HYDRATION.
 *
 * Garmin keeps only a daily total (each log call adds to it) with no list of
 * entries to dedup against, so this side remembers what it sent: a deleted
 * entry is taken back with a negative log, an edited one is taken back and
 * re-added. For the same reason there is no backfill: the first pass only
 * takes a changes token, since Garmin cannot tell whether an older entry is
 * already in its total (logged by hand, say).
 */
class HydrationSync(
    private val source: HydrationSource,
    private val store: HydrationStateStore,
    private val gateway: HydrationGateway,
    private val clock: () -> Instant
) {

    // shortcut: no cross-process lock like SyncEngine's; a worker and a manual pass
    // racing could both send an entry (counted twice). Add a lock if that shows up.
    suspend fun sync(): HydrationResult {
        if (!source.canReadHydration()) return HydrationResult.NOT_ENABLED
        val now = clock()
        val state = store.load()
        if (state.changesToken == null) {
            store.save(HydrationState(changesToken = source.newHydrationToken(), lastPassEpochMs = now.toEpochMilli()))
            return HydrationResult(enabled = true)
        }

        val changes = fetch(state, now)
        val pending = state.pending.associateBy { it.id }.toMutableMap()
        val sent = state.sent.associateBy { it.id }.toMutableMap()
        val takeBacks = state.takeBacks.toMutableList()
        for (entry in changes.upserts) {
            val old = sent[entry.id]
            if (old == entry) continue
            if (old != null) {
                takeBacks += old
                sent -= entry.id
            }
            // An entry edited down to nothing only takes the old amount back.
            if (entry.volumeMl > 0) pending[entry.id] = entry else pending -= entry.id
        }
        for (id in changes.deletedIds) {
            pending -= id
            sent.remove(id)?.let { takeBacks += it }
        }

        // Take-backs first, so a day's total never shows an edit twice.
        val queue = takeBacks.map { it to -1 } + pending.values.sortedBy { it.epochMs }.map { it to 1 }
        val sentCount = if (queue.isEmpty()) 0 else gateway.send(queue.map { (entry, sign) -> HydrationLog.of(entry, sign) })
        val done = queue.take(sentCount)
        done.filter { it.second > 0 }.forEach { (entry, _) -> pending -= entry.id; sent[entry.id] = entry }
        val remainingTakeBacks = takeBacks.drop(done.count { it.second < 0 })
        if (sentCount < queue.size) {
            Log.w(TAG, "Hydration: ${queue.size - sentCount} log call(s) left for the next pass")
        }

        val cutoff = now.toEpochMilli() - SENT_RETENTION_MS
        store.save(
            HydrationState(
                changesToken = changes.nextToken,
                lastPassEpochMs = now.toEpochMilli(),
                pending = pending.values.toList(),
                takeBacks = remainingTakeBacks,
                sent = sent.values.filter { it.epochMs >= cutoff }
            )
        )
        return HydrationResult(
            enabled = true,
            sent = sentCount,
            queued = queue.size - sentCount
        )
    }

    private suspend fun fetch(state: HydrationState, now: Instant): HydrationChanges {
        val changes = source.pullHydrationChanges(state.changesToken!!)
        if (!changes.tokenExpired) return changes
        // Token expired (no pass for ~30 days): token first, then re-read since the
        // last pass; already-sent entries come back unchanged and are skipped.
        // Deletions in that gap are lost.
        val fresh = source.newHydrationToken()
        val since = Instant.ofEpochMilli(state.lastPassEpochMs ?: now.toEpochMilli())
        return HydrationChanges(source.readHydration(since, now), emptySet(), fresh, tokenExpired = false)
    }

    companion object {
        private const val TAG = "HydrationSync"

        /** Sent entries are remembered this long; deleting an older one leaves Garmin as is. */
        const val SENT_RETENTION_MS = 35L * 24 * 3600 * 1000
    }
}

interface HydrationStateStore {
    fun load(): HydrationState
    fun save(state: HydrationState)
}

/** [HydrationStateStore] in one JSON file, written atomically like [FileSyncStateStore]. */
class FileHydrationStateStore(private val file: File) : HydrationStateStore {

    override fun load(): HydrationState = try {
        if (!file.isFile) HydrationState() else fromJson(JSONObject(file.readText()))
    } catch (e: IOException) {
        Log.w(TAG, "Failed to read ${file.name}; starting empty", e)
        HydrationState()
    } catch (e: JSONException) {
        Log.w(TAG, "Corrupted ${file.name}; starting empty", e)
        HydrationState()
    }

    override fun save(state: HydrationState) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(toJson(state).toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun fromJson(json: JSONObject) = HydrationState(
        changesToken = json.optString(KEY_TOKEN).ifEmpty { null },
        lastPassEpochMs = if (json.has(KEY_LAST_PASS)) json.getLong(KEY_LAST_PASS) else null,
        pending = entries(json.optJSONArray(KEY_PENDING)),
        takeBacks = entries(json.optJSONArray(KEY_TAKE_BACKS)),
        sent = entries(json.optJSONArray(KEY_SENT))
    )

    private fun entries(array: JSONArray?): List<HcHydration> =
        if (array == null) emptyList() else (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            HcHydration(e.getString("id"), e.getLong("epochMs"), e.getInt("zoneOffsetSeconds"), e.getDouble("volumeMl"))
        }

    private fun toJson(state: HydrationState): JSONObject = JSONObject().apply {
        state.changesToken?.let { put(KEY_TOKEN, it) }
        state.lastPassEpochMs?.let { put(KEY_LAST_PASS, it) }
        put(KEY_PENDING, array(state.pending))
        put(KEY_TAKE_BACKS, array(state.takeBacks))
        put(KEY_SENT, array(state.sent))
    }

    private fun array(entries: List<HcHydration>) = JSONArray(entries.map { e ->
        JSONObject()
            .put("id", e.id)
            .put("epochMs", e.epochMs)
            .put("zoneOffsetSeconds", e.zoneOffsetSeconds)
            .put("volumeMl", e.volumeMl)
    })

    private companion object {
        const val TAG = "HydrationState"
        const val KEY_TOKEN = "changesToken"
        const val KEY_LAST_PASS = "lastPassEpochMs"
        const val KEY_PENDING = "pending"
        const val KEY_TAKE_BACKS = "takeBacks"
        const val KEY_SENT = "sent"
    }
}
