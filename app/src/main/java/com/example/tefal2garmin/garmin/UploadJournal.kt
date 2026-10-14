package com.example.tefal2garmin.garmin

import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Persistence seam for [UploadJournal]. [FileJournalPersistence] is the
 * production adapter; tests inject an in-memory fake (Robolectric cannot
 * run on this repo's JDK 26 toolchain — see app/build.gradle).
 */
interface JournalPersistence {
    /** Raw persisted JSON text, or null when nothing was ever stored. */
    fun load(): String?

    fun save(json: String)
}

/** Stores the journal as a JSON array of composite journal keys in [file]. */
class FileJournalPersistence(private val file: File) : JournalPersistence {

    override fun load(): String? = if (file.exists()) file.readText() else null

    /**
     * Atomic write: the JSON lands in a temp file first and is renamed over
     * [file], so a crash mid-write can never truncate or half-overwrite the
     * journal (a corrupted journal degrades to "start fresh", losing
     * dedup state).
     */
    override fun save(json: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json)
        if (!temp.renameTo(file)) {
            // Rename can fail across filesystems or on exotic providers —
            // fall back to copy+delete so the save still completes.
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}

/**
 * Local sent-records journal that decides which weigh-in records are new
 * (keys = composite `epochSecondsUtc:weightKg` strings, see
 * [GarminWeighInRecord.journalKey]), so re-sharing the same CSV never
 * uploads a reading twice while a same-day second measurement still
 * uploads.
 *
 * [filterNew] dedupes against the journal AND within the batch itself —
 * Tefal exports can contain repeated timestamps (re-measurements). Only
 * [markSent] may grow the journal, and only the caller decides what counts
 * as confirmed (Success records, or explicit succeededKeys of a Partial —
 * never unconfirmed guesses).
 *
 * All access is synchronized (single-process safety: the upload coroutine
 * and UI reads may overlap). A corrupted persisted journal degrades to a
 * fresh empty one with a Log.w — worst case is a duplicate-detection
 * response from Garmin, never a crash.
 */
class UploadJournal(private val persistence: JournalPersistence) {

    private val lock = Any()
    private val sentKeys = mutableSetOf<String>()

    init {
        loadFromPersistence()
    }

    /**
     * Returns the records whose composite keys ([GarminWeighInRecord.journalKey])
     * are neither journaled nor already present earlier in [records]
     * (preserving input order) — a same-day second measurement with a
     * different weight counts as new, an identical re-record does not.
     */
    fun filterNew(records: List<GarminWeighInRecord>): List<GarminWeighInRecord> =
        synchronized(lock) {
            val seenInBatch = mutableSetOf<String>()
            records.filter { record ->
                val key = record.journalKey()
                key !in sentKeys && seenInBatch.add(key)
            }
        }

    /** Marks the given composite journal keys as uploaded and persists. */
    fun markSent(keys: List<String>) = synchronized(lock) {
        sentKeys += keys
        persist()
    }

    /** Number of journaled (uploaded) records. */
    fun sentCount(): Int = synchronized(lock) { sentKeys.size }

    // ------------------------------------------------------------------ //
    //  Persistence                                                        //
    // ------------------------------------------------------------------ //

    private fun loadFromPersistence() {
        val raw = try {
            persistence.load()
        } catch (e: Exception) {
            Log.w(TAG, "Upload journal unreadable; starting fresh", e)
            null
        } ?: return
        try {
            val array = JSONArray(raw)
            val loaded = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                loaded += array.getString(i)
            }
            sentKeys += loaded
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted upload journal; starting fresh: ${e.message}")
            sentKeys.clear()
        }
    }

    private fun persist() {
        try {
            persistence.save(JSONArray(sentKeys).toString())
        } catch (e: Exception) {
            Log.w(TAG, "Upload journal not persisted: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GarminJournal"
        private const val JOURNAL_FILE_NAME = "upload_journal.json"

        /** Production factory: filesDir/upload_journal.json. */
        fun inFilesDir(filesDir: File): UploadJournal =
            UploadJournal(FileJournalPersistence(File(filesDir, JOURNAL_FILE_NAME)))
    }
}
