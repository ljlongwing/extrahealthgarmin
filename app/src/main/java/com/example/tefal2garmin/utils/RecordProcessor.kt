package com.example.tefal2garmin.utils

import android.util.Log
import java.util.*

class RecordProcessor {
    
    companion object {
        private const val TAG = "RecordProcessor"
        
        fun processRecord(
            record: Map<String, String>,
            validator: CsvValidator
        ): ProcessingResult {
            // Validate record
            val validationResult = validator.validateRecord(record)
            
            // Apply modifications if needed
            val processedRecord = if (validationResult.modifiedValues.isNotEmpty()) {
                record.toMutableMap().apply {
                    putAll(validationResult.modifiedValues)
                }
            } else {
                record
            }
            
            // Convert to Garmin format
            val garminFormat = try {
                // Extract values using primary format field names (from Goodvibes CSV)
                val rawDate = processedRecord["Время измерения"] ?: ""
                val normalizedDate = CsvFieldMapper.normalizeDate(rawDate)
                val weight = processedRecord["Вес(кг)"]
                val bmi = processedRecord["ИМТ"]
                val fat = processedRecord["Процент жира(%)"]

                CsvFieldMapper.mapToGarminFormat(weight, bmi, fat, normalizedDate)
            } catch (e: Exception) {
                return ProcessingResult(
                    success = false,
                    record = emptyMap(),
                    validationResult = validationResult,
                    error = "Conversion error: ${e.message}"
                )
            }
            
            return ProcessingResult(
                success = true,
                record = garminFormat,
                validationResult = validationResult,
                error = null
            )
        }
    }
    
    data class ProcessingResult(
        val success: Boolean,
        val record: Map<String, String?>,
        val validationResult: CsvValidator.RecordValidationResult,
        val error: String?
    )
}