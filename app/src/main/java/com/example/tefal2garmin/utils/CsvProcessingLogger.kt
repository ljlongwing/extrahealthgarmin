package com.example.tefal2garmin.utils

import android.util.Log
import java.util.*

class CsvProcessingLogger {
    
    companion object {
        private const val TAG = "CsvProcessingLogger"
        
        fun logProcessingStart(fileName: String, totalRecords: Int) {
            Log.i(TAG, "=== Starting CSV Processing ===")
            Log.i(TAG, "File: $fileName")
            Log.i(TAG, "Total records to process: $totalRecords")
        }
        
        fun logProcessingEnd(validationReport: ValidationReport) {
            Log.i(TAG, "=== CSV Processing Complete ===")
            validationReport.logSummary()
        }
        
        fun logRecordValidation(
            lineNumber: Int,
            originalRecord: Map<String, String>,
            validationResult: CsvValidator.RecordValidationResult,
            processedRecord: Map<String, String?>?
        ) {
            if (validationResult.isValid) {
                Log.d(TAG, "Line $lineNumber: Record validated successfully")
                return
            }
            
            Log.w(TAG, "Line $lineNumber: Record validation failed")
            Log.w(TAG, "  Original: $originalRecord")
            
            if (validationResult.missingFields.isNotEmpty()) {
                Log.w(TAG, "  Missing fields: ${validationResult.missingFields.joinToString(", ")}")
            }
            
            if (validationResult.invalidFields.isNotEmpty()) {
                Log.w(TAG, "  Invalid fields: ${validationResult.invalidFields.joinToString(", ")}")
            }
            
            if (validationResult.modifiedValues.isNotEmpty()) {
                Log.w(TAG, "  Applied modifications:")
                validationResult.modifiedValues.forEach { (field, newValue) ->
                    val oldValue = originalRecord[field] ?: "<missing>"
                    Log.w(TAG, "    $field: '$oldValue' -> '$newValue'")
                }
            }
            
            processedRecord?.let { record ->
                Log.d(TAG, "  Processed record: $record")
            }
        }
        
        fun logProcessingError(lineNumber: Int, error: String, exception: Throwable? = null) {
            Log.e(TAG, "Line $lineNumber: Processing error - $error", exception)
        }
        
        fun generateSummaryReport(validationReport: ValidationReport): String {
            val report = StringBuilder()
            report.appendLine("=== CSV Processing Summary Report ===")
            report.appendLine("Generated: ${Date()}")
            report.appendLine()
            
            report.appendLine("Overview:")
            report.appendLine("  Total records processed: ${validationReport.totalRecords}")
            report.appendLine("  Valid records: ${validationReport.validRecords}")
            report.appendLine("  Records with missing fields: ${validationReport.recordsWithMissingFields}")
            report.appendLine("  Records with invalid fields: ${validationReport.recordsWithInvalidFields}")
            report.appendLine("  Records modified: ${validationReport.recordsModified}")
            report.appendLine()
            
            if (validationReport.fieldModificationSummary.isNotEmpty()) {
                report.appendLine("Field Modifications Summary:")
                validationReport.fieldModificationSummary.forEach { (field, count) ->
                    report.appendLine("  $field: $count modifications")
                }
                report.appendLine()
            }
            
            if (validationReport.details.isNotEmpty()) {
                report.appendLine("Detailed Issues:")
                validationReport.details.forEachIndexed { index, detail ->
                    report.appendLine("  ${index + 1}. $detail")
                }
                report.appendLine()
            }
            
            val successRate = if (validationReport.totalRecords > 0) {
                (validationReport.validRecords.toFloat() / validationReport.totalRecords * 100).toInt()
            } else {
                0
            }
            
            report.appendLine("Success Rate: $successRate%")
            report.appendLine("=== End Report ===")
            
            return report.toString()
        }
        
        fun saveReportToFile(validationReport: ValidationReport, fileName: String = "csv_processing_report.txt") {
            try {
                val reportContent = generateSummaryReport(validationReport)
                // In a real Android app, you would save to external storage
                // For now, just log the content
                Log.i(TAG, "Report content:\n$reportContent")
                Log.i(TAG, "Report would be saved to: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving report to file", e)
            }
        }
    }
}