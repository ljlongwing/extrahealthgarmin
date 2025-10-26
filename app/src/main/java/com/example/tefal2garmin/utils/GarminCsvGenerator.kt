package com.example.tefal2garmin.utils

import java.io.*
import java.nio.charset.StandardCharsets

class GarminCsvGenerator {
    
    companion object {
        /**
         * Generates a Garmin-compatible CSV file from processed records
         */
        fun generateGarminCsv(
            records: List<Map<String, String?>>,
            outputFile: File,
            onProgress: (Int, Int) -> Unit = { _, _ -> }
        ): GenerationResult {
            return try {
                // Validate all records before writing
                val validationResults = records.map { GarminCsvSchema.validateRecord(it) }
                val invalidRecords = validationResults.filter { !it.isValid }
                
                if (invalidRecords.isNotEmpty()) {
                    invalidRecords.forEachIndexed { index, result ->
                        // Log.w(TAG, "Invalid record ${index + 1}: ${result.issues.joinToString(", ")}")
                    }
                    return GenerationResult(
                        success = false,
                        recordsProcessed = records.size,
                        recordsGenerated = 0,
                        validationIssues = invalidRecords.flatMap { it.issues },
                        error = "Validation failed for ${invalidRecords.size} records"
                    )
                }
                
                // Write file
                BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), StandardCharsets.UTF_8)).use { writer ->
                    // Write headers
                    writer.write(GarminCsvSchema.generateHeader())
                    writer.newLine()
                    
                    // Write data records
                    var generatedCount = 0
                    records.forEachIndexed { index, record ->
                        if (index > 0) {
                            writer.newLine()
                        }
                        
                        val csvLine = GarminCsvSchema.formatRecord(record)
                        writer.write(csvLine)
                        generatedCount++
                        
                        // Report progress
                        onProgress(index + 1, records.size)
                    }
                    
                    GenerationResult(
                        success = true,
                        recordsProcessed = records.size,
                        recordsGenerated = generatedCount,
                        validationIssues = emptyList(),
                        error = null
                    )
                }
                
            } catch (e: Exception) {
                GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = emptyList(),
                    error = "File generation failed: ${e.message}"
                )
            }
        }
        
        /**
         * Validates all records before generation and provides detailed feedback
         */
        fun validateAllRecords(records: List<Map<String, String?>>): ValidationResult {
            val allIssues = mutableListOf<String>()
            val validRecords = mutableListOf<Map<String, String?>>()
            val invalidRecords = mutableListOf<Map<String, String?>>()
            
            records.forEachIndexed { index, record ->
                val validation = GarminCsvSchema.validateRecord(record)
                
                if (validation.isValid) {
                    validRecords.add(record)
                } else {
                    invalidRecords.add(record)
                    allIssues.addAll(validation.issues.map { "Record ${index + 1}: $it" })
                }
            }
            
            return ValidationResult(
                totalRecords = records.size,
                validRecords = validRecords.size,
                invalidRecords = invalidRecords.size,
                allIssues = allIssues,
                isValid = allIssues.isEmpty()
            )
        }
        
        /**
         * Creates a sample Garmin CSV file for testing
         */
        fun createSampleFile(outputFile: File) {
            val sampleRecords = listOf(
                mapOf(
                    "Date" to "2024-01-15",
                    "Weight" to "155.00",
                    "BMI" to "23.50",
                    "Fat" to "18.20"
                ),
                mapOf(
                    "Date" to "2024-01-16",
                    "Weight" to "154.50",
                    "BMI" to "23.40",
                    "Fat" to "18.10"
                ),
                mapOf(
                    "Date" to "2024-01-17",
                    "Weight" to "154.00",
                    "BMI" to "23.30",
                    "Fat" to "18.00"
                )
            )
            
            val result = generateGarminCsv(sampleRecords, outputFile)
            if (result.success) {
                // Log.i(TAG, "Sample Garmin CSV file created successfully: ${outputFile.absolutePath}")
            } else {
                // Log.e(TAG, "Failed to create sample file: ${result.error}")
            }
        }
    }
    
    data class GenerationResult(
        val success: Boolean,
        val recordsProcessed: Int,
        val recordsGenerated: Int,
        val validationIssues: List<String>,
        val error: String?
    )
    
    data class ValidationResult(
        val totalRecords: Int,
        val validRecords: Int,
        val invalidRecords: Int,
        val allIssues: List<String>,
        val isValid: Boolean
    )
}