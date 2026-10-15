package com.zamotkin.extrahealthgarmin.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UploadJournal] against an in-memory [JournalPersistence] fake —
 * journal dedup, intra-batch dedup, persistence roundtrip via the seam and
 * corrupted-JSON recovery. Plain JUnit only (Robolectric cannot run on this
 * JDK 26 toolchain; android.util.Log is a stubbed no-op).
 */
class UploadJournalTest {

    private class InMemoryPersistence : JournalPersistence {
        var stored: String? = null
        var savedCount = 0

        override fun load(): String? = stored

        override fun save(json: String) {
            stored = json
            savedCount++
        }
    }

    private fun record(epochSecondsUtc: Long): GarminWeighInRecord =
        GarminWeighInRecord(epochSecondsUtc, weightKg = 75.5f, percentFat = 22.5f, bmi = 24.5f)

    // ------------------------------------------------------------------ //
    //  Spec scenarios (names match the task's TDD list 1:1)              //
    // ------------------------------------------------------------------ //

    @Test
    fun `empty_journal_all_records_new`() {
        val persistence = InMemoryPersistence()
        val journal = UploadJournal(persistence)

        val newRecords = journal.filterNew(listOf(record(1L), record(2L), record(3L)))

        assertEquals(listOf(1L, 2L, 3L), newRecords.map { it.epochSecondsUtc })
        assertEquals(0, journal.sentCount())
    }

    @Test
    fun `marked_records_filtered_out`() {
        val persistence = InMemoryPersistence()
        val journal = UploadJournal(persistence)

        journal.markSent(listOf(record(1L).journalKey(), record(2L).journalKey()))
        val newRecords = journal.filterNew(listOf(record(1L), record(2L), record(3L)))

        assertEquals(listOf(3L), newRecords.map { it.epochSecondsUtc })
        assertEquals(2, journal.sentCount())
    }

    @Test
    fun `persistence_roundtrip_via_seam`() {
        val persistence = InMemoryPersistence()
        val first = UploadJournal(persistence)
        first.markSent(listOf(record(11L).journalKey(), record(22L).journalKey()))

        // A fresh journal over the same persistence must see the marked set.
        val second = UploadJournal(persistence)
        assertEquals(2, second.sentCount())
        val newRecords = second.filterNew(listOf(record(11L), record(33L)))
        assertEquals(listOf(33L), newRecords.map { it.epochSecondsUtc })

        // And the persisted form is a JSON array of composite key strings.
        val stored = persistence.stored!!
        assertTrue("journal must persist a JSON array: $stored", stored.startsWith("["))
        assertTrue(stored.contains("\"11:75.5\""))
        assertTrue(stored.contains("\"22:75.5\""))
    }

    @Test
    fun `duplicate_timestamps_within_one_batch_deduped`() {
        val persistence = InMemoryPersistence()
        val journal = UploadJournal(persistence)

        val batch = listOf(
            record(5L),
            record(5L), // re-measurement with the identical timestamp
            record(6L)
        )
        val newRecords = journal.filterNew(batch)

        assertEquals(
            "duplicate timestamps inside ONE batch must upload once",
            listOf(5L, 6L),
            newRecords.map { it.epochSecondsUtc }
        )
    }

    @Test
    fun `partial_marking_then_filter`() {
        val persistence = InMemoryPersistence()
        val journal = UploadJournal(persistence)

        val firstPass = journal.filterNew(listOf(record(1L), record(2L), record(3L)))
        assertEquals(3, firstPass.size)

        // Simulate a partial upload: only records 1 and 2 confirmed.
        journal.markSent(listOf(record(1L).journalKey(), record(2L).journalKey()))

        val secondPass = journal.filterNew(listOf(record(1L), record(2L), record(3L)))
        assertEquals(listOf(3L), secondPass.map { it.epochSecondsUtc })
    }

    /**
     * [FileJournalPersistence] saves atomically (temp file + rename): the
     * content round-trips, an overwrite replaces in place, and no `.tmp`
     * litter survives the save.
     */
    @Test
    fun `file_persistence_atomic_write_leaves_no_temp_file`() {
        val folder = org.junit.rules.TemporaryFolder()
        folder.create()
        try {
            val file = folder.newFile("upload_journal.json")
            val persistence = FileJournalPersistence(file)

            persistence.save("""["1:75.5"]""")
            assertEquals("""["1:75.5"]""", persistence.load())

            persistence.save("""["2:80.0"]""")
            assertEquals("overwrite must replace the content in place", """["2:80.0"]""", persistence.load())

            assertEquals(
                "the temp file must not survive the atomic save: ${file.parentFile!!.list()!!.contentToString()}",
                listOf("upload_journal.json"),
                file.parentFile!!.list()!!.toList()
            )
        } finally {
            folder.delete()
        }
    }

    @Test
    fun `corrupted_json_starts_fresh`() {
        val persistence = InMemoryPersistence()
        persistence.stored = "{{{not json at all"

        val journal = UploadJournal(persistence)

        assertEquals("corrupted journal must start empty, never crash", 0, journal.sentCount())
        val newRecords = journal.filterNew(listOf(record(9L)))
        assertEquals(listOf(9L), newRecords.map { it.epochSecondsUtc })
    }
}
