package com.zamotkin.extrahealthgarmin.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** [BloodPressureSync]: waiting for a pulse, giving up, deletions, dedup. */
class BloodPressureSyncTest {

    private var now = Instant.parse("2026-10-22T04:00:00Z")

    private class FakeSource : BloodPressureSource {
        var granted = true
        var range = listOf<HcBloodPressure>()
        var changes = BloodPressureChanges(emptyList(), emptySet(), "t2", tokenExpired = false)
        var rangeReads = 0

        override suspend fun canReadBloodPressure() = granted
        override suspend fun readBloodPressure(since: Instant, to: Instant): List<HcBloodPressure> {
            rangeReads++
            return range
        }
        override suspend fun pullBloodPressureChanges(token: String) = changes
        override suspend fun newBloodPressureToken() = "t1"
    }

    private class InMemoryStore : BloodPressureStateStore {
        var state = BloodPressureState()
        override fun load() = state
        override fun save(state: BloodPressureState) { this.state = state }
    }

    private val source = FakeSource()
    private val store = InMemoryStore()
    private val sent = mutableListOf<List<String>>()
    /** Ids the fake Garmin side can find a pulse for. */
    private var withPulse = setOf<String>()
    private var gatewayOutcome: BloodPressureUploadOutcome? = null

    private val sync = BloodPressureSync(
        source = source,
        store = store,
        gateway = { readings ->
            sent += readings.map { it.id }
            gatewayOutcome ?: run {
                val ready = readings.map { it.id }.filter { it in withPulse }.toSet()
                BloodPressureUploadOutcome.Done(ready, ready.size)
            }
        },
        clock = { now }
    )

    private fun reading(id: String, systolic: Int = 120) =
        HcBloodPressure(id, now.toEpochMilli(), -6 * 3600, systolic, 80)

    @Test
    fun `not enabled without the permission`() = runTest {
        source.granted = false
        assertEquals(BloodPressureResult.NOT_ENABLED, sync.sync())
        assertEquals(0, source.rangeReads)
    }

    @Test
    fun `first run reads the range, uploads readings with a pulse and stores the token`() = runTest {
        source.range = listOf(reading("a"))
        withPulse = setOf("a")
        assertEquals(BloodPressureResult(enabled = true, uploaded = 1), sync.sync())
        assertEquals("t1", store.state.changesToken)
        assertEquals(listOf("a"), store.state.handledIds)
    }

    @Test
    fun `reading without a pulse waits, then uploads once the watch synced`() = runTest {
        source.range = listOf(reading("a"))
        assertEquals(1, sync.sync().waiting)
        withPulse = setOf("a")
        now += Duration.ofHours(3)
        assertEquals(1, sync.sync().uploaded)
        assertEquals(emptyList<WaitingBloodPressure>(), store.state.waiting)
    }

    @Test
    fun `reading is given up after a day without a pulse`() = runTest {
        source.range = listOf(reading("a"))
        sync.sync()
        now += Duration.ofHours(24)
        val result = sync.sync()
        assertEquals(1, result.dropped)
        assertEquals(0, result.waiting)
        assertEquals(listOf("a"), store.state.handledIds)
    }

    @Test
    fun `reading deleted in health connect before upload is never sent`() = runTest {
        source.range = listOf(reading("a"))
        sync.sync()
        source.changes = BloodPressureChanges(emptyList(), setOf("a"), "t2", tokenExpired = false)
        sent.clear()
        assertEquals(0, sync.sync().waiting)
        assertEquals(emptyList<List<String>>(), sent)
    }

    @Test
    fun `handled readings are never queued again`() = runTest {
        source.range = listOf(reading("a"))
        withPulse = setOf("a")
        sync.sync()
        source.changes = BloodPressureChanges(listOf(reading("a")), emptySet(), "t2", tokenExpired = false)
        sent.clear()
        sync.sync()
        assertEquals(emptyList<List<String>>(), sent)
    }

    @Test
    fun `out of range readings are dropped without contacting garmin`() = runTest {
        source.range = listOf(reading("a", systolic = 400))
        assertEquals(1, sync.sync().dropped)
        assertEquals(emptyList<List<String>>(), sent)
    }

    @Test
    fun `login problems keep readings waiting`() = runTest {
        source.range = listOf(reading("a"))
        gatewayOutcome = BloodPressureUploadOutcome.LoginRequired
        assertEquals(1, sync.sync().waiting)
        assertEquals(1, store.state.waiting.size)
    }

    @Test
    fun `expired changes token falls back to a range read`() = runTest {
        store.state = BloodPressureState(changesToken = "old")
        source.changes = BloodPressureChanges(emptyList(), emptySet(), "old", tokenExpired = true)
        source.range = listOf(reading("a"))
        withPulse = setOf("a")
        assertEquals(1, sync.sync().uploaded)
        assertEquals("t1", store.state.changesToken)
    }

    @Test
    fun `local time adds the zone offset`() {
        val r = HcBloodPressure("a", 1_792_639_860_000, -6 * 3600, 120, 80) // 03:31Z
        assertEquals(1_792_618_260L, r.localEpochSeconds) // 21:31 local
    }
}
