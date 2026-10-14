package com.example.tefal2garmin.garmin

import android.util.Log
import com.example.tefal2garmin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/** DI OAuth2 tokens returned by a successful login/refresh. */
data class GarminTokens(
    val diToken: String,
    val diRefreshToken: String,
    val diClientId: String
)

/**
 * Opaque state returned by [GarminAuthClient.login] when Garmin answers
 * MFA_REQUIRED. Pass it back to [GarminAuthClient.resumeLogin] together with
 * the verification code. Carries no credentials.
 */
data class MfaClientState(val mfaMethod: String)

/** Result of [GarminAuthClient.login]: either tokens or a pending MFA flow. */
sealed interface LoginResult {
    data class Success(val tokens: GarminTokens) : LoginResult
    data class NeedsMfa(val clientState: MfaClientState) : LoginResult
}

/** Result of an authenticated API GET; tokens may have been refreshed. */
data class ApiGetResult(val body: String, val tokens: GarminTokens)

/**
 * Garmin DI OAuth2 auth client — Kotlin port of the python-garminconnect
 * 0.3.x fork's mobile strategy (sso.garmin.com/mobile/api/login), with the
 * ticket-to-DI exchange ladder, exp-based refresh, MFA resume and
 * socialProfile token validation.
 *
 * Wire contract (verified against the fork):
 *  1. POST {sso}/mobile/api/login?clientId=GCM_IOS_DARK&locale=en-US&service=<ios url>
 *     with JSON {username, password, rememberMe:false, captchaToken:""} and an
 *     iPhone Safari UA. SUCCESSFUL -> serviceTicketId; MFA_REQUIRED ->
 *     [LoginResult.NeedsMfa]; INVALID_USERNAME_PASSWORD / CAPTCHA_REQUIRED ->
 *     [GarminAuthError]; HTTP 429 -> [GarminTooManyRequestsError]; HTML body ->
 *     [GarminConnectionError] (Cloudflare/TLS-fingerprint challenge).
 *  2. POST https://diauth.garmin.com/di-oauth2-service/oauth/token with Basic
 *     auth from the client-id ladder (2025Q2 -> 2024Q4 -> ANDROID_DI -> IOS_DI,
 *     advancing on 400/401, max 4 attempts) and form
 *     grant_type=<service_ticket grant>&service_ticket=...&service_url=<ios url>.
 *  3. GET https://connectapi.garmin.com/userprofile-service/socialProfile with
 *     `Authorization: Bearer <di_token>` validates the token; a non-200 after
 *     retries fails the login.
 *
 * Retry discipline (epic anti-patterns): login/MFA/exchange/refresh are never
 * retried; 429 and auth errors are never retried anywhere; only post-auth API
 * GETs retry (3 attempts, capped exponential backoff with jitter).
 *
 * JWT `exp` is read from the unverified Base64 payload (parse only, no crypto
 * library); a malformed JWT forces a refresh. All requests run on
 * [Dispatchers.IO] with a 15 s call timeout. Stages are logged under TAG
 * `GarminAuth` — never secrets.
 */
class GarminAuthClient(
    private val ssoBaseUrl: String = SSO_BASE_URL,
    private val diauthBaseUrl: String = DIAUTH_BASE_URL,
    private val apiBaseUrl: String = API_BASE_URL,
    private val apiBackoffDelayMs: (attempt: Int) -> Long = { defaultApiBackoffDelayMs(it) }
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(RedactingHttpLogger())
            }
        }
        .build()

    /** In-flight MFA state (client with the SSO session cookies + method). */
    private var pendingMfa: PendingMfa? = null

    /**
     * Starts a DI OAuth2 login. Blank credentials fail fast with zero network
     * calls. Throws [GarminTooManyRequestsError] on 429 (never retried) and
     * [GarminAuthError] on credential rejection.
     */
    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (pendingMfa != null) {
            throw GarminAuthError(
                "MFA login already in progress; complete it with resumeLogin() or call logout() first"
            )
        }
        if (email.isBlank() || password.isBlank()) {
            throw GarminAuthError("Email and password must not be blank")
        }
        val ssoClient = httpClient.newBuilder()
            .cookieJar(InMemoryCookieJar())
            .build()
        val url = ssoUrl(LOGIN_PATH)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LOGIN_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", ssoBaseUrl)
            .post(
                jsonBody(
                    "username" to email,
                    "password" to password,
                    "rememberMe" to false,
                    "captchaToken" to ""
                )
            )
            .build()
        Log.d(TAG, "Login: POST $LOGIN_PATH")
        val call = execute(ssoClient, request)
        if (call.code == HTTP_TOO_MANY_REQUESTS) {
            throw GarminTooManyRequestsError(
                "Mobile login returned 429 - rate limited or blocked by Garmin"
            )
        }
        val json = parseJsonBody("Mobile login", call)
        handleLoginResponse(call, json, ssoClient)
    }

    /**
     * Completes a login that returned [LoginResult.NeedsMfa]. A failed code
     * keeps the pending MFA session so the caller may retry with a corrected
     * code; success clears it.
     */
    suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
        withContext(Dispatchers.IO) {
            val pending = pendingMfa
                ?: throw GarminAuthError("No pending MFA login; start one with login() first")
            if (code.isBlank()) {
                throw GarminAuthError("MFA verification code must not be blank")
            }
            Log.d(TAG, "MFA: POST $MFA_VERIFY_PATH (method=${pending.mfaMethod})")
            val request = Request.Builder()
                .url(ssoUrl(MFA_VERIFY_PATH))
                .header("User-Agent", LOGIN_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", ssoBaseUrl)
                .post(
                    jsonBody(
                        "mfaMethod" to pending.mfaMethod,
                        "mfaVerificationCode" to code,
                        "rememberMyBrowser" to true,
                        "reconsentList" to org.json.JSONArray(),
                        "mfaSetup" to false
                    )
                )
                .build()
            val call = execute(pending.ssoClient, request)
            if (call.code == HTTP_TOO_MANY_REQUESTS) {
                throw GarminTooManyRequestsError("MFA verification returned 429")
            }
            val json = parseJsonBody("MFA verification", call)
            val responseType = json.optJSONObject("responseStatus")?.optString("type").orEmpty()
            when {
                responseType == "SUCCESSFUL" -> {
                    val ticket = json.optString(SERVICE_TICKET_KEY).takeUnless { it.isBlank() }
                        ?: throw GarminAuthError(
                            "MFA verification succeeded but returned no $SERVICE_TICKET_KEY"
                        )
                    val tokens = exchangeTicketForTokens(ticket)
                    val validated = apiGet(tokens, SOCIAL_PROFILE_PATH)
                    pendingMfa = null
                    validated.tokens
                }
                json.optJSONObject(ERROR_KEY)?.optString(STATUS_CODE_KEY) == "429" ->
                    throw GarminTooManyRequestsError("MFA verification: 429 in JSON body")
                else -> {
                    val status = responseType.ifBlank {
                        json.optJSONObject(ERROR_KEY)?.optString(STATUS_CODE_KEY).orEmpty()
                            .ifBlank { "unknown" }
                    }
                    throw GarminAuthError("MFA verification failed: $status (HTTP ${call.code})")
                }
            }
        }

    /** Discards any pending MFA flow (client-side state only). */
    fun logout() {
        pendingMfa = null
    }

    /**
     * Refreshes [tokens] when the DI JWT expires within 900 s (or is
     * malformed); returns them unchanged otherwise. Refresh failures throw
     * [GarminAuthError] (callers clear the token store -> LoginRequired) and
     * are never retried.
     */
    suspend fun refreshIfNeeded(tokens: GarminTokens): GarminTokens {
        return if (tokenExpiresWithinMargin(tokens.diToken)) refreshTokens(tokens) else tokens
    }

    /**
     * Authenticated API GET with the native GCM-Android headers.
     *
     * Refreshes once on expiry and retries once after a 401; retries with
     * backoff on 5xx/transport (max 3 attempts); 429/404 surface immediately.
     * Returns the body plus the (possibly refreshed) tokens.
     */
    suspend fun apiGet(tokens: GarminTokens, path: String): ApiGetResult =
        withContext(Dispatchers.IO) {
            var current = refreshIfNeeded(tokens)
            var refreshedAfter401 = false
            var attempt = 0
            while (true) {
                attempt++
                val call = try {
                    execute(httpClient, apiGetRequest(current, path))
                } catch (e: GarminConnectionError) {
                    if (attempt < API_MAX_ATTEMPTS) {
                        backoffAndWait(attempt, path)
                        continue
                    }
                    throw e
                }
                when {
                    call.code == HTTP_OK -> return@withContext ApiGetResult(call.bodyText, current)
                    call.code == HTTP_UNAUTHORIZED && !refreshedAfter401 -> {
                        Log.d(TAG, "API $path: HTTP 401 - refreshing DI token once and retrying")
                        current = refreshTokens(current)
                        refreshedAfter401 = true
                    }
                    call.code == HTTP_UNAUTHORIZED ->
                        throw GarminAuthError("API $path: token rejected (HTTP 401) even after refresh")
                    call.code == HTTP_TOO_MANY_REQUESTS ->
                        throw GarminTooManyRequestsError("API $path returned HTTP 429 - rate limited")
                    call.code == HTTP_NOT_FOUND ->
                        throw GarminNotFoundError("API $path returned HTTP 404")
                    call.code >= 500 && attempt < API_MAX_ATTEMPTS -> backoffAndWait(attempt, path)
                    else ->
                        throw GarminConnectionError(
                            "API $path failed: HTTP ${call.code} after $attempt attempt(s)"
                        )
                }
            }
            throw IllegalStateException("apiGet retry loop must never exit normally")
        }

    // ------------------------------------------------------------------ //
    //  Login response handling                                            //
    // ------------------------------------------------------------------ //

    private suspend fun handleLoginResponse(
        call: HttpCallResult,
        json: JSONObject,
        ssoClient: OkHttpClient
    ): LoginResult {
        val responseType = json.optJSONObject(RESPONSE_STATUS_KEY)?.optString("type").orEmpty()
        when {
            responseType == "SUCCESSFUL" -> {
                val ticket = json.optString(SERVICE_TICKET_KEY).takeUnless { it.isBlank() }
                    ?: throw GarminConnectionError(
                        "Mobile login: SUCCESSFUL response without $SERVICE_TICKET_KEY"
                    )
                val tokens = exchangeTicketForTokens(ticket)
                val validated = apiGet(tokens, SOCIAL_PROFILE_PATH)
                return LoginResult.Success(validated.tokens)
            }
            responseType == "MFA_REQUIRED" -> {
                val method = json.optJSONObject(CUSTOMER_MFA_INFO_KEY)
                    ?.optString("mfaLastMethodUsed")
                    ?.takeUnless { it.isBlank() }
                    ?: "email"
                pendingMfa = PendingMfa(ssoClient, method)
                Log.i(TAG, "Login: MFA required (method=$method)")
                return LoginResult.NeedsMfa(MfaClientState(mfaMethod = method))
            }
            responseType == "INVALID_USERNAME_PASSWORD" || call.code == HTTP_UNAUTHORIZED ->
                throw GarminAuthError("401 Unauthorized (Invalid Username or Password)")
            json.optJSONObject(ERROR_KEY)?.optString(STATUS_CODE_KEY) == "429" ->
                throw GarminTooManyRequestsError("Mobile login: 429 in JSON body")
            responseType == "CAPTCHA_REQUIRED" ->
                throw GarminAuthError(
                    "Mobile login: CAPTCHA required (bot challenge); non-interactive login is impossible"
                )
            else -> throw GarminConnectionError(
                "Mobile login failed: HTTP ${call.code}, " +
                    "responseStatus=${responseType.ifBlank { "unknown" }}"
            )
        }
    }

    // ------------------------------------------------------------------ //
    //  Ticket -> DI token exchange (client-id ladder)                     //
    // ------------------------------------------------------------------ //

    private suspend fun exchangeTicketForTokens(ticket: String): GarminTokens =
        withContext(Dispatchers.IO) {
            var lastCode = 0
            for ((index, clientId) in DI_CLIENT_IDS.withIndex()) {
                Log.d(TAG, "DI exchange: attempt ${index + 1}/${DI_CLIENT_IDS.size} (client id $clientId)")
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("service_ticket", ticket)
                    .add("grant_type", DI_GRANT_TYPE)
                    .add("service_url", SERVICE_URL)
                    .build()
                val request = Request.Builder()
                    .url("$diauthBaseUrl$DI_TOKEN_PATH")
                    .header("Authorization", Credentials.basic(clientId, ""))
                    .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .apply { addNativeHeaders(this) }
                    .post(form)
                    .build()
                val call = execute(httpClient, request)
                if (call.code == HTTP_TOO_MANY_REQUESTS) {
                    throw GarminTooManyRequestsError("DI token exchange rate limited (HTTP 429)")
                }
                if (looksLikeHtml(call)) {
                    throw GarminConnectionError(
                        "DI token exchange: HTML challenge response (HTTP ${call.code}) - " +
                            "likely Cloudflare/TLS-fingerprint block"
                    )
                }
                if (call.code !in 200..299) {
                    lastCode = call.code
                    Log.d(TAG, "DI exchange rejected for $clientId: HTTP ${call.code}")
                    continue
                }
                val tokens = parseExchangePayload(call, clientId)
                if (tokens != null) return@withContext tokens
                Log.d(TAG, "DI exchange: unusable token payload for $clientId")
            }
            throw GarminAuthError("DI token exchange failed for all client IDs (last HTTP $lastCode)")
        }

    private fun parseExchangePayload(call: HttpCallResult, clientId: String): GarminTokens? {
        return runCatching {
            val json = JSONObject(call.bodyText)
            val accessToken = json.getString(ACCESS_TOKEN_KEY)
            GarminTokens(
                diToken = accessToken,
                diRefreshToken = json.optString(REFRESH_TOKEN_KEY),
                diClientId = extractJwtStringClaim(accessToken, "client_id") ?: clientId
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------ //
    //  Refresh                                                            //
    // ------------------------------------------------------------------ //

    private suspend fun refreshTokens(tokens: GarminTokens): GarminTokens =
        withContext(Dispatchers.IO) {
            if (tokens.diRefreshToken.isBlank() || tokens.diClientId.isBlank()) {
                throw GarminAuthError("No DI refresh token available")
            }
            Log.d(TAG, "Refreshing DI token via refresh grant")
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", tokens.diClientId)
                .add("refresh_token", tokens.diRefreshToken)
                .build()
            val request = Request.Builder()
                .url("$diauthBaseUrl$DI_TOKEN_PATH")
                .header("Authorization", Credentials.basic(tokens.diClientId, ""))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .apply { addNativeHeaders(this) }
                .post(form)
                .build()
            val call = execute(httpClient, request)
            if (call.code == HTTP_TOO_MANY_REQUESTS) {
                throw GarminTooManyRequestsError("DI token refresh rate limited (HTTP 429)")
            }
            if (looksLikeHtml(call)) {
                throw GarminConnectionError(
                    "DI token refresh: HTML challenge response (HTTP ${call.code}) - " +
                        "likely Cloudflare/TLS-fingerprint block"
                )
            }
            if (call.code !in 200..299) {
                // Covers 400 invalid_grant (dead refresh token): auth error,
                // never retried; the caller clears the token store.
                throw GarminAuthError("DI token refresh failed: HTTP ${call.code}")
            }
            try {
                val json = JSONObject(call.bodyText)
                val accessToken = json.getString(ACCESS_TOKEN_KEY)
                GarminTokens(
                    diToken = accessToken,
                    diRefreshToken = json.optString(REFRESH_TOKEN_KEY)
                        .ifBlank { tokens.diRefreshToken },
                    diClientId = extractJwtStringClaim(accessToken, "client_id") ?: tokens.diClientId
                )
            } catch (e: JSONException) {
                throw GarminConnectionError(
                    "DI token refresh: malformed JSON response (HTTP ${call.code})", e
                )
            }
        }

    // ------------------------------------------------------------------ //
    //  JWT (unverified payload parse only)                                //
    // ------------------------------------------------------------------ //

    /** True when the token is malformed or expires within the refresh margin. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun tokenExpiresWithinMargin(token: String): Boolean {
        val exp = jwtExpiryEpochSeconds(token) ?: return true // malformed -> force refresh
        return System.currentTimeMillis() / 1000L > exp - EXP_REFRESH_MARGIN_SECONDS
    }

    /** Unverified JWT payload `exp` claim; null when undecodable or absent. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun jwtExpiryEpochSeconds(token: String): Long? {
        return try {
            val payload = jwtPayloadObject(token) ?: return null
            when (val rawExp = payload.opt("exp")) {
                is Number -> rawExp.toLong()
                is String -> rawExp.toLongOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Unverified JWT payload string claim (e.g. `client_id`); null when absent. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun extractJwtStringClaim(token: String, claim: String): String? {
        return try {
            jwtPayloadObject(token)?.optString(claim)?.takeUnless { it.isBlank() }
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwtPayloadObject(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val header = JSONObject(decodeBase64Url(parts[0]))
        if (header.optString("alg") == "none") return null // reject unsigned tokens
        return JSONObject(decodeBase64Url(parts[1]))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(value: String): String {
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .decode(value)
            .decodeToString()
    }

    // ------------------------------------------------------------------ //
    //  HTTP plumbing                                                      //
    // ------------------------------------------------------------------ //

    private class HttpCallResult(val code: Int, val contentType: String?, val bodyText: String)

    private class PendingMfa internal constructor(
        val ssoClient: OkHttpClient,
        val mfaMethod: String
    )

    /** Minimal session cookie jar: keeps SSO cookies across login -> MFA. */
    private class InMemoryCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach {
                this.cookies["${it.domain}|${it.path}|${it.name}"] = it
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.values.filter { it.matches(url) }
    }

    private fun execute(client: OkHttpClient, request: Request): HttpCallResult {
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
    private fun parseJsonBody(stage: String, call: HttpCallResult): JSONObject {
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

    private fun looksLikeHtml(call: HttpCallResult): Boolean {
        val htmlContentType = call.contentType?.contains("text/html", ignoreCase = true) == true
        return htmlContentType || call.bodyText.trimStart().startsWith("<")
    }

    private fun ssoUrl(path: String): HttpUrl =
        "$ssoBaseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("clientId", SSO_CLIENT_ID)
            .addQueryParameter("locale", "en-US")
            .addQueryParameter("service", SERVICE_URL)
            .build()

    private fun apiGetRequest(tokens: GarminTokens, path: String): Request =
        Request.Builder()
            .url("$apiBaseUrl$path")
            .header("Authorization", "Bearer ${tokens.diToken}")
            .header("Accept", "application/json")
            .apply { addNativeHeaders(this) }
            .get()
            .build()

    private fun addNativeHeaders(builder: Request.Builder) {
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

    private fun jsonBody(vararg fields: Pair<String, Any?>): okhttp3.RequestBody {
        val json = JSONObject()
        fields.forEach { (name, value) -> json.put(name, value ?: JSONObject.NULL) }
        return json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private suspend fun backoffAndWait(attempt: Int, path: String) {
        val delayMs = apiBackoffDelayMs(attempt)
        Log.d(TAG, "API $path: transient failure; backing off ${delayMs}ms (attempt $attempt)")
        delay(delayMs)
    }

    companion object {
        private const val TAG = "GarminAuth"

        private const val SSO_BASE_URL = "https://sso.garmin.com"
        private const val DIAUTH_BASE_URL = "https://diauth.garmin.com"
        private const val API_BASE_URL = "https://connectapi.garmin.com"

        private const val LOGIN_PATH = "/mobile/api/login"
        private const val MFA_VERIFY_PATH = "/mobile/api/mfa/verifyCode"
        private const val DI_TOKEN_PATH = "/di-oauth2-service/oauth/token"
        private const val SOCIAL_PROFILE_PATH = "/userprofile-service/socialProfile"

        private const val SSO_CLIENT_ID = "GCM_IOS_DARK"
        private const val SERVICE_URL = "https://mobile.integration.garmin.com/gcm/ios"
        private const val LOGIN_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        private const val NATIVE_USER_AGENT = "GCM-Android-5.23"
        private const val NATIVE_X_GARMIN_USER_AGENT =
            "com.garmin.android.apps.connectmobile/5.23; ; " +
                "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"

        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI"
        )
        private const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

        private const val RESPONSE_STATUS_KEY = "responseStatus"
        private const val SERVICE_TICKET_KEY = "serviceTicketId"
        private const val CUSTOMER_MFA_INFO_KEY = "customerMfaInfo"
        private const val ERROR_KEY = "error"
        private const val STATUS_CODE_KEY = "status-code"
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val REFRESH_TOKEN_KEY = "refresh_token"

        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private const val EXP_REFRESH_MARGIN_SECONDS = 900L
        private const val API_MAX_ATTEMPTS = 3
        private const val CALL_TIMEOUT_SECONDS = 15L

        /**
         * Capped exponential backoff with jitter for post-auth API GET
         * retries: min(10 s, 1 s * 2^n) * (0.5 + rand(0.5)).
         */
        fun defaultApiBackoffDelayMs(attempt: Int): Long {
            val exponential = 1000L shl (attempt - 1).coerceIn(0, 10)
            val capped = minOf(10_000L, exponential)
            return (capped * (0.5 + Random.nextDouble(0.5))).toLong()
        }
    }
}
