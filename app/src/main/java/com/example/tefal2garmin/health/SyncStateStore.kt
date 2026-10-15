package com.example.tefal2garmin.health

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Persistent state of the Health Connect -> Garmin sync (Strangler phase 2a).
 *
 * Deliberately a single JSON file — no Room, no DataStore:
 *  - [changesToken] — Health Connect changes token; `null` until the first
 *    successful sync, marking the initial 30-day read as still due.
 *  - [lastSyncInstantEpochMs] — when state last advanced; the fallback
 *    re-read anchor when a changes token expires.
 *  - [processedRecordIds] — anchor record ids already handed to the gateway
 *    with a terminal outcome; the dedup set. Anchors only land here, never
 *    companions.
 *  - [cachedHeightMeters] — newest [RawMeasurementType.HEIGHT] seen, kept
 *    across syncs so BMI survives height records dropping out of windows.
 */
data class SyncState(
    val changesToken: String? = null,
    val lastSyncInstantEpochMs: Long? = null,
    val processedRecordIds: Set<String> = emptySet(),
    val cachedHeightMeters: Double? = null
)

/** Load/save seam for [SyncEngine] — faked in unit tests. */
interface SyncStateStore {
    fun load(): SyncState
    fun save(state: SyncState)
}

/**
 * [SyncStateStore] backed by one JSON file. Writes are atomic (temp file +
 * rename) and serialized on an internal lock, so a crash or a concurrent
 * [save] can never leave a torn or half-mixed state behind. A corrupted file
 * loads as an empty state — worst case the sync re-reads the last 30 days
 * and dedups via [SyncState.processedRecordIds]... which is also lost, so
 * the gateway journal still prevents double imports.
 */
class FileSyncStateStore(private val file: File) : SyncStateStore {

    private val lock = Any()

    override fun load(): SyncState = synchronized(lock) {
        val text = try {
            if (!file.isFile) return SyncState()
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read ${file.name}; starting from empty state", e)
            return SyncState()
        }
        try {
            fromJson(JSONObject(text))
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupted sync state in ${file.name}; starting from empty state", e)
            SyncState()
        }
    }

    override fun save(state: SyncState) = synchronized(lock) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(toJson(state).toString())
            if (!temp.renameTo(file)) {
                // Cross-filesystem rename failed (or the target was locked);
                // fall back to copy-then-delete so the final file is still
                // written whole, never appended to.
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) {
                    throw IOException("Could not clean up ${temp.path}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to persist sync state to ${file.name}", e)
            throw e
        }
    }

    private fun fromJson(json: JSONObject): SyncState = SyncState(
        changesToken = json.optString(KEY_CHANGES_TOKEN).ifEmpty { null },
        lastSyncInstantEpochMs = json.optNullableLong(KEY_LAST_SYNC),
        processedRecordIds = json.optJSONArray(KEY_PROCESSED_IDS)
            ?.let { ids -> buildSet {
                for (index in 0 until ids.length()) ids.optString(index).let { if (it.isNotEmpty()) add(it) }
            } }
            ?: emptySet(),
        cachedHeightMeters = json.optNullableDouble(KEY_CACHED_HEIGHT)
    )

    private fun toJson(state: SyncState): JSONObject = JSONObject().apply {
        state.changesToken?.let { put(KEY_CHANGES_TOKEN, it) }
        state.lastSyncInstantEpochMs?.let { put(KEY_LAST_SYNC, it) }
        if (state.processedRecordIds.isNotEmpty()) {
            put(KEY_PROCESSED_IDS, JSONArray(state.processedRecordIds))
        }
        state.cachedHeightMeters?.let { put(KEY_CACHED_HEIGHT, it) }
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private companion object {
        const val TAG = "FileSyncStateStore"
        const val KEY_CHANGES_TOKEN = "changesToken"
        const val KEY_LAST_SYNC = "lastSyncInstantEpochMs"
        const val KEY_PROCESSED_IDS = "processedRecordIds"
        const val KEY_CACHED_HEIGHT = "cachedHeightMeters"
    }
}

/** In-memory [SyncStateStore] for tests and previews. */
class InMemorySyncStateStore(initial: SyncState = SyncState()) : SyncStateStore {
    private val lock = Any()

    @Volatile
    private var current: SyncState = initial

    override fun load(): SyncState = synchronized(lock) { current }

    override fun save(state: SyncState) = synchronized(lock) {
        current = state
        Unit
    }
}
