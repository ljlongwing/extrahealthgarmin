package com.example.tefal2garmin.utils

import android.util.Log
import java.util.*

data class ValidationReport(
    val totalRecords: Int = 0,
    val validRecords: Int = 0,
    val recordsWithMissingFields: Int = 0,
    val recordsWithInvalidFields: Int = 0,
    val recordsModified: Int = 0,
    val fieldModificationSummary: Map<String, Int> = emptyMap(),
    val details: List<String> = emptyList()
) {
    companion object {
        private const val TAG = "ValidationReport"
    }
    
    fun addRecord(result: CsvValidator.RecordValidationResult): ValidationReport {
        val newTotal = totalRecords + 1
        val newValid = if (result.isValid) validRecords + 1 else validRecords
        val newMissingCount = if (result.missingFields.isNotEmpty()) recordsWithMissingFields + 1 else recordsWithMissingFields
        val newInvalidCount = if (result.invalidFields.isNotEmpty()) recordsWithInvalidFields + 1 else recordsWithInvalidFields
        val newModifiedCount = if (result.modifiedValues.isNotEmpty()) recordsModified + 1 else recordsModified
        
        val newFieldModificationSummary = fieldModificationSummary.toMutableMap()
        result.modifiedValues.keys.forEach { field ->
            newFieldModificationSummary[field] = (newFieldModificationSummary[field] ?: 0) + 1
        }
        
        val newDetails = details.toMutableList()
        if (!result.isValid) {
            newDetails.add(result.message)
            if (result.modifiedValues.isNotEmpty()) {
                newDetails.add("Modified fields: ${result.modifiedValues.entries.joinToString { "${it.key}=${it.value}" }}")
            }
        }
        
        return copy(
            totalRecords = newTotal,
            validRecords = newValid,
            recordsWithMissingFields = newMissingCount,
            recordsWithInvalidFields = newInvalidCount,
            recordsModified = newModifiedCount,
            fieldModificationSummary = newFieldModificationSummary,
            details = newDetails
        )
    }
    
    fun logSummary() {
        Log.i(TAG, "=== Validation Report ===")
        Log.i(TAG, "Total records: $totalRecords")
        Log.i(TAG, "Valid records: $validRecords")
        Log.i(TAG, "Records with missing fields: $recordsWithMissingFields")
        Log.i(TAG, "Records with invalid fields: $recordsWithInvalidFields")
        Log.i(TAG, "Records modified: $recordsModified")
        
        if (fieldModificationSummary.isNotEmpty()) {
            Log.i(TAG, "Field modifications summary:")
            fieldModificationSummary.forEach { (field, count) ->
                Log.i(TAG, "  $field: $count modifications")
            }
        }
        
        if (details.isNotEmpty()) {
            Log.d(TAG, "Detailed issues:")
            details.forEach { detail ->
                Log.d(TAG, "  $detail")
            }
        }
        Log.i(TAG, "=== End Report ===")
    }
    
    fun hasIssues(): Boolean {
        return totalRecords > validRecords || recordsModified > 0
    }
}