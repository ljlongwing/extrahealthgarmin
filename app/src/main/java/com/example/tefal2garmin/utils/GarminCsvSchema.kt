package com.example.tefal2garmin.utils

/**
 * Schema definition for Garmin CSV export format
 */
object GarminCsvSchema {
    
    // CSV column headers in Garmin format
    const val HEADER_DATE = "Date"
    const val HEADER_WEIGHT = "Weight"
    const val HEADER_BMI = "BMI"
    const val HEADER_FAT = "Fat"
    
    private val REQUIRED_HEADERS = listOf(HEADER_DATE, HEADER_WEIGHT, HEADER_BMI, HEADER_FAT)
    
    /**
     * Generates the CSV header line for Garmin format
     */
    fun generateHeader(): String {
        return REQUIRED_HEADERS.joinToString(",")
    }
    
    /**
     * Validates a record against Garmin CSV schema
     */
    fun validateRecord(record: Map<String, String?>): RecordValidation {
        val issues = mutableListOf<String>()
        
        // Check required fields
        REQUIRED_HEADERS.forEach { header ->
            if (record[header].isNullOrEmpty()) {
                issues.add("Missing required field: $header")
            }
        }
        
        // Validate date format (YYYY-MM-DD)
        record[HEADER_DATE]?.let { date ->
            if (!isValidDate(date)) {
                issues.add("Invalid date format: $date (expected YYYY-MM-DD)")
            }
        }
        
        // Validate numeric fields
        record[HEADER_WEIGHT]?.let { weight ->
            if (!isValidDecimal(weight)) {
                issues.add("Invalid weight format: $weight")
            }
        }
        
        record[HEADER_BMI]?.let { bmi ->
            if (!isValidDecimal(bmi)) {
                issues.add("Invalid BMI format: $bmi")
            }
        }
        
        record[HEADER_FAT]?.let { fat ->
            if (!isValidDecimal(fat)) {
                issues.add("Invalid fat percentage format: $fat")
            }
        }
        
        return RecordValidation(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }
    
    /**
     * Formats a record as a CSV line according to Garmin schema
     */
    fun formatRecord(record: Map<String, String?>): String {
        return listOf(
            record[HEADER_DATE] ?: "",
            record[HEADER_WEIGHT] ?: "",
            record[HEADER_BMI] ?: "",
            record[HEADER_FAT] ?: ""
        ).joinToString(",")
    }
    
    /**
     * Validates date format (YYYY-MM-DD)
     */
    private fun isValidDate(date: String): Boolean {
        return try {
            val parts = date.split("-")
            if (parts.size != 3) return false
            
            val year = parts[0].toIntOrNull()
            val month = parts[1].toIntOrNull()
            val day = parts[2].toIntOrNull()
            
            year != null && month != null && day != null &&
            year in 1900..2100 &&
            month in 1..12 &&
            day in 1..31
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validates decimal number format (uses '.' as decimal separator)
     */
    private fun isValidDecimal(value: String): Boolean {
        return try {
            value.toDoubleOrNull() != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Returns the expected column order for CSV processing
     */
    fun getExpectedColumns(): List<String> {
        return REQUIRED_HEADERS.toList()
    }
    
    /**
     * Checks if a column name is part of the Garmin schema
     */
    fun isGarminColumn(columnName: String): Boolean {
        return REQUIRED_HEADERS.contains(columnName)
    }
}

data class RecordValidation(
    val isValid: Boolean,
    val issues: List<String>
)