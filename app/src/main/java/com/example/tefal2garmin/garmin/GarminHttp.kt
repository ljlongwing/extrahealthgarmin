package com.example.tefal2garmin.garmin

import com.example.tefal2garmin.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Package-internal request machinery shared by [GarminAuthClient] and
 * [GarminUploadClient] (extracted from the auth client — its 20 wire tests
 * are the refactor contract). Owns: the OkHttpClient factory with the
 * debug-only [RedactingHttpLogger], transport-error mapping, the
 * Cloudflare/HTML guard around JSON parsing, the native GCM-Android header
 * set and the capped exponential backoff-with-jitter formula.
 */
internal object GarminHttp {

    /** A finished HTTP call: status, Content-Type header and fully-read body. */
    class HttpCallResult(val code: Int, val contentType: String?, val bodyText: String)

    fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(RedactingHttpLogger())
            }
        }
        .build()

    /** Executes [request]; transport failures surface as [GarminConnectionError]. */
    fun execute(client: OkHttpClient, request: Request): HttpCallResult {
        return try {
            client.newCall(request).execute().use { response ->
                HttpCallResult(
                    code = response.code,
                    contentType = response.header("Content-Type"),
                    bodyText = response.body?.string().orEmpty()
                )
            }
        } catch (e: IOException) {
            throw GarminConnectionError(
                "Network failure during ${request.method} ${request.url.encodedPath}: ${e.message}", e
            )
        }
    }

    /**
     * JSON parser with the Cloudflare guard: an HTML response (by
     * Content-Type or first byte) maps to [GarminConnectionError], never to a
     * JSON parse crash; malformed JSON maps to [GarminConnectionError] too.
     */
    fun parseJsonBody(stage: String, call: HttpCallResult): JSONObject {
        if (looksLikeHtml(call)) {
            throw GarminConnectionError(
                "$stage: HTML challenge response (HTTP ${call.code}) - " +
                    "likely Cloudflare/TLS-fingerprint block"
            )
        }
        return try {
            JSONObject(call.bodyText)
        } catch (e: JSONException) {
            throw GarminConnectionError(
                "$stage: malformed JSON response (HTTP ${call.code})", e
            )
        }
    }

    fun looksLikeHtml(call: HttpCallResult): Boolean {
        val htmlContentType = call.contentType?.contains("text/html", ignoreCase = true) == true
        return htmlContentType || call.bodyText.trimStart().startsWith("<")
    }

    /** The native GCM-Android header set every connectapi call must carry. */
    fun addNativeHeaders(builder: Request.Builder) {
        builder
            .header("User-Agent", NATIVE_USER_AGENT)
            .header("X-Garmin-User-Agent", NATIVE_X_GARMIN_USER_AGENT)
            .header("X-Garmin-Paired-App-Version", "10861")
            .header("X-Garmin-Client-Platform", "Android")
            .header("X-App-Ver", "10861")
            .header("X-Lang", "en")
            .header("X-GCExperience", "GC5")
            .header("Accept-Language", "en-US,en;q=0.9")
    }

    /** Capped exponential backoff with jitter: min(10 s, 1 s * 2^n) * (0.5 + rand(0.5)). */
    fun defaultBackoffDelayMs(attempt: Int): Long {
        val exponential = 1000L shl (attempt - 1).coerceIn(0, 10)
        val capped = minOf(10_000L, exponential)
        return (capped * (0.5 + Random.nextDouble(0.5))).toLong()
    }

    const val HTTP_OK = 200
    const val HTTP_NO_CONTENT = 204
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_NOT_FOUND = 404
    const val HTTP_TOO_MANY_REQUESTS = 429

    const val NATIVE_USER_AGENT = "GCM-Android-5.23"
    const val NATIVE_X_GARMIN_USER_AGENT =
        "com.garmin.android.apps.connectmobile/5.23; ; " +
            "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"

    private const val CALL_TIMEOUT_SECONDS = 15L
}
