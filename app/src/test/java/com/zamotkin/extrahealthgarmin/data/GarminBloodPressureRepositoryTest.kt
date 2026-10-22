package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.garmin.ApiGetResult
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminBloodPressureReading
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.TokenCrypto
import com.zamotkin.extrahealthgarmin.garmin.TokenPersistence
import com.zamotkin.extrahealthgarmin.health.BloodPressureUploadOutcome
import com.zamotkin.extrahealthgarmin.health.HcBloodPressure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [GarminBloodPressureRepository]: pulse lookup, Garmin-side dedup, outcomes. */
class GarminBloodPressureRepositoryTest {

    private val tokens = GarminTokens("di", "refresh", "client")
    private val refreshed = GarminTokens("di2", "refresh2", "client")

    // 21:31 local (-06:00) = 03:31Z next day.
    private val reading = HcBloodPressure("a", 1_792_639_860_000, -6 * 3600, 120, 80)

    private class Persistence : TokenPersistence {
        var stored: String? = null
        override fun read() = stored
        override fun write(value: String) { stored = value }
        override fun remove() { stored = null }
    }

    private class PlainCrypto : TokenCrypto {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(stored: String) = stored
    }

    private inner class FakeRemote : BloodPressureRemote {
        val paths = mutableListOf<String>()
        var existing = """{"measurementSummaries":[]}"""
        var heartRate = """{"heartRateValues":[[1792639800000,96]]}"""
        var uploaded: List<GarminBloodPressureReading>? = null
        var failUpload: Exception? = null

        override suspend fun apiGet(tokens: GarminTokens, path: String): ApiGetResult {
            paths += path
            return ApiGetResult(if (path.contains("bloodpressure")) existing else heartRate, refreshed)
        }

        override suspend fun uploadBloodPressure(
            readings: List<GarminBloodPressureReading>,
            tokens: GarminTokens
        ): GarminTokens {
            failUpload?.let { throw it }
            uploaded = readings
            return tokens
        }
    }

    private val remote = FakeRemote()
    private val tokenStore = GarminTokenStore(Persistence(), PlainCrypto()).apply { save(tokens) }
    private val repository = GarminBloodPressureRepository(remote, tokenStore)

    @Test
    fun `uploads with the watch heart rate at that minute on the local date`() = runTest {
        val outcome = repository.upload(listOf(reading))
        assertEquals(BloodPressureUploadOutcome.Done(setOf("a"), 1), outcome)
        assertEquals(listOf(GarminBloodPressureReading(1_792_618_260, 120, 80, 96)), remote.uploaded)
        assertEquals(
            listOf(
                "/bloodpressure-service/bloodpressure/range/2026-10-21/2026-10-21?includeAll=true",
                "/wellness-service/wellness/dailyHeartRate?date=2026-10-21"
            ),
            remote.paths
        )
        assertEquals(refreshed, tokenStore.load())
    }

    @Test
    fun `no heart rate yet leaves the reading waiting`() = runTest {
        remote.heartRate = """{"heartRateValues":[]}"""
        assertEquals(BloodPressureUploadOutcome.Done(emptySet(), 0), repository.upload(listOf(reading)))
        assertEquals(null, remote.uploaded)
    }

    @Test
    fun `reading garmin already holds is handled without upload`() = runTest {
        remote.existing = """{"measurementSummaries":[{"measurements":[{"systolic":120,"diastolic":80,
            "measurementTimestampLocal":"2026-10-21T21:31:00.0"}]}]}"""
        assertEquals(BloodPressureUploadOutcome.Done(setOf("a"), 0), repository.upload(listOf(reading)))
        assertEquals(null, remote.uploaded)
    }

    @Test
    fun `missing tokens or rejected auth means login required`() = runTest {
        remote.failUpload = GarminAuthError("401")
        assertEquals(BloodPressureUploadOutcome.LoginRequired, repository.upload(listOf(reading)))
        tokenStore.clear()
        assertEquals(BloodPressureUploadOutcome.LoginRequired, repository.upload(listOf(reading)))
    }

    @Test
    fun `other failures are retryable`() = runTest {
        remote.failUpload = IllegalStateException("boom")
        assertEquals(BloodPressureUploadOutcome.Retryable, repository.upload(listOf(reading)))
    }
}
