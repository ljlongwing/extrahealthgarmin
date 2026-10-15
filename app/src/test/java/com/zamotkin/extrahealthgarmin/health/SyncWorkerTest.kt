package com.zamotkin.extrahealthgarmin.health

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure SyncResult -> WorkManager-outcome mapping of [SyncWorker]
 * (task 2b) on the plain JVM. The full worker loop (WorkManager scheduling,
 * SecurityException degradation) is not unit-testable without work-testing +
 * Robolectric (broken on the current JDK, see build.gradle) — the mapping
 * below is the decision table, the catch ladder around it stays trivial.
 */
class SyncWorkerTest {

    @Test
    fun `retryable asks workmanager to back off and retry`() {
        assertEquals(
            ListenableWorker.Result.retry(),
            SyncWorker.mapSyncResult(SyncResult.Retryable)
        )
    }

    @Test
    fun `idle is a terminal success`() {
        assertEquals(
            ListenableWorker.Result.success(),
            SyncWorker.mapSyncResult(SyncResult.Idle)
        )
    }

    @Test
    fun `uploaded is a terminal success`() {
        assertEquals(
            ListenableWorker.Result.success(),
            SyncWorker.mapSyncResult(SyncResult.Uploaded(3))
        )
    }

    @Test
    fun `partial uploaded is a terminal success`() {
        assertEquals(
            ListenableWorker.Result.success(),
            SyncWorker.mapSyncResult(SyncResult.PartialUploaded(1, listOf("row 2 rejected")))
        )
    }

    @Test
    fun `login required is a success - ui shows connect on next open`() {
        assertEquals(
            ListenableWorker.Result.success(),
            SyncWorker.mapSyncResult(SyncResult.LoginRequired)
        )
    }
}
