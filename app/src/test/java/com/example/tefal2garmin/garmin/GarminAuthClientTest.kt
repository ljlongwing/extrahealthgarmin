package com.example.tefal2garmin.garmin

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Wire-contract tests for [GarminAuthClient] against MockWebServer, one
 * scenario per failure mode of the python-garminconnect reference. Request
 * counts are asserted wherever the epic forbids retry storms (wrong creds,
 * 429, 401-on-API).
 */
class GarminAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GarminAuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ //
    //  Spec scenarios (names match the task's TDD list 1:1)              //
    // ------------------------------------------------------------------ //

    @Test
    fun `login_success_returns_tokens`() = runBlocking {
        val jwt = farFutureJwt()
        enqueueSuccessfulChain(tokenJson(jwt))

        val result = client.login("user@example.com", "password123")

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        val tokens = (result as LoginResult.Success).tokens
        assertEquals(jwt, tokens.diToken)
        assertEquals("di-rt-1", tokens.diRefreshToken)
        assertEquals(CLIENT_ID_2025Q2, tokens.diClientId)
        assertEquals(3, server.requestCount)

        val login = server.takeRequest()
        assertEquals("/mobile/api/login", login.path!!.substringBefore('?'))
        assertTrue(
            "login query must carry the iOS client id",
            login.path!!.contains("clientId=GCM_IOS_DARK")
        )
        assertEquals("Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            login.getHeader("User-Agent"))

        val exchange = server.takeRequest()
        assertEquals("/di-oauth2-service/oauth/token", exchange.path)
        assertEquals(basicAuth(CLIENT_ID_2025Q2), exchange.getHeader("Authorization"))
        val exchangeBody = exchange.body.readUtf8()
        assertTrue("exchange must carry the service ticket: $exchangeBody",
            exchangeBody.contains("service_ticket=ST-12345"))
        assertTrue("exchange must carry the service-ticket grant type: $exchangeBody",
            exchangeBody.contains("grant_type="))
        assertTrue("exchange must carry the iOS service url: $exchangeBody",
            exchangeBody.contains("service_url="))

        val validation = server.takeRequest()
        assertEquals("/userprofile-service/socialProfile", validation.path)
        assertEquals("Bearer $jwt", validation.getHeader("Authorization"))
        assertEquals("GCM-Android-5.23", validation.getHeader("User-Agent"))
    }

    @Test
    fun `login_mfa_required_returns_client_state_and_resume_completes`() = runBlocking {
        server.enqueue(jsonResponse(mfaRequiredBody()))
        val result = client.login("user@example.com", "password123")
        assertTrue("expected NeedsMfa, got $result", result is LoginResult.NeedsMfa)
        assertEquals("email", (result as LoginResult.NeedsMfa).clientState.mfaMethod)

        // Resume: verifyCode -> exchange -> socialProfile validation.
        server.enqueue(jsonResponse(loginSuccessBody(ticket = "ST-MFA-9")))
        server.enqueue(jsonResponse(tokenJson(farFutureJwt(), refreshToken = "di-rt-mfa")))
        server.enqueue(jsonResponse("""{"displayName":"Test User"}"""))

        val tokens = client.resumeLogin(result.clientState, "123456")

        assertEquals("di-rt-mfa", tokens.diRefreshToken)
        assertEquals(4, server.requestCount)

        val login = server.takeRequest()
        assertEquals("/mobile/api/login", login.path!!.substringBefore('?'))
        val verify = server.takeRequest()
        assertTrue("expected MFA verify path, got ${verify.path}",
            verify.path!!.startsWith("/mobile/api/mfa/verifyCode?"))
        assertTrue("verify query must carry the iOS client id: ${verify.path}",
            verify.path!!.contains("clientId=GCM_IOS_DARK"))
        val verifyBody = verify.body.readUtf8()
        assertTrue("verify body must carry the code: $verifyBody",
            verifyBody.contains("\"mfaVerificationCode\":\"123456\""))
        assertTrue("verify body must carry the method: $verifyBody",
            verifyBody.contains("\"mfaMethod\":\"email\""))

        val exchange = server.takeRequest()
        val exchangeBody = exchange.body.readUtf8()
        assertTrue("exchange must consume the MFA ticket: $exchangeBody",
            exchangeBody.contains("service_ticket=ST-MFA-9"))
    }

    @Test
    fun `wrong_credentials_throw_auth_error_without_fallback_retry`() = runBlocking {
        server.enqueue(jsonResponse("""{"responseStatus":{"type":"INVALID_USERNAME_PASSWORD"}}"""))

        try {
            client.login("user@example.com", "wrong-password")
            fail("expected GarminAuthError")
        } catch (e: GarminAuthError) {
            assertTrue(e.message!!.contains("Invalid Username or Password"))
        }
        assertEquals("wrong credentials must produce exactly ONE request", 1, server.requestCount)
    }

    @Test
    fun `rate_limited_429_surfaces_immediately_no_retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))

        try {
            client.login("user@example.com", "password123")
            fail("expected GarminTooManyRequestsError")
        } catch (e: GarminTooManyRequestsError) {
            assertTrue(e.message!!.contains("429"))
        }
        assertEquals("429 must produce exactly ONE request", 1, server.requestCount)
    }

    @Test
    fun `html_challenge_response_raises_connection_error_not_json_crash`() = runBlocking {
        // 200 with an HTML Cloudflare challenge body — the sneakiest case.
        server.enqueue(htmlResponse(200))
        try {
            client.login("user@example.com", "password123")
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue("message must indicate HTML: ${e.message}",
                e.message!!.contains("HTML"))
            assertTrue("message must indicate a challenge: ${e.message}",
                e.message!!.contains("challenge"))
        }
        // Same for a 403 challenge.
        server.enqueue(htmlResponse(403))
        try {
            client.login("user@example.com", "password123")
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue(e.message!!.contains("challenge"))
        }
        assertEquals("each challenge attempt is a single request", 2, server.requestCount)
    }

    @Test
    fun `captcha_required_raises_auth_error`() = runBlocking {
        server.enqueue(jsonResponse("""{"responseStatus":{"type":"CAPTCHA_REQUIRED"}}"""))

        try {
            client.login("user@example.com", "password123")
            fail("expected GarminAuthError")
        } catch (e: GarminAuthError) {
            assertTrue("message must mention captcha: ${e.message}",
                e.message!!.contains("CAPTCHA"))
        }
        assertEquals("captcha must not trigger strategy loops", 1, server.requestCount)
    }

    @Test
    fun `exchange_client_id_ladder_advances_on_400`() = runBlocking {
        server.enqueue(jsonResponse(loginSuccessBody()))
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant"}""")
        )
        server.enqueue(jsonResponse(tokenJson(farFutureJwt())))
        server.enqueue(jsonResponse("""{"displayName":"Test User"}"""))

        val result = client.login("user@example.com", "password123")

        assertTrue("expected Success after ladder advance, got $result",
            result is LoginResult.Success)
        assertEquals(4, server.requestCount)

        server.takeRequest() // login
        val first = server.takeRequest()
        assertEquals(basicAuth(CLIENT_ID_2025Q2), first.getHeader("Authorization"))
        val second = server.takeRequest()
        assertEquals("ladder must advance to 2024Q4 after a 400",
            basicAuth("GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4"),
            second.getHeader("Authorization"))
    }

    @Test
    fun `refresh_triggered_when_jwt_expires_within_900s`() = runBlocking {
        val expiringJwt = fakeJwt(System.currentTimeMillis() / 1000 + 600)
        val freshJwt = farFutureJwt()
        val tokens = GarminTokens(expiringJwt, "rt-old", CLIENT_ID_2025Q2)
        server.enqueue(jsonResponse(tokenJson(freshJwt, refreshToken = "rt-new")))
        server.enqueue(jsonResponse("""{"displayName":"Test User"}"""))

        val result = client.apiGet(tokens, "/userprofile-service/socialProfile")

        assertEquals("""{"displayName":"Test User"}""", result.body)
        assertEquals("expiring token: exactly refresh + API call", 2, server.requestCount)

        val refresh = server.takeRequest()
        assertEquals("/di-oauth2-service/oauth/token", refresh.path)
        val refreshBody = refresh.body.readUtf8()
        assertTrue("must be a refresh grant: $refreshBody",
            refreshBody.contains("grant_type=refresh_token"))
        assertTrue("must send the old refresh token: $refreshBody",
            refreshBody.contains("refresh_token=rt-old"))
        assertEquals("refresh uses Basic auth with the token's client id",
            basicAuth(CLIENT_ID_2025Q2), refresh.getHeader("Authorization"))

        val api = server.takeRequest()
        assertEquals("/userprofile-service/socialProfile", api.path)
        assertEquals("Bearer $freshJwt", api.getHeader("Authorization"))
    }

    @Test
    fun `api_401_refreshes_once_then_retries_once_second_401_is_auth_error`() = runBlocking {
        val tokens = GarminTokens(farFutureJwt(), "rt-old", CLIENT_ID_2025Q2)
        val freshJwt = farFutureJwt()
        server.enqueue(jsonResponse("", code = 401))
        server.enqueue(jsonResponse(tokenJson(freshJwt, refreshToken = "rt-new")))
        server.enqueue(jsonResponse("", code = 401))

        try {
            client.apiGet(tokens, "/userprofile-service/socialProfile")
            fail("expected GarminAuthError")
        } catch (e: GarminAuthError) {
            assertTrue(e.message!!.contains("401"))
        }

        // Exactly 2 API attempts + 1 refresh — no retry storm.
        assertEquals(3, server.requestCount)
        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/userprofile-service/socialProfile", first.path)
        assertEquals("/di-oauth2-service/oauth/token", refresh.path)
        assertEquals("/userprofile-service/socialProfile", second.path)
        assertEquals("Bearer ${tokens.diToken}", first.getHeader("Authorization"))
        assertEquals("Bearer $freshJwt", second.getHeader("Authorization"))
    }

    @Test
    fun `blank_credentials_fail_fast_before_network`() = runBlocking {
        try {
            client.login("", "password123")
            fail("expected GarminAuthError for blank email")
        } catch (e: GarminAuthError) {
            assertTrue(e.message!!.contains("blank"))
        }
        try {
            client.login("user@example.com", "  ")
            fail("expected GarminAuthError for blank password")
        } catch (e: GarminAuthError) {
            assertTrue(e.message!!.contains("blank"))
        }
        assertEquals("blank credentials must not touch the network", 0, server.requestCount)
    }

    @Test
    fun `unicode_password_json_encoded_utf8`() = runBlocking {
        enqueueSuccessfulChain()

        val result = client.login("user@example.com", "пароль123✓")

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        val login = server.takeRequest()
        assertEquals("application/json; charset=utf-8", login.getHeader("Content-Type"))
        val body = login.body.readUtf8()
        assertTrue("password must be UTF-8 encoded in the body: $body",
            body.contains("пароль123✓"))
        assertTrue(body.contains("\"username\":\"user@example.com\""))
        assertTrue(body.contains("\"rememberMe\":false"))
    }

    @Test
    fun `socialProfile_non_200_rejects_token`() = runBlocking {
        server.enqueue(jsonResponse(loginSuccessBody()))
        server.enqueue(jsonResponse(tokenJson(farFutureJwt())))
        repeat(3) { server.enqueue(jsonResponse("boom", code = 500)) }

        try {
            client.login("user@example.com", "password123")
            fail("expected login to fail on rejected token")
        } catch (e: GarminConnectionError) {
            assertTrue("message must name the failed endpoint: ${e.message}",
                e.message!!.contains("socialProfile"))
        }
        // login + exchange + 3 retried validation attempts, then a clean stop.
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `malformed_json_response_raises_connection_error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{{{not json at all")
        )

        try {
            client.login("user@example.com", "password123")
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            assertTrue("message must say the JSON is malformed: ${e.message}",
                e.message!!.contains("malformed"))
        }
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------ //
    //  Task .6 additions (audit gaps + wire-contract pins)                //
    // ------------------------------------------------------------------ //

    /**
     * The MFA verify request must reuse the SSO session established by the
     * login POST (Set-Cookie -> Cookie header), or Garmin rejects the code.
     */
    @Test
    fun `mfa_verify_request_carries_sso_session_cookie`() = runBlocking {
        server.enqueue(
            jsonResponse(mfaRequiredBody())
                .addHeader("Set-Cookie", "SESSIONID=abc123; Path=/; HttpOnly")
        )
        val result = client.login("user@example.com", "password123")
        assertTrue("expected NeedsMfa, got $result", result is LoginResult.NeedsMfa)

        // verifyCode -> exchange -> socialProfile validation
        server.enqueue(jsonResponse(loginSuccessBody(ticket = "ST-MFA-COOKIE")))
        server.enqueue(jsonResponse(tokenJson(farFutureJwt())))
        server.enqueue(jsonResponse("""{"displayName":"Test User"}"""))

        client.resumeLogin((result as LoginResult.NeedsMfa).clientState, "123456")

        server.takeRequest() // login (received the Set-Cookie)
        val verify = server.takeRequest()
        val cookie = verify.getHeader("Cookie")
        assertTrue(
            "MFA verify must carry the SSO session cookie from login, got: $cookie",
            cookie != null && cookie.contains("SESSIONID=abc123")
        )
    }

    /** All four ladder client ids rejected with 400 -> auth error, no retry. */
    @Test
    fun `exchange_ladder_exhaustion_4x400_raises_auth_error`() = runBlocking {
        server.enqueue(jsonResponse(loginSuccessBody()))
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"invalid_grant"}""")
            )
        }

        try {
            client.login("user@example.com", "password123")
            fail("expected GarminAuthError after the client-id ladder is exhausted")
        } catch (e: GarminAuthError) {
            assertTrue("message must name the ladder failure: ${e.message}",
                e.message!!.contains("all client IDs"))
        }
        assertEquals("1 login + all 4 ladder attempts, nothing retried", 5, server.requestCount)
    }

    /**
     * A dead refresh token (400 invalid_grant) on the forced 401-refresh is
     * an auth error and is never retried.
     */
    @Test
    fun `refresh_400_invalid_grant_raises_auth_error_no_retry`() = runBlocking {
        val tokens = GarminTokens(farFutureJwt(), "rt-dead", CLIENT_ID_2025Q2)
        server.enqueue(jsonResponse("", code = 401))
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant"}""")
        )

        try {
            client.apiGet(tokens, "/userprofile-service/socialProfile")
            fail("expected GarminAuthError for the dead refresh token")
        } catch (e: GarminAuthError) {
            assertTrue("message must name the refresh failure: ${e.message}",
                e.message!!.contains("refresh"))
        }
        assertEquals("1 API attempt + 1 refresh, never retried", 2, server.requestCount)
    }

    /** 429 on the refresh call surfaces immediately (single request). */
    @Test
    fun `refresh_429_surfaces_immediately`() = runBlocking {
        val expiring =
            GarminTokens(fakeJwt(System.currentTimeMillis() / 1000 + 600), "rt", CLIENT_ID_2025Q2)
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))

        try {
            client.apiGet(expiring, "/userprofile-service/socialProfile")
            fail("expected GarminTooManyRequestsError")
        } catch (e: GarminTooManyRequestsError) {
            assertTrue(e.message!!.contains("429"))
        }
        assertEquals("429 on refresh must surface after ONE request", 1, server.requestCount)
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun newClient(): GarminAuthClient {
        val base = server.url("/").toString().trimEnd('/')
        return GarminAuthClient(
            ssoBaseUrl = base,
            diauthBaseUrl = base,
            apiBaseUrl = base,
            apiBackoffDelayMs = { 0 } // no sleeping in tests
        )
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun htmlResponse(code: Int): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "text/html")
            .setBody(
                "<!DOCTYPE html><html><head><title>Just a moment...</title>" +
                    "</head><body>cf-challenge-window cloudflare</body></html>"
            )

    /** login -> exchange -> socialProfile all succeeding, in order. */
    private fun enqueueSuccessfulChain(tokenJson: String = tokenJson(farFutureJwt())) {
        server.enqueue(jsonResponse(loginSuccessBody()))
        server.enqueue(jsonResponse(tokenJson))
        server.enqueue(jsonResponse("""{"displayName":"Test User"}"""))
    }

    private fun loginSuccessBody(ticket: String = "ST-12345"): String =
        """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"$ticket"}"""

    private fun mfaRequiredBody(): String =
        """{"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{"mfaLastMethodUsed":"email"}}"""

    private fun tokenJson(jwt: String, refreshToken: String = "di-rt-1"): String =
        """{"access_token":"$jwt","refresh_token":"$refreshToken","token_type":"Bearer","expires_in":3599}"""

    private fun fakeJwt(
        expEpochSeconds: Long,
        clientId: String = CLIENT_ID_2025Q2
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"exp":$expEpochSeconds,"client_id":"$clientId"}""".toByteArray()
        )
        return "$header.$payload.sig"
    }

    private fun farFutureJwt(): String =
        fakeJwt(System.currentTimeMillis() / 1000 + 86_400)

    private fun basicAuth(clientId: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$clientId:".toByteArray())

    companion object {
        private const val CLIENT_ID_2025Q2 = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
    }
}
