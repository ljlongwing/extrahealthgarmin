package com.zamotkin.extrahealthgarmin.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/** [HydrationSync]: no backfill, adds, take-backs for deletions/edits, partial sends. */
class HydrationSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = Instant.parse("2026-10-22T04:00:00Z")

    private class FakeSource : HydrationSource {
        var granted = true
        var range = listOf<HcHydration>()
        var changes = HydrationChanges(emptyList(), emptySet(), "t2", tokenExpired = false)
        var rangeReads = 0

        override suspend fun canReadHydration() = granted
        override suspend fun readHydration(since: Instant, to: Instant): List<HcHydration> {
            rangeReads++
            return range
        }
        override suspend fun pullHydrationChanges(token: String) = changes
        override suspend fun newHydrationToken() = "t1"
    }

    private class InMemoryStore : HydrationStateStore {
        var state = HydrationState()
        override fun load() = state
        override fun save(state: HydrationState) { this.state = state }
    }

    private val source = FakeSource()
    private val store = InMemoryStore()
    private val calls = mutableListOf<HydrationLog>()
    /** How many log calls the fake Garmin side accepts per batch. */
    private var accept = Int.MAX_VALUE

    private val sync = HydrationSync(
        source = source,
        store = store,
        gateway = { logs ->
            val n = minOf(accept, logs.size)
            calls += logs.take(n)
            n
        },
        clock = { now }
    )

    // 21:30 local (-06:00) on Oct 21 = 03:30Z Oct 22.
    private fun entry(id: String, ml: Double = 250.0) =
        HcHydration(id, Instant.parse("2026-10-22T03:30:00Z").toEpochMilli(), -6 * 3600, ml)

    private fun changes(vararg upserts: HcHydration, deleted: Set<String> = emptySet()) {
        source.changes = HydrationChanges(upserts.toList(), deleted, "t2", tokenExpired = false)
    }

    private suspend fun started() {
        sync.sync()
        calls.clear()
    }

    @Test
    fun `not enabled without the permission`() = runTest {
        source.granted = false
        assertEquals(HydrationResult.NOT_ENABLED, sync.sync())
    }

    @Test
    fun `first run only takes a token - no backfill`() = runTest {
        source.range = listOf(entry("a"))
        assertEquals(HydrationResult(enabled = true), sync.sync())
        assertEquals("t1", store.state.changesToken)
        assertEquals(0, source.rangeReads)
        assertEquals(emptyList<HydrationLog>(), calls)
    }

    @Test
    fun `new entry is logged on its local day and remembered`() = runTest {
        started()
        changes(entry("a"))
        assertEquals(HydrationResult(enabled = true, sent = 1), sync.sync())
        assertEquals(listOf(HydrationLog("2026-10-21", "2026-10-21T21:30:00.0", 250.0)), calls)
        assertEquals(listOf("a"), store.state.sent.map { it.id })
        assertEquals("t2", store.state.changesToken)
    }

    @Test
    fun `unchanged re-delivery is not sent twice`() = runTest {
        started()
        changes(entry("a"))
        sync.sync()
        sync.sync()
        assertEquals(1, calls.size)
    }

    @Test
    fun `deleting a sent entry takes its amount back`() = runTest {
        started()
        changes(entry("a"))
        sync.sync()
        changes(deleted = setOf("a"))
        sync.sync()
        assertEquals(listOf(250.0, -250.0), calls.map { it.valueMl })
        assertEquals(emptyList<HcHydration>(), store.state.sent)
    }

    @Test
    fun `editing a sent entry takes the old amount back before adding the new one`() = runTest {
        started()
        changes(entry("a"))
        sync.sync()
        changes(entry("a", 500.0))
        sync.sync()
        assertEquals(listOf(250.0, -250.0, 500.0), calls.map { it.valueMl })
        assertEquals(listOf(500.0), store.state.sent.map { it.volumeMl })
    }

    @Test
    fun `zero-volume entry is never sent`() = runTest {
        started()
        changes(entry("a", 0.0))
        sync.sync()
        assertEquals(emptyList<HydrationLog>(), calls)
    }

    @Test
    fun `deleted before it was sent - never sent`() = runTest {
        started()
        accept = 0
        changes(entry("a"))
        assertEquals(1, sync.sync().queued)
        accept = Int.MAX_VALUE
        changes(deleted = setOf("a"))
        sync.sync()
        assertEquals(emptyList<HydrationLog>(), calls)
        assertEquals(emptyList<HcHydration>(), store.state.pending)
    }

    @Test
    fun `failed sends stay queued while the token advances`() = runTest {
        started()
        accept = 1
        changes(entry("a"), entry("b"))
        assertEquals(HydrationResult(enabled = true, sent = 1, queued = 1), sync.sync())
        assertEquals("t2", store.state.changesToken)
        accept = Int.MAX_VALUE
        changes()
        assertEquals(1, sync.sync().sent)
        assertEquals(setOf("a", "b"), store.state.sent.map { it.id }.toSet())
    }

    @Test
    fun `expired token re-reads since the last pass and skips what was sent`() = runTest {
        started()
        changes(entry("a"))
        sync.sync()
        source.changes = HydrationChanges(emptyList(), emptySet(), "t2", tokenExpired = true)
        source.range = listOf(entry("a"), entry("b"))
        sync.sync()
        assertEquals(1, source.rangeReads)
        assertEquals(2, calls.size)
        assertEquals("t1", store.state.changesToken)
    }

    @Test
    fun `old sent entries are forgotten after the retention`() = runTest {
        started()
        changes(entry("a"))
        sync.sync()
        now += Duration.ofDays(36)
        changes()
        sync.sync()
        assertEquals(emptyList<HcHydration>(), store.state.sent)
    }

    @Test
    fun `file store round-trips the state`() {
        val file = tmp.newFile("hydration-sync.json")
        val state = HydrationState("tok", 5L, listOf(entry("p")), listOf(entry("t", 100.0)), listOf(entry("s")))
        FileHydrationStateStore(file).save(state)
        assertEquals(state, FileHydrationStateStore(file).load())
    }
}
