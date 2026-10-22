package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.garmin.ApiGetResult
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminConnectionError
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.TokenCrypto
import com.zamotkin.extrahealthgarmin.garmin.TokenPersistence
import com.zamotkin.extrahealthgarmin.health.HydrationLog
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** [GarminHydrationRepository]: request body, stop at first failure, token hand-over. */
class GarminHydrationRepositoryTest {

    private val tokens = GarminTokens("di", "refresh", "client")
    private val refreshed = GarminTokens("di2", "refresh2", "client")

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

    private val bodies = mutableListOf<JSONObject>()
    private val usedTokens = mutableListOf<GarminTokens>()
    private var failOnCall: Int? = null
    private var failure: Exception = GarminConnectionError("HTTP 500")

    private val persistence = Persistence()
    private val tokenStore = GarminTokenStore(persistence, PlainCrypto()).apply { save(tokens) }
    private val repo = GarminHydrationRepository(
        remote = { t, path, json ->
            assertEquals("/usersummary-service/usersummary/hydration/log", path)
            if (bodies.size == failOnCall) throw failure
            usedTokens += t
            bodies += JSONObject(json)
            ApiGetResult("{}", refreshed)
        },
        tokenStore = tokenStore
    )

    private val add = HydrationLog("2026-10-21", "2026-10-21T21:30:00.0", 250.0)
    private val takeBack = add.copy(valueMl = -250.0)

    @Test
    fun `sends each log in order with Garmin field names`() = runTest {
        assertEquals(2, repo.send(listOf(takeBack, add)))
        assertEquals(listOf(-250.0, 250.0), bodies.map { it.getDouble("valueInML") })
        assertEquals("2026-10-21", bodies[0].getString("calendarDate"))
        assertEquals("2026-10-21T21:30:00.0", bodies[0].getString("timestampLocal"))
    }

    @Test
    fun `refreshed tokens are used for the next call and saved`() = runTest {
        repo.send(listOf(add, add))
        assertEquals(listOf(tokens, refreshed), usedTokens)
        assertEquals(refreshed, tokenStore.load())
    }

    @Test
    fun `stops at the first failure and reports how many went through`() = runTest {
        failOnCall = 1
        assertEquals(1, repo.send(listOf(add, add, add)))
    }

    @Test
    fun `auth failure sends nothing and never throws`() = runTest {
        failOnCall = 0
        failure = GarminAuthError("401")
        assertEquals(0, repo.send(listOf(add)))
    }

    @Test
    fun `signed out - nothing sent`() = runTest {
        persistence.stored = null
        assertEquals(0, repo.send(listOf(add)))
        assertEquals(0, bodies.size)
    }
}
