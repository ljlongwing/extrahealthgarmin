package com.example.tefal2garmin.utils

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Utility class for validating and handling CSV MIME types
 */
class MimeTypeValidator {

    companion object {
        private const val TAG = "MimeTypeValidator"

        // Supported CSV MIME types in order of preference
        private val SUPPORTED_CSV_MIME_TYPES = setOf(
            // Official and standard MIME types
            "text/csv",                           // RFC 4180 official MIME type
            "text/comma-separated-values",        // Alternative text-based MIME type

            // Application-specific MIME types
            "application/csv",                    // Application-specific CSV
            "application/x-csv",                  // Non-standard but common

            // Fallback MIME types that may contain CSV
            "text/plain",                         // Plain text (may contain CSV)
            "application/vnd.ms-excel",           // Excel (sometimes used for CSV)
            "application/octet-stream",           // Generic binary (may contain CSV)

            // Wildcard patterns
            "text/*"                              // Any text-based file
        )

        // MIME types that are highly likely to be CSV
        private val HIGH_CONFIDENCE_CSV_TYPES = setOf(
            "text/csv",
            "text/comma-separated-values",
            "application/csv",
            "application/x-csv"
        )

        // MIME types that need content inspection
        private val INSPECT_CONTENT_TYPES = setOf(
            "text/plain",
            "application/octet-stream",
            "text/*",
            "application/vnd.ms-excel"
        )

        /**
         * Checks if the given MIME type is supported for CSV processing
         */
        fun isCsvMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) {
                return false
            }

            // Direct match
            if (SUPPORTED_CSV_MIME_TYPES.contains(mimeType)) {
                return true
            }

            // Check wildcard patterns
            if (mimeType == "text/*" || mimeType.startsWith("text/")) {
                return true
            }

            return false
        }

        /**
         * Determines confidence level for CSV processing
         */
        fun getCsvConfidenceLevel(mimeType: String?): CsvConfidence {
            if (mimeType.isNullOrBlank()) {
                return CsvConfidence.NONE
            }

            return when {
                HIGH_CONFIDENCE_CSV_TYPES.contains(mimeType) -> CsvConfidence.HIGH
                INSPECT_CONTENT_TYPES.contains(mimeType) -> CsvConfidence.MEDIUM
                mimeType.startsWith("text/") -> CsvConfidence.LOW
                else -> CsvConfidence.NONE
            }
        }

        /**
         * Validates if an intent contains a supported CSV file
         */
        fun validateIntentForCsv(intent: Intent?): IntentValidationResult {
            if (intent == null) {
                return IntentValidationResult(false, null, "No intent provided")
            }

            val action = intent.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
                return IntentValidationResult(false, null, "Unsupported intent action: $action")
            }

            // Check for URI-based sharing
            val uri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) {
                val mimeType = intent.type
                val confidence = getCsvConfidenceLevel(mimeType)

                return if (isCsvMimeType(mimeType)) {
                    IntentValidationResult(true, uri, null, mimeType, confidence)
                } else {
                    IntentValidationResult(false, uri, "Unsupported MIME type: $mimeType", mimeType, confidence)
                }
            }

            // Check for text-based sharing
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                val mimeType = intent.type ?: "text/plain"
                val confidence = getCsvConfidenceLevel(mimeType)

                return if (isCsvMimeType(mimeType)) {
                    IntentValidationResult(true, null, null, mimeType, confidence, text)
                } else {
                    IntentValidationResult(false, null, "Unsupported MIME type for text: $mimeType", mimeType, confidence, text)
                }
            }

            return IntentValidationResult(false, null, "No URI or text content found in intent")
        }

        /**
         * Checks if content appears to be CSV based on structure
         * This is a basic heuristic for files with ambiguous MIME types
         */
        fun appearsToBeCsv(content: String): Boolean {
            if (content.isBlank()) {
                return false
            }

            val lines = content.lines().filter { it.isNotBlank() }
            if (lines.size < 2) {
                return false // Need at least header + 1 data row
            }

            // Check if first few lines have comma-separated values
            val sampleLines = lines.take(3)
            return sampleLines.all { line ->
                // Basic check: contains commas and reasonable field count
                val commaCount = line.count { it == ',' }
                commaCount >= 2 && commaCount <= 20 // Reasonable range for CSV
            }
        }

        /**
         * Gets a user-friendly description of the MIME type
         */
        fun getMimeTypeDescription(mimeType: String?): String {
            return when (mimeType) {
                "text/csv" -> "CSV file (RFC 4180 standard)"
                "text/comma-separated-values" -> "Comma-separated values (text)"
                "application/csv" -> "CSV file (application)"
                "application/x-csv" -> "CSV file (non-standard)"
                "text/plain" -> "Plain text file (may contain CSV)"
                "application/vnd.ms-excel" -> "Excel file (may contain CSV)"
                "application/octet-stream" -> "Binary file (may contain CSV)"
                "text/*" -> "Text file (any type)"
                else -> mimeType ?: "Unknown"
            }
        }

        /**
         * Logs MIME type handling for debugging
         */
        fun logMimeTypeHandling(mimeType: String?, uri: Uri?, action: String) {
            val description = getMimeTypeDescription(mimeType)
            val confidence = getCsvConfidenceLevel(mimeType)

            Log.d(TAG, "MIME Type Handling - Action: $action, Type: $mimeType ($description), Confidence: $confidence, URI: $uri")
        }

        /**
         * Compatibility method for getting parcelable extras across Android versions
         */
        private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(name, clazz)
            } else {
                @Suppress("UNCHECKED_CAST")
                intent.getParcelableExtra(name) as? T
            }
        }
    }

    enum class CsvConfidence {
        HIGH,    // Very likely CSV (text/csv, application/csv, etc.)
        MEDIUM,  // Possibly CSV (text/plain, application/octet-stream)
        LOW,     // Unlikely but possible CSV (text/*)
        NONE     // Not CSV
    }

    data class IntentValidationResult(
        val isValid: Boolean,
        val uri: Uri? = null,
        val errorMessage: String? = null,
        val mimeType: String? = null,
        val confidence: CsvConfidence = CsvConfidence.NONE,
        val textContent: String? = null
    )
}