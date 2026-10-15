package com.example.tefal2garmin.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence tests for [FileSyncStateStore] and [InMemorySyncStateStore] —
 * bug-named per the task 2a TDD list (roundtrip, corrupted JSON, atomic
 * save). File-based: plain JVM with a temp folder; android.util.Log calls
 * inside run against stubbed framework methods (see app/build.gradle
 * testOptions) and org.json comes from the JVM test dependency.
 */
class SyncStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): FileSyncStateStore =
        FileSyncStateStore(File(tmp.root, "sync_state.json"))

    @Test
    fun `roundtrip_persists_all_fields`() {
        val store = newStore()
        val state = SyncState(
            changesToken = "token-abc",
            lastSyncInstantEpochMs = 1_760_000_000_000L,
            processedRecordIds = setOf("id-1", "id-2", "id-3"),
            cachedHeightMeters = 1.80
        )
        store.save(state)

        assertEquals(state, newStore().load())
    }

    @Test
    fun `empty_state_defaults_persist_to_empty_state`() {
        val store = newStore()
        store.save(SyncState())

        assertEquals(SyncState(), newStore().load())
    }

    @Test
    fun `corrupted_json_yields_empty_state_without_crash`() {
        val file = File(tmp.root, "sync_state.json")
        file.writeText("{{{not json at all")

        assertEquals(SyncState(), newStore().load())
    }

    @Test
    fun `missing_file_yields_empty_state`() {
        assertEquals(SyncState(), newStore().load())
    }

    @Test
    fun `save_replaces_previous_state`() {
        val store = newStore()
        store.save(
            SyncState(
                changesToken = "token-1",
                processedRecordIds = setOf("old-1", "old-2"),
                cachedHeightMeters = 1.75
            )
        )

        store.save(SyncState(changesToken = "token-2", processedRecordIds = setOf("new-1")))

        val loaded = newStore().load()
        assertEquals("token-2", loaded.changesToken)
        assertEquals(setOf("new-1"), loaded.processedRecordIds)
        assertEquals("a replaced state must not keep stale fields",
            null, loaded.cachedHeightMeters)
    }

    @Test
    fun `atomic_save_leaves_no_temp_file_and_valid_json`() {
        val store = newStore()
        store.save(SyncState(changesToken = "token-x"))

        val leftovers = tmp.root.list()!!.filter { it.endsWith(".tmp") }
        assertTrue("temp file must be renamed away: $leftovers", leftovers.isEmpty())
        assertTrue(File(tmp.root, "sync_state.json").readText().contains("token-x"))
    }

    @Test
    fun `concurrent_sequential_saves_keep_last_writer_intact`() {
        val store = newStore()
        val workers = (1..8).map { index ->
            Thread {
                repeat(25) { round ->
                    store.save(
                        SyncState(
                            changesToken = "w$index-$round",
                            processedRecordIds = setOf("w$index-$round-id")
                        )
                    )
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val loaded = newStore().load()
        // Whatever the interleaving, the persisted file must be a complete,
        // parseable state written by exactly one save — never a torn mix.
        assertTrue("corrupted state after concurrent saves: $loaded",
            loaded.changesToken != null &&
                loaded.processedIdsOfSingleWriter())
        assertFalse(File(tmp.root, "sync_state.json").readText().isEmpty())
    }

    @Test
    fun `in_memory_store_roundtrip_and_replacement`() {
        val store = InMemorySyncStateStore()
        assertEquals(SyncState(), store.load())

        val state = SyncState(changesToken = "t", processedRecordIds = setOf("a"))
        store.save(state)
        assertEquals(state, store.load())

        store.save(SyncState())
        assertEquals(SyncState(), store.load())
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun SyncState.processedIdsOfSingleWriter(): Boolean {
        if (processedRecordIds.size != 1) return false
        val id = processedRecordIds.single()
        val token = changesToken!!
        // Same writer index and round: "w<i>-<r>" vs "w<i>-<r>-id".
        return id.removeSuffix("-id") == token
    }
}
