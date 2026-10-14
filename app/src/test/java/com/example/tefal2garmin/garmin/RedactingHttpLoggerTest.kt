package com.example.tefal2garmin.garmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redaction guarantees for the debug HTTP logger: no credential, token or
 * ticket VALUE may survive into a log line.
 */
class RedactingHttpLoggerTest {

    private val logger = RedactingHttpLogger()

    @Test
    fun `redacts_json_secret_values`() {
        val body = "{\"username\":\"a@b.c\",\"password\":\"пароль123\"," +
            "\"mfaVerificationCode\":\"123456\",\"access_token\":\"jwt.value.here\"," +
            "\"refresh_token\":\"rt-1\",\"di_token\":\"d-1\",\"service_ticket\":\"ST-1\"}"

        val redacted = logger.redact(body)

        listOf("пароль123", "123456", "jwt.value.here", "rt-1", "d-1", "ST-1")
            .forEach { secret ->
                assertFalse("secret '$secret' must not survive: $redacted",
                    redacted.contains(secret))
            }
        listOf(
            "\"password\":", "\"mfaVerificationCode\":", "\"refresh_token\":",
            "\"di_token\":", "\"service_ticket\":", "\"username\":\"a@b.c\""
        ).forEach { marker ->
            assertTrue("marker '$marker' must stay visible: $redacted",
                redacted.contains(marker))
        }
    }

    @Test
    fun `redacts_bearer_and_basic_credentials`() {
        val header = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.abc.def and " +
            "Authorization: Basic R0NST05ORUNUX0NPTk5FQ1Q="

        val redacted = logger.redact(header)

        assertFalse(redacted.contains("eyJhbGciOiJSUzI1NiJ9.abc.def"))
        assertFalse(redacted.contains("R0NST05ORUNUX0NPTk5FQ1Q"))
        assertTrue(redacted.contains("Bearer <redacted>"))
        assertTrue(redacted.contains("Basic <redacted>"))
    }

    @Test
    fun `redacts_form_encoded_secrets`() {
        val form = "grant_type=refresh_token&refresh_token=rt-secret" +
            "&service_ticket=ST-secret&client_id=GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"

        val redacted = logger.redact(form)

        assertFalse(redacted.contains("rt-secret"))
        assertFalse(redacted.contains("ST-secret"))
        assertTrue("the grant_type value must survive (it is not a secret): $redacted",
            redacted.contains("grant_type=refresh_token"))
        assertTrue("client_id must survive (it is not a secret): $redacted",
            redacted.contains("client_id=GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"))
        assertTrue(redacted.contains("refresh_token=<redacted>"))
    }
}
