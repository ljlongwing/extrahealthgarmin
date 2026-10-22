package com.zamotkin.extrahealthgarmin.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the [SyncAlerts.decide] decision table: post once per episode, cancel when proven over. */
class SyncAlertsTest {

    private val hour = 3_600_000L
    private val none = AlertState()

    private fun decide(
        state: AlertState,
        outcome: PassOutcome,
        now: Long = 0L,
        settings: Set<SyncAlert> = emptySet()
    ) = SyncAlerts.decide(state, outcome, settings, now)

    @Test
    fun `outcome mapping covers every sync result`() {
        assertEquals(PassOutcome.UPLOADED, SyncAlerts.outcomeOf(SyncResult.Uploaded(1)))
        assertEquals(PassOutcome.IDLE, SyncAlerts.outcomeOf(SyncResult.Idle))
        assertEquals(
            PassOutcome.RECORDS_REJECTED,
            SyncAlerts.outcomeOf(SyncResult.PartialUploaded(1, listOf("x")))
        )
        assertEquals(PassOutcome.LOGIN_REQUIRED, SyncAlerts.outcomeOf(SyncResult.LoginRequired))
        assertEquals(PassOutcome.FAILED, SyncAlerts.outcomeOf(SyncResult.Retryable))
    }

    @Test
    fun `clean pass posts nothing`() {
        val d = decide(none, PassOutcome.UPLOADED)
        assertEquals(emptySet<SyncAlert>(), d.post)
        assertEquals(emptySet<SyncAlert>(), d.cancel)
    }

    @Test
    fun `login required posts once, then stays silent`() {
        val first = decide(none, PassOutcome.LOGIN_REQUIRED)
        assertEquals(setOf(SyncAlert.LOGIN_REQUIRED), first.post)
        val again = decide(first.state, PassOutcome.LOGIN_REQUIRED)
        assertEquals(emptySet<SyncAlert>(), again.post)
    }

    @Test
    fun `idle pass does not clear login - it may never have contacted garmin`() {
        val open = decide(none, PassOutcome.LOGIN_REQUIRED).state
        val d = decide(open, PassOutcome.IDLE)
        assertEquals(emptySet<SyncAlert>(), d.cancel)
        assertEquals(setOf(SyncAlert.LOGIN_REQUIRED), d.state.shown)
    }

    @Test
    fun `upload cancels open sync alerts`() {
        val open = AlertState(setOf(SyncAlert.LOGIN_REQUIRED, SyncAlert.SYNC_FAILING), 0L)
        val d = decide(open, PassOutcome.UPLOADED)
        assertEquals(open.shown, d.cancel)
        assertEquals(none, d.state)
    }

    @Test
    fun `access lost posts once and an idle pass clears it`() {
        val lost = decide(none, PassOutcome.ACCESS_LOST)
        assertEquals(setOf(SyncAlert.HEALTH_CONNECT_ACCESS_LOST), lost.post)
        val back = decide(lost.state, PassOutcome.IDLE)
        assertEquals(setOf(SyncAlert.HEALTH_CONNECT_ACCESS_LOST), back.cancel)
    }

    @Test
    fun `failures alert only after the threshold, once`() {
        val start = decide(none, PassOutcome.FAILED, now = 0L)
        assertEquals(emptySet<SyncAlert>(), start.post)
        assertEquals(0L, start.state.failingSinceEpochMs)
        val early = decide(start.state, PassOutcome.FAILED, now = 23 * hour)
        assertEquals(emptySet<SyncAlert>(), early.post)
        val late = decide(early.state, PassOutcome.FAILED, now = 24 * hour)
        assertEquals(setOf(SyncAlert.SYNC_FAILING), late.post)
        val later = decide(late.state, PassOutcome.FAILED, now = 27 * hour)
        assertEquals(emptySet<SyncAlert>(), later.post)
    }

    @Test
    fun `failed pass keeps earlier alerts open`() {
        val open = AlertState(setOf(SyncAlert.LOGIN_REQUIRED))
        val d = decide(open, PassOutcome.FAILED, now = 5L)
        assertEquals(emptySet<SyncAlert>(), d.cancel)
        assertEquals(setOf(SyncAlert.LOGIN_REQUIRED), d.state.shown)
    }

    @Test
    fun `success resets the failure streak`() {
        val failing = decide(none, PassOutcome.FAILED, now = 0L).state
        assertNull(decide(failing, PassOutcome.UPLOADED).state.failingSinceEpochMs)
    }

    @Test
    fun `rejection posts every time and is never kept open`() {
        val first = decide(none, PassOutcome.RECORDS_REJECTED)
        assertEquals(setOf(SyncAlert.RECORDS_REJECTED), first.post)
        assertEquals(emptySet<SyncAlert>(), first.state.shown)
        assertEquals(setOf(SyncAlert.RECORDS_REJECTED), decide(first.state, PassOutcome.RECORDS_REJECTED).post)
    }

    @Test
    fun `settings alerts post once and cancel when the setting is fixed`() {
        val both = setOf(SyncAlert.APP_MAY_BE_PAUSED, SyncAlert.BATTERY_OPTIMIZED)
        val on = decide(none, PassOutcome.IDLE, settings = both)
        assertEquals(both, on.post)
        assertEquals(emptySet<SyncAlert>(), decide(on.state, PassOutcome.UPLOADED, settings = both).post)
        val fixed = decide(on.state, PassOutcome.IDLE, settings = setOf(SyncAlert.BATTERY_OPTIMIZED))
        assertEquals(setOf(SyncAlert.APP_MAY_BE_PAUSED), fixed.cancel)
    }

    @Test
    fun `settings alerts survive a failed pass`() {
        val on = decide(none, PassOutcome.IDLE, settings = setOf(SyncAlert.BATTERY_OPTIMIZED)).state
        val d = decide(on, PassOutcome.FAILED, settings = setOf(SyncAlert.BATTERY_OPTIMIZED))
        assertEquals(emptySet<SyncAlert>(), d.cancel)
        assertEquals(emptySet<SyncAlert>(), d.post)
    }
}
