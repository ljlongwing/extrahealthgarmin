package com.zamotkin.extrahealthgarmin.garmin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Outcome of a batched weigh-in upload. */
sealed class UploadResult {

    /** Every record was accepted; [count] rows were uploaded. [tokens] are the
     * (possibly refreshed) credentials the request actually used — the caller
     * MUST persist them, or a rotated refresh token is lost (task 5 fix). */
    data class Success(val count: Int, val tokens: GarminTokens) : UploadResult()

    /**
     * Garmin reported per-record failures via `detailedImportResult.failures`.
     *
     * [succeededKeys] lists the composite journal keys
     * ([GarminWeighInRecord.journalKey]) of records that are provably imported — it is EMPTY whenever the failure entries carry no
     * correlatable record index (conservative by design: the caller journals
     * only provable successes and leaves everything else un-journaled for a
     * retry). Never guess success.
     */
    data class Partial(
        val succeededKeys: List<String>,
        val failureMessages: List<String>,
        val tokens: GarminTokens
    ) : UploadResult()
}

/**
 * Batched weigh-in uploader for the unofficial Garmin Connect
 * `POST /upload-service/upload` endpoint (the python-garminconnect
 * `add_body_composition` flow, Kotlin + official FIT SDK):
 *
 *  1. empty input short-circuits to [UploadResult.Success] with ZERO network
 *     calls (rate-limit hygiene); records are range-validated before encode;
 *  2. all records become ONE `body_composition.fit` multipart part (part
 *     name `file`) — no per-record upload loops;
 *  3. the request carries the native GCM-Android headers plus
 *     `Authorization: Bearer <di_token>`;
 *  4. `detailedImportResult` decides Success vs Partial.
 *
 * Retry discipline mirrors [GarminAuthClient.apiGet]: 5xx/transport retry up
 * to 3 attempts with jittered backoff (inject [backoffDelayMs] for tests);
 * 401 -> one forced refresh via the auth client -> one retry, a second 401
 * is [GarminAuthError]; 429 is never retried ([GarminTooManyRequestsError]);
 * auth errors never retry. Transport/auth/429/retries-exhausted surface as
 * typed exceptions, not result values.
 */
class GarminUploadClient(
    private val authClient: GarminAuthClient,
    private val apiBaseUrl: String = API_BASE_URL,
    private val backoffDelayMs: (attempt: Int) -> Long =
        { GarminHttp.defaultBackoffDelayMs(it) }
) {

    private val httpClient: OkHttpClient = GarminHttp.newHttpClient()

    /**
     * Uploads [records] as one batched weight-scale FIT file using [tokens].
     * Returns [UploadResult.Success] or [UploadResult.Partial]; throws typed
     * exceptions ([GarminValidationError] before any network call,
     * [GarminAuthError], [GarminTooManyRequestsError],
     * [GarminConnectionError]) for every failure mode.
     */
    suspend fun uploadWeighIns(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult = withContext(Dispatchers.IO) {
        if (records.isEmpty()) {
            Log.d(TAG, "Upload: no new records - skipping network entirely")
            return@withContext UploadResult.Success(0, tokens)
        }
        GarminWeighInRecord.validateRanges(records)
        var current = authClient.refreshIfNeeded(tokens)
        val fitBytes = FitWeightFileBuilder.build(records)
        var refreshedAfter401 = false
        var attempt = 0
        while (true) {
            attempt++
            val call = try {
                GarminHttp.execute(httpClient, uploadRequest(current, fitBytes))
            } catch (e: GarminConnectionError) {
                if (attempt < MAX_ATTEMPTS) {
                    backoffAndWait(attempt)
                    continue
                }
                throw e
            }
            when {
                call.code == GarminHttp.HTTP_NO_CONTENT ->
                    return@withContext UploadResult.Success(records.size, current)
                call.code == GarminHttp.HTTP_UNAUTHORIZED && !refreshedAfter401 -> {
                    Log.d(TAG, "Upload: HTTP 401 - refreshing DI token once and retrying")
                    current = authClient.forceRefresh(current)
                    refreshedAfter401 = true
                }
                call.code == GarminHttp.HTTP_UNAUTHORIZED ->
                    throw GarminAuthError("Upload rejected: HTTP 401 even after token refresh")
                call.code == GarminHttp.HTTP_TOO_MANY_REQUESTS ->
                    throw GarminTooManyRequestsError(
                        "Upload returned HTTP 429 - rate limited by Garmin"
                    )
                call.code == GarminHttp.HTTP_NOT_FOUND ->
                    throw GarminNotFoundError("Upload endpoint returned HTTP 404")
                call.code >= 500 && attempt < MAX_ATTEMPTS -> backoffAndWait(attempt)
                else -> return@withContext parseUploadOutcome(call, records, current)
            }
        }
        throw IllegalStateException("upload retry loop must never exit normally")
    }

    // ------------------------------------------------------------------ //
    //  Response parsing                                                   //
    // ------------------------------------------------------------------ //

    /**
     * Final (non-retryable) response handling: HTML challenge ->
     * [GarminConnectionError]; malformed JSON -> [GarminConnectionError];
     * `detailedImportResult.failures` -> [UploadResult.Partial]; 2xx without
     * failures -> [UploadResult.Success]; any other status ->
     * [GarminConnectionError] carrying the server's message/content detail
     * (python-garminconnect client.py:1718-1727 extraction order).
     */
    private fun parseUploadOutcome(
        call: GarminHttp.HttpCallResult,
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult {
        val json = GarminHttp.parseJsonBody("Upload", call)
        val failures = json.optJSONObject(DETAILED_IMPORT_RESULT_KEY)
            ?.optJSONArray(FAILURES_KEY)
        return if (failures != null && failures.length() > 0) {
            buildPartial(failures, records, tokens)
        } else if (call.code in 200..299) {
            UploadResult.Success(records.size, tokens)
        } else {
            val detail = json.optString(MESSAGE_KEY)
                .ifBlank { json.optString(CONTENT_KEY) }
                .ifBlank {
                    json.optJSONObject(DETAILED_IMPORT_RESULT_KEY)
                        ?.optJSONArray(FAILURES_KEY)
                        ?.optJSONObject(0)
                        ?.optJSONArray(MESSAGES_KEY)
                        ?.optString(0)
                        .orEmpty()
                }
            throw GarminConnectionError(
                "Upload failed: HTTP ${call.code}" +
                    if (detail.isBlank()) "" else " - $detail"
            )
        }
    }

    /**
     * Conservative Partial: succeededKeys stays EMPTY unless EVERY failure
     * entry carries an explicit, in-range record index; only then can the
     * un-failed batch positions be proven imported.
     */
    private fun buildPartial(
        failures: JSONArray,
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult.Partial {
        val messages = mutableListOf<String>()
        val failedIndices = mutableSetOf<Int>()
        var allCorrelatable = true
        for (i in 0 until failures.length()) {
            val entry = failures.optJSONObject(i)
            if (entry == null) {
                allCorrelatable = false
                continue
            }
            val entryMessages = entry.optJSONArray(MESSAGES_KEY)
            if (entryMessages != null && entryMessages.length() > 0) {
                for (j in 0 until entryMessages.length()) {
                    messages += entryMessages.optString(j)
                }
            } else {
                messages += "Failure ${i + 1} (no message)"
            }
            val index = entry.optInt(INDEX_KEY, -1)
            if (index in records.indices) {
                failedIndices += index
            } else {
                allCorrelatable = false
            }
        }
        val succeededKeys = if (allCorrelatable) {
            records.indices
                .filter { it !in failedIndices }
                .map { records[it].journalKey() }
        } else {
            emptyList()
        }
        Log.w(
            TAG,
            "Upload partial: ${failedIndices.size} failed, " +
                "${succeededKeys.size} confirmed, correlatable=$allCorrelatable"
        )
        return UploadResult.Partial(succeededKeys, messages, tokens)
    }

    // ------------------------------------------------------------------ //
    //  Request building                                                   //
    // ------------------------------------------------------------------ //

    private fun uploadRequest(tokens: GarminTokens, fitBytes: ByteArray): Request {
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                PART_NAME,
                FIT_FILENAME,
                fitBytes.toRequestBody(FIT_MEDIA_TYPE)
            )
            .build()
        return Request.Builder()
            .url("$apiBaseUrl$UPLOAD_PATH")
            .header("Authorization", "Bearer ${tokens.diToken}")
            .header("Accept", "application/json")
            .apply { GarminHttp.addNativeHeaders(this) }
            .post(multipartBody)
            .build()
    }

    private suspend fun backoffAndWait(attempt: Int) {
        val delayMs = backoffDelayMs(attempt)
        Log.d(TAG, "Upload: transient failure; backing off ${delayMs}ms (attempt $attempt)")
        delay(delayMs)
    }

    companion object {
        private const val TAG = "GarminUpload"

        private const val API_BASE_URL = "https://connectapi.garmin.com"
        private const val UPLOAD_PATH = "/upload-service/upload"
        private const val PART_NAME = "file"
        private const val FIT_FILENAME = "body_composition.fit"
        private val FIT_MEDIA_TYPE = "application/octet-stream".toMediaType()

        private const val DETAILED_IMPORT_RESULT_KEY = "detailedImportResult"
        private const val FAILURES_KEY = "failures"
        private const val MESSAGES_KEY = "messages"
        private const val INDEX_KEY = "index"
        private const val MESSAGE_KEY = "message"
        private const val CONTENT_KEY = "content"

        private const val MAX_ATTEMPTS = 3
    }
}
