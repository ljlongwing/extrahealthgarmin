package com.example.tefal2garmin.garmin

import android.util.Log
import com.example.tefal2garmin.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Debug-only OkHttp interceptor for the Garmin auth/API wire, TAG `GarminHttp`.
 *
 * Double-guarded so release builds log nothing:
 *  1. [GarminAuthClient] only adds the interceptor when BuildConfig.DEBUG;
 *  2. the interceptor itself returns immediately when !BuildConfig.DEBUG,
 *     so even accidental wiring stays a zero-op.
 *
 * Everything it logs passes through [redact]: the values of `Authorization`,
 * `Bearer`/`Basic` credentials, and the JSON/form values of `password`,
 * `mfaVerificationCode`, `refresh_token`, `service_ticket`, `di_token` and
 * `access_token` are replaced with `<redacted>`. Key names stay visible on
 * purpose (they make the log readable and are not secrets).
 */
class RedactingHttpLogger : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.DEBUG) {
            return chain.proceed(chain.request())
        }
        val request = chain.request()
        Log.d(TAG, "--> ${request.method} ${redact(request.url.toString())}")
        request.body?.let { body ->
            try {
                val buffer = Buffer()
                body.writeTo(buffer)
                Log.d(TAG, "--> body: ${redact(buffer.readUtf8())}")
            } catch (e: Exception) {
                Log.d(TAG, "--> body: <unavailable: ${e.javaClass.simpleName}>")
            }
        }
        val response = chain.proceed(request)
        val bodySnippet = try {
            response.peekBody(MAX_BODY_LOG_BYTES).string()
        } catch (e: Exception) {
            "<unavailable: ${e.javaClass.simpleName}>"
        }
        Log.d(TAG, "<-- ${response.code} ${redact(bodySnippet)}")
        return response
    }

    /** Replaces every known secret value in [text] with `<redacted>`. */
    internal fun redact(text: String): String = text
        .replace(JSON_SECRET_VALUE_REGEX) { m -> "${m.groupValues[1]}<redacted>${m.groupValues[2]}" }
        .replace(FORM_SECRET_VALUE_REGEX) { m -> "${m.groupValues[1]}<redacted>" }
        .replace(AUTH_SCHEME_VALUE_REGEX) { m -> "${m.groupValues[1]} <redacted>" }

    companion object {
        private const val TAG = "GarminHttp"
        private const val MAX_BODY_LOG_BYTES = 64 * 1024L

        // JSON string values: {"password": "..."} / {"di_token":"..."} etc.
        // serviceTicketId is the camelCase form Garmin's login RESPONSE uses
        // (found in the live spike: request-side snake_case was redacted, the
        // response-side ticket leaked) — one-time CAS ticket, still a secret.
        private val JSON_SECRET_VALUE_REGEX = Regex(
            """("(?:password|mfaVerificationCode|refresh_token|service_ticket|serviceTicketId|di_token|access_token)"\s*:\s*")[^"]*(")""",
            RegexOption.IGNORE_CASE
        )

        // Form- and query-encoded values: refresh_token=...&service_ticket=...
        private val FORM_SECRET_VALUE_REGEX = Regex(
            """((?:refresh_token|service_ticket)=)[^&\s]*""",
            RegexOption.IGNORE_CASE
        )

        // Header-style credentials: "Bearer eyJ..." / "Basic R0NST..."
        private val AUTH_SCHEME_VALUE_REGEX = Regex(
            """\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+""",
            RegexOption.IGNORE_CASE
        )
    }
}
