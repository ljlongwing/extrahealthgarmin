package com.zamotkin.extrahealthgarmin.health

import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Orchestration-contract tests for [SyncEngine] — bug-named per the task 2a
 * TDD list (token advance, failure keeps state, expired-token re-read, 30-day
 * initial read). [FakeSource] and [CountingStore] fake the two seams; the
 * gateway is a lambda.
 */
class SyncEngineTest {

    // ------------------------------------------------------------------ //
    //  Initial sync                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `initial_run_reads_30_day_range_and_uploads`() = runTest {
        val source = FakeSource(
            readResult = mutableListOf(weight("w1", NOW.minus(Duration.ofMinutes(10))))
        )
        val store = CountingStore(SyncState())
        val uploaded = mutableListOf<List<GarminWeighInRecord>>()
        val engine = engine(
            source, store,
            gateway = { records -> uploaded += records; UploadOutcome.Success(records.size) }
        )

        val result = engine.sync()

        assertEquals(SyncResult.Uploaded(1), result)
        assertEquals("initial read must span the last 30 days",
            NOW.minus(Duration.ofDays(30)), source.readCalls.single().first)
        assertEquals(listOf(listOf(expectedRecord("w1"))), uploaded)
        val saved = store.current
        assertEquals("token-new", saved.changesToken)
        assertEquals(NOW.toEpochMilli(), saved.lastSyncInstantEpochMs)
        assertEquals(setOf("w1"), saved.processedRecordIds)
    }

    @Test
    fun `initial_run_caches_latest_height_for_bmi`() = runTest {
        val source = FakeSource(
            readResult = mutableListOf(
                height("h1", NOW.minus(Duration.ofDays(40)), meters = 1.75),
                height("h2", NOW.minus(Duration.ofDays(2)), meters = 1.80),
                weight("w1", NOW.minus(Duration.ofMinutes(10)), kg = 80.0)
            )
        )
        val store = CountingStore(SyncState())
        val uploaded = mutableListOf<List<GarminWeighInRecord>>()
        val engine = engine(source, store) { records -> uploaded += records; UploadOutcome.Success(records.size) }

        engine.sync()

        assertEquals("the newest HEIGHT record must be cached",
            1.80, store.current.cachedHeightMeters!!, 1e-9)
        assertEquals("BMI must come from the cached height",
            24.69f, uploaded.single().single().bmi!!, 0.01f)
    }

    // ------------------------------------------------------------------ //
    //  Change-driven sync                                                 //
    // ------------------------------------------------------------------ //

    @Test
    fun `success_advances_token_and_marks_processed`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w9", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(
            SyncState(
                changesToken = "token-1",
                lastSyncInstantEpochMs = NOW.minus(Duration.ofHours(2)).toEpochMilli(),
                processedRecordIds = setOf("w0"),
                cachedHeightMeters = 1.80
            )
        )
        val engine = engine(source, store) { UploadOutcome.Success(it.size) }

        val result = engine.sync()

        assertEquals(SyncResult.Uploaded(1), result)
        val saved = store.current
        assertEquals("token-after", saved.changesToken)
        assertEquals("already-processed ids must be preserved",
            setOf("w0", "w9"), saved.processedRecordIds)
        assertEquals(NOW.toEpochMilli(), saved.lastSyncInstantEpochMs)
        assertEquals(1.80, saved.cachedHeightMeters!!, 1e-9)
    }

    @Test
    fun `empty_new_batch_advances_token_only_and_returns_idle`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w0", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(
            SyncState(changesToken = "token-1", processedRecordIds = setOf("w0"))
        )
        val gatewayCalls = mutableListOf<Int>()
        val engine = engine(source, store) { gatewayCalls += it.size; UploadOutcome.Success(it.size) }

        val result = engine.sync()

        assertEquals(SyncResult.Idle, result)
        assertTrue("nothing new: gateway must not be called", gatewayCalls.isEmpty())
        assertEquals("token must still advance so the same page never re-pulls",
            "token-after", store.current.changesToken)
        assertEquals("idle advance must NOT change processed ids",
            setOf("w0"), store.current.processedRecordIds)
    }

    // ------------------------------------------------------------------ //
    //  Failure discipline                                                 //
    // ------------------------------------------------------------------ //

    @Test
    fun `retryable_keeps_state_untouched`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w9", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val engine = engine(source, store) { UploadOutcome.Retryable("429") }

        val result = engine.sync()

        assertEquals(SyncResult.Retryable, result)
        assertEquals("Retryable must not persist ANY state (old token kept for re-pull)",
            0, store.saveCount)
    }

    @Test
    fun `login_required_keeps_state_untouched`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w9", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val engine = engine(source, store) { UploadOutcome.LoginRequired }

        val result = engine.sync()

        assertEquals(SyncResult.LoginRequired, result)
        assertEquals("LoginRequired must not persist ANY state",
            0, store.saveCount)
    }

    @Test
    fun `partial_upload_saves_state_and_returns_partial`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w9", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val engine = engine(source, store) {
            UploadOutcome.Partial(0, listOf("Duplicate detection"))
        }

        val result = engine.sync()

        assertEquals(SyncResult.PartialUploaded(0, listOf("Duplicate detection")), result)
        assertEquals("token-after", store.current.changesToken)
        assertEquals(setOf("w9"), store.current.processedRecordIds)
    }

    // ------------------------------------------------------------------ //
    //  Expired token                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun `expired_token_rereads_since_lastSync_and_dedups`() = runTest {
        val lastSync = NOW.minus(Duration.ofHours(1))
        val source = FakeSource(
            readResult = mutableListOf(
                weight("w0", NOW.minus(Duration.ofMinutes(50))), // already processed — must dedup
                weight("w1", NOW.minus(Duration.ofMinutes(10)))  // new
            ),
            pullResult = ChangesPage(
                records = emptyList(),
                nextToken = "stale",
                hasMore = false,
                tokenExpired = true
            )
        )
        val store = CountingStore(
            SyncState(
                changesToken = "token-1",
                lastSyncInstantEpochMs = lastSync.toEpochMilli(),
                processedRecordIds = setOf("w0")
            )
        )
        val uploaded = mutableListOf<List<GarminWeighInRecord>>()
        val engine = engine(source, store) { records -> uploaded += records; UploadOutcome.Success(records.size) }

        val result = engine.sync()

        assertEquals(SyncResult.Uploaded(1), result)
        assertEquals("expired token must trigger a re-read since lastSyncInstant",
            lastSync, source.readCalls.single().first)
        assertEquals("the already-processed anchor must NOT upload again",
            listOf(listOf(expectedRecord("w1"))), uploaded)
        assertEquals("state must move to a FRESH token after the fallback re-read",
            "token-new", store.current.changesToken)
    }

    // ------------------------------------------------------------------ //
    //  Dedup + deferral                                                   //
    // ------------------------------------------------------------------ //

    @Test
    fun `processed_anchor_from_changes_is_not_reuploaded`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w0", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(
            SyncState(changesToken = "token-1", processedRecordIds = setOf("w0"))
        )
        val gatewayCalls = mutableListOf<Int>()
        val engine = engine(source, store) { gatewayCalls += it.size; UploadOutcome.Success(it.size) }

        val result = engine.sync()

        assertEquals(SyncResult.Idle, result)
        assertTrue(gatewayCalls.isEmpty())
    }

    @Test
    fun `young_deferred_anchor_is_not_uploaded_and_not_processed`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w-young", NOW.minusSeconds(5))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val gatewayCalls = mutableListOf<Int>()
        val engine = engine(source, store) { gatewayCalls += it.size; UploadOutcome.Success(it.size) }

        val result = engine.sync()

        assertEquals(SyncResult.Idle, result)
        assertTrue("a deferred young anchor must not reach the gateway", gatewayCalls.isEmpty())
        assertEquals("a deferred anchor must NOT be marked processed (it retries later)",
            emptySet<String>(), store.current.processedRecordIds)
        assertEquals("token-after", store.current.changesToken)
    }

    @Test
    fun `not_needed_gateway_result_maps_to_idle_and_saves_state`() = runTest {
        val source = FakeSource(
            pullResult = ChangesPage(
                records = listOf(weight("w9", NOW.minus(Duration.ofMinutes(5)))),
                nextToken = "token-after",
                hasMore = false,
                tokenExpired = false
            )
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val engine = engine(source, store) { UploadOutcome.NotNeeded }

        val result = engine.sync()

        assertEquals(SyncResult.Idle, result)
        assertEquals(setOf("w9"), store.current.processedRecordIds)
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `concurrent_pass_holding_lock_skips_as_idle`() = runTest {
        // Task 5 fix: a second pass arriving while another holds the
        // cross-process lock must skip as Idle, not race the same pending set
        // (duplicate-upload window between SyncWorker and manual sync).
        val tmp = java.nio.file.Files.createTempDirectory("sync-lock-test").toFile()
        val store = FileSyncStateStore(java.io.File(tmp, "state.json"))
        val lockFile = java.io.File(tmp, "state.lock")
        // Scripted pullChanges: if BOTH passes somehow ran (lock broken),
        // the second pass must find an EMPTY changes page -> Idle via dedup,
        // never crash on an unscripted response. The primary assertion is
        // gatewayCalls == 1, which only holds when the lock actually gated.
        val source = FakeSource(
            readResult = mutableListOf(weight("w1", NOW.minus(Duration.ofMinutes(10)))),
            pullResult = ChangesPage(emptyList(), "tok2", hasMore = false, tokenExpired = false)
        )
        var gatewayCalls = 0
        val engine = SyncEngine(
            source = source,
            stateStore = store,
            gateway = { records -> gatewayCalls++; UploadOutcome.Success(records.size) },
            clock = { NOW },
            ownPackage = OWN,
            lockFile = lockFile
        )

        val first = async { engine.sync() }
        val second = async { engine.sync() }
        val results = listOf(first.await(), second.await())

        assertEquals("one pass ran, the other skipped as Idle",
            listOf(SyncResult.Uploaded(1), SyncResult.Idle),
            results.sortedBy { if (it is SyncResult.Uploaded) 0 else 1 })
        assertEquals("gateway must be invoked exactly once across both passes", 1, gatewayCalls)
        tmp.deleteRecursively()
    }

    @Test
    fun `sequential_passes_after_lock_release_both_proceed`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("sync-lock-seq").toFile()
        val store = FileSyncStateStore(java.io.File(tmp, "state.json"))
        val lockFile = java.io.File(tmp, "state.lock")
        val source = FakeSource(
            readResult = mutableListOf(weight("w1", NOW.minus(Duration.ofMinutes(10))))
        )
        var gatewayCalls = 0
        val engine = SyncEngine(
            source = source,
            stateStore = store,
            gateway = { records -> gatewayCalls++; UploadOutcome.Success(records.size) },
            clock = { NOW },
            ownPackage = OWN,
            lockFile = lockFile
        )

        assertEquals(SyncResult.Uploaded(1), engine.sync())
        // Second pass: the lock is free, the engine runs, but the state
        // store now holds a changes token, so it takes the pullChanges path
        // -- FakeSource has no scripted pullChanges response (error()).
        // Assert only what this test owns: the lock released and let the
        // second pass ACQUIRE it (no IllegalStateException from tryLock on a
        // closed channel), which the clean return proves.
        val source2 = FakeSource(pullResult = ChangesPage(emptyList(), "tok2", hasMore = false, tokenExpired = false))
        val engine2 = SyncEngine(
            source = source2,
            stateStore = store,
            gateway = { records -> gatewayCalls++; UploadOutcome.Success(records.size) },
            clock = { NOW },
            ownPackage = OWN,
            lockFile = lockFile
        )
        assertEquals("lock released: second engine instance proceeds",
            SyncResult.Idle, engine2.sync())
        assertEquals("gateway ran once (second pass found the journal dedup set)", 1, gatewayCalls)
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ //
    //  Backfill                                                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `backfill_reads_window_uploads_unprocessed_keeps_sync_position`() = runTest {
        val source = FakeSource(
            readResult = mutableListOf(
                weight("w0", NOW.minus(Duration.ofMinutes(50))),
                weight("w1", NOW.minus(Duration.ofMinutes(10)))
            )
        )
        val lastSync = NOW.minus(Duration.ofHours(2)).toEpochMilli()
        val store = CountingStore(
            SyncState(
                changesToken = "token-1",
                lastSyncInstantEpochMs = lastSync,
                processedRecordIds = setOf("w0")
            )
        )
        val uploaded = mutableListOf<List<GarminWeighInRecord>>()
        val engine = engine(source, store) { uploaded += it; UploadOutcome.Success(it.size) }

        val result = engine.backfill(Duration.ofDays(7))

        assertEquals(SyncResult.Uploaded(1), result)
        assertEquals("reads exactly the requested window",
            NOW.minus(Duration.ofDays(7)) to NOW, source.readCalls.single())
        assertEquals("only the unprocessed weigh-in is uploaded",
            listOf(listOf(expectedRecord("w1"))), uploaded)
        val saved = store.current
        assertEquals("changes token untouched", "token-1", saved.changesToken)
        assertEquals("last sync untouched", lastSync, saved.lastSyncInstantEpochMs)
        assertEquals(setOf("w0", "w1"), saved.processedRecordIds)
    }

    @Test
    fun `backfill_before_first_sync_leaves_token_unset`() = runTest {
        val source = FakeSource(
            readResult = mutableListOf(weight("w1", NOW.minus(Duration.ofMinutes(10))))
        )
        val store = CountingStore(SyncState())
        val engine = engine(source, store) { UploadOutcome.Success(it.size) }

        engine.backfill(Duration.ofDays(1))

        assertEquals("the regular first sync must still run its initial read",
            null, store.current.changesToken)
        assertEquals(null, store.current.lastSyncInstantEpochMs)
        assertEquals(setOf("w1"), store.current.processedRecordIds)
    }

    @Test
    fun `backfill_retryable_keeps_state_untouched`() = runTest {
        val source = FakeSource(
            readResult = mutableListOf(weight("w1", NOW.minus(Duration.ofMinutes(10))))
        )
        val store = CountingStore(SyncState(changesToken = "token-1"))
        val engine = engine(source, store) { UploadOutcome.Retryable("429") }

        assertEquals(SyncResult.Retryable, engine.backfill(Duration.ofDays(30)))
        assertEquals("nothing persisted on Retryable", 0, store.saveCount)
    }

    private fun engine(
        source: FakeSource,
        store: CountingStore,
        gateway: suspend (List<GarminWeighInRecord>) -> UploadOutcome
    ): SyncEngine = SyncEngine(
        source = source,
        stateStore = store,
        gateway = gateway,
        clock = { NOW },
        ownPackage = OWN
    )

    private fun expectedRecord(id: String): GarminWeighInRecord =
        GarminWeighInRecord(
            epochSecondsUtc = WEIGHT_INSTANTS.getValue(id),
            weightKg = 80f,
            percentFat = null,
            bmi = null
        )

    private fun weight(id: String, instant: Instant, kg: Double = 80.0) =
        WeighInRawRecord(id, RawMeasurementType.WEIGHT, instant, VENDOR, kg)

    private fun height(id: String, instant: Instant, meters: Double) =
        WeighInRawRecord(id, RawMeasurementType.HEIGHT, instant, VENDOR, meters)

    private class FakeSource(
        private val readResult: MutableList<WeighInRawRecord> = mutableListOf(),
        private val pullResult: ChangesPage? = null
    ) : HealthDataSource {
        val readCalls = mutableListOf<Pair<Instant, Instant>>()

        override suspend fun readRecords(since: Instant, to: Instant): List<WeighInRawRecord> {
            readCalls += since to to
            return readResult
        }

        override suspend fun pullChanges(token: String): ChangesPage =
            pullResult ?: error("no scripted pullChanges response")

        override suspend fun newToken(): String = "token-new"
    }

    private class CountingStore(initial: SyncState) : SyncStateStore {
        var current: SyncState = initial
            private set
        var saveCount = 0
            private set

        override fun load(): SyncState = current

        override fun save(state: SyncState) {
            saveCount++
            current = state
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-10-14T10:00:00Z")
        private const val VENDOR = "com.vendor.scale"
        private const val OWN = "com.zamotkin.extrahealthgarmin"

        /** Anchors used in these tests, for expected-record lookups. */
        private val WEIGHT_INSTANTS: Map<String, Long> = mapOf(
            "w1" to NOW.minus(Duration.ofMinutes(10)).epochSecond,
            "w9" to NOW.minus(Duration.ofMinutes(5)).epochSecond,
            "w0" to NOW.minus(Duration.ofMinutes(50)).epochSecond
        )
    }
}
