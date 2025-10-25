package com.example.tefal2garmin.utils

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class CsvValidator {
    
    companion object {
        private const val TAG = "CsvValidator"
        
        // Expected Russian column headers for Tefal Goodvibes export
        private val EXPECTED_HEADERS = listOf(
            "Дата", "Время", "Вес (кг)", "ИМТ", "Жир (% тела)", "Воды (%)", "Скелет мышц (%)", 
            "Другое", "Физическая активность (уровень)", "Примечания"
        )
        
        // UTF-8 BOM signature
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
    
    fun validateEncoding(inputStream: InputStream): EncodingValidationResult {
        return try {
            val firstBytes = ByteArray(3)
            val bytesRead = inputStream.read(firstBytes)
            
            if (bytesRead == 3 && firstBytes.contentEquals(UTF8_BOM)) {
                Log.w(TAG, "File contains UTF-8 BOM")
                EncodingValidationResult(false, "File contains UTF-8 BOM (Byte Order Mark). Please save file without BOM.")
            } else {
                // Try to read a small portion as UTF-8 to verify encoding
                val available = inputStream.available()
                val bufferSize = minOf(available, 1024)
                val buffer = ByteArray(bufferSize)
                inputStream.mark(bufferSize + 3)
                inputStream.read(buffer)
                inputStream.reset()
                
                val testString = String(buffer, StandardCharsets.UTF_8)
                
                // Check for common encoding issues
                if (testString.contains("�") || testString.contains("?") && testString.contains("�")) {
                    Log.w(TAG, "File appears to have encoding issues")
                    EncodingValidationResult(false, "File appears to have encoding issues. Please ensure file is saved as UTF-8.")
                } else {
                    Log.d(TAG, "File encoding validation passed")
                    EncodingValidationResult(true, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating file encoding", e)
            EncodingValidationResult(false, "Error reading file: ${e.message}")
        }
    }
    
    fun validateDelimiter(lines: List<String>): DelimiterValidationResult {
        if (lines.isEmpty()) {
            return DelimiterValidationResult(false, "File is empty")
        }
        
        val headerLine = lines[0]
        val commaCount = headerLine.count { it == ',' }
        val semicolonCount = headerLine.count { it == ';' }
        val tabCount = headerLine.count { it == '\t' }
        
        return when {
            commaCount > semicolonCount && commaCount > tabCount -> {
                Log.d(TAG, "File uses comma delimiter")
                DelimiterValidationResult(true, null)
            }
            semicolonCount > commaCount && semicolonCount > tabCount -> {
                Log.w(TAG, "File uses semicolon delimiter")
                DelimiterValidationResult(false, "File uses semicolon (;) as delimiter. Please use comma (,) delimiter.")
            }
            tabCount > commaCount && tabCount > semicolonCount -> {
                Log.w(TAG, "File uses tab delimiter")
                DelimiterValidationResult(false, "File uses tab as delimiter. Please use comma (,) delimiter.")
            }
            else -> {
                Log.w(TAG, "File delimiter is unclear or inconsistent")
                DelimiterValidationResult(false, "File uses unclear or inconsistent delimiters. Please use comma (,) delimiter.")
            }
        }
    }
    
    fun validateHeaders(headerLine: String): HeaderValidationResult {
        val headers = parseCsvLine(headerLine)
        val missingHeaders = mutableListOf<String>()
        val extraHeaders = mutableListOf<String>()
        val misspelledHeaders = mutableListOf<String>()
        
        // Check for missing headers
        EXPECTED_HEADERS.forEach { expectedHeader ->
            if (!headers.contains(expectedHeader)) {
                missingHeaders.add(expectedHeader)
            }
        }
        
        // Check for extra headers
        headers.forEach { header ->
            if (!EXPECTED_HEADERS.contains(header)) {
                extraHeaders.add(header)
            }
        }
        
        // Check for common misspellings
        val commonMisspellings = mapOf(
            "Дта" to "Дата",
            "Время" to "Время",
            "Вес" to "Вес (кг)",
            "Жир" to "Жир (% тела)",
            "Воды" to "Воды (%)",
            "Скелет" to "Скелет мышц (%)"
        )
        
        commonMisspellings.forEach { (misspelled, _) ->
            if (headers.contains(misspelled)) {
                misspelledHeaders.add(misspelled)
            }
        }
        
        val hasIssues = missingHeaders.isNotEmpty() || extraHeaders.isNotEmpty() || misspelledHeaders.isNotEmpty()
        
        return if (hasIssues) {
            val errorMessage = StringBuilder()
            if (missingHeaders.isNotEmpty()) {
                errorMessage.append("Missing columns: ${missingHeaders.joinToString(", ")}")
            }
            if (extraHeaders.isNotEmpty()) {
                if (errorMessage.isNotEmpty()) errorMessage.append("; ")
                errorMessage.append("Extra columns: ${extraHeaders.joinToString(", ")}")
            }
            if (misspelledHeaders.isNotEmpty()) {
                if (errorMessage.isNotEmpty()) errorMessage.append("; ")
                errorMessage.append("Possibly misspelled: ${misspelledHeaders.joinToString(", ")}")
            }
            
            Log.w(TAG, "Header validation failed: $errorMessage")
            HeaderValidationResult(false, errorMessage.toString())
        } else {
            Log.d(TAG, "Header validation passed")
            HeaderValidationResult(true, null)
        }
    }
    
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when {
                !inQuotes && line[i] == '"' -> {
                    inQuotes = true
                }
                inQuotes && line[i] == '"' -> {
                    // Check for escaped quote
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        currentField.append('"')
                        i++ // Skip escaped quote
                    } else {
                        inQuotes = false
                    }
                }
                !inQuotes && line[i] == ',' -> {
                    fields.add(currentField.toString().trim())
                    currentField = StringBuilder()
                }
                else -> {
                    currentField.append(line[i])
                }
            }
            i++
        }
        
        // Add last field
        fields.add(currentField.toString().trim())
        
        return fields
    }
    
    fun validateRecord(record: Map<String, String>): RecordValidationResult {
        val missingFields = mutableListOf<String>()
        val invalidFields = mutableListOf<String>()
        val modifiedValues = mutableMapOf<String, String>()
        
        // Check required fields and their formats
        val requiredFields = mapOf(
            "Дата" to "date",
            "Вес (кг)" to "weight"
        )
        
        requiredFields.forEach { (russianHeader, fieldName) ->
            val value = record[russianHeader]
            
            when {
                value.isNullOrBlank() -> {
                    missingFields.add(russianHeader)
                    modifiedValues[russianHeader] = getDefaultForField(fieldName)
                }
                fieldName == "date" && !isValidDate(value) -> {
                    invalidFields.add("$russianHeader (invalid date format)")
                    modifiedValues[russianHeader] = getDefaultForField(fieldName)
                }
                fieldName == "weight" && !isValidWeight(value) -> {
                    invalidFields.add("$russianHeader (invalid weight value)")
                    modifiedValues[russianHeader] = getDefaultForField(fieldName)
                }
            }
        }
        
        // Check optional fields (BMI, Fat)
        val optionalFields = mapOf(
            "ИМТ" to "bmi",
            "Жир (% тела)" to "fat"
        )
        
        optionalFields.forEach { (russianHeader, fieldName) ->
            val value = record[russianHeader]
            
            if (!value.isNullOrBlank()) {
                when {
                    fieldName == "bmi" && !isValidBMI(value) -> {
                        invalidFields.add("$russianHeader (invalid BMI value)")
                        modifiedValues[russianHeader] = getDefaultForField(fieldName)
                    }
                    fieldName == "fat" && !isValidFat(value) -> {
                        invalidFields.add("$russianHeader (invalid fat percentage)")
                        modifiedValues[russianHeader] = getDefaultForField(fieldName)
                    }
                }
            }
        }
        
        val hasIssues = missingFields.isNotEmpty() || invalidFields.isNotEmpty()
        val isValid = !hasIssues
        
        return RecordValidationResult(
            isValid = isValid,
            missingFields = missingFields,
            invalidFields = invalidFields,
            modifiedValues = modifiedValues,
            message = if (hasIssues) {
                val issues = mutableListOf<String>()
                if (missingFields.isNotEmpty()) issues.add("Missing: ${missingFields.joinToString(", ")}")
                if (invalidFields.isNotEmpty()) issues.add("Invalid: ${invalidFields.joinToString(", ")}")
                "Record has issues: ${issues.joinToString("; ")}"
            } else {
                "Record is valid"
            }
        )
    }
    
    private fun isValidDate(dateString: String): Boolean {
        val formats = listOf(
            "yyyy-MM-dd",
            "dd.MM.yyyy",
            "dd/MM/yyyy",
            "MM/dd/yyyy"
        )
        
        return formats.any { format ->
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                sdf.isLenient = false
                val parsedDate = sdf.parse(dateString)
                parsedDate != null
            } catch (e: Exception) {
                false
            }
        }
    }
    
    private fun isValidWeight(weightString: String): Boolean {
        return try {
            val normalized = weightString.replace(",", ".").trim()
            val weight = normalized.toFloatOrNull()
            weight != null && weight > 0 && weight < 1000
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidBMI(bmiString: String): Boolean {
        return try {
            val bmi = bmiString.replace(",", ".").trim().toFloatOrNull()
            bmi != null && bmi > 0 && bmi < 100
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidFat(fatString: String): Boolean {
        return try {
            val fat = fatString.replace(",", ".").trim().toFloatOrNull()
            fat != null && fat >= 0 && fat <= 100
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getDefaultForField(fieldName: String): String {
        return when (fieldName) {
            "date" -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                sdf.format(Date())
            }
            "weight", "bmi", "fat" -> "0.0"
            else -> ""
        }
    }
    
    data class RecordValidationResult(
        val isValid: Boolean,
        val missingFields: List<String>,
        val invalidFields: List<String>,
        val modifiedValues: Map<String, String>,
        val message: String
    )
    
    data class EncodingValidationResult(
        val isValid: Boolean,
        val errorMessage: String?
    )
    
    data class DelimiterValidationResult(
        val isValid: Boolean,
        val errorMessage: String?
    )
    
    data class HeaderValidationResult(
        val isValid: Boolean,
        val errorMessage: String?
    )
}