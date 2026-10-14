package com.example.tefal2garmin.data

/**
 * Outcome of uploading not-yet-journaled [com.example.tefal2garmin.garmin.GarminWeighInRecord]s
 * via [GarminUploadOrchestrator.uploadNewRecords] — the records-based
 * successor of the CSV-carrying [UploadOutcome] (Strangler phase 1a; the CSV
 * type and its users stay alive until the records path fully replaces them).
 *
 * Unlike [UploadOutcome] there is NO residual CSV here: records that were
 * not confirmed imported simply stay un-journaled, so the next call retries
 * exactly them.
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
sealed class UploadOutcomeRecords {
    object NotNeeded : UploadOutcomeRecords()
    data class Success(val uploadedCount: Int) : UploadOutcomeRecords()
    data class Partial(
        val uploadedCount: Int,
        val failureMessages: List<String>
    ) : UploadOutcomeRecords()
    data class Retryable(val reason: String) : UploadOutcomeRecords()
    object LoginRequired : UploadOutcomeRecords()
}
