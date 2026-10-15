package com.zamotkin.extrahealthgarmin.data

/**
 * Outcome of uploading not-yet-journaled [com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord]s
 * via [GarminUploadOrchestrator.uploadNewRecords].
 *
 * There is no residual file here: records that were not confirmed imported
 * simply stay un-journaled, so the next call retries exactly them.
 *
 *  - [NotNeeded] — the journal already covers every record; zero network.
 *  - [Success] — every new record was imported ([uploadedCount] of them).
 *  - [Partial] — some records were confirmed imported ([uploadedCount]);
 *    [failureMessages] describes the rest, which stay retriable.
 *  - [Retryable] — nothing was uploaded and nothing journaled (rate limit,
 *    transport failure, validation violation); the caller may retry later.
 *  - [LoginRequired] — no usable stored tokens (or they were rejected and
 *    cleared); the UI must run [GarminUploadOrchestrator.login] first.
 */
sealed class UploadOutcome {
    object NotNeeded : UploadOutcome()
    data class Success(val uploadedCount: Int) : UploadOutcome()
    data class Partial(
        val uploadedCount: Int,
        val failureMessages: List<String>
    ) : UploadOutcome()
    data class Retryable(val reason: String) : UploadOutcome()
    object LoginRequired : UploadOutcome()
}
