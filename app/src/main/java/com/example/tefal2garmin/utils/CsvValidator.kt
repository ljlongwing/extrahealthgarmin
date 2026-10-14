package com.example.tefal2garmin.utils

import android.util.Log
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class CsvValidator {
    
    companion object {
        private const val TAG = "CsvValidator"
        
        // Expected Russian column headers for Tefal Goodvibes export
        // Primary format (from Goodvibes-Dmitry file)
        private val EXPECTED_HEADERS_PRIMARY = listOf(
            "Время измерения", "Вес(кг)", "ИМТ", "Пульс(bpm)", "Процент жира(%)",
            "Масса тела без жира(кг)", "Подкожный жир(%)", "Висцеральный жир",
            "Вода в организме(%)", "Скелетные мышцы(%)", "Мышечная масса(кг)",
            "Костная масса(кг)", "Белок(%)", "Величина основного обмена(kcal)", "Метаболический возраст"
        )

        // Alternative format (older Goodvibes export)
        private val EXPECTED_HEADERS_ALTERNATIVE = listOf(
            "Дата", "Время", "Вес (кг)", "ИМТ", "Жир (% тела)", "Воды (%)", "Скелет мышц (%)",
            "Другое", "Физическая активность (уровень)", "Примечания"
        )

        // Minimum required headers for basic functionality
        private val MINIMUM_REQUIRED_HEADERS = listOf("Время измерения", "Вес(кг)", "ИМТ")
        
        // UTF-8 BOM signature
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        // Already-normalized ISO date (YYYY-MM-DD) — the target shape of CsvFieldMapper.normalizeDate
        private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
    
    fun validateEncoding(inputStream: InputStream): EncodingValidationResult {
        return try {
            // Read first few bytes to check for BOM and basic encoding validation
            val firstBytes = ByteArray(3)
            val bytesRead = inputStream.read(firstBytes)

            if (bytesRead == 3 && firstBytes.contentEquals(UTF8_BOM)) {
                Log.w(TAG, "File contains UTF-8 BOM")
                EncodingValidationResult(false, "File contains UTF-8 BOM (Byte Order Mark). Please save file without BOM.")
            } else {
                // Read a small portion for encoding validation
                // Since ContentResolver streams don't support mark/reset, we'll read additional bytes
                val additionalBytes = ByteArray(1024 - 3) // Already read 3 bytes
                val additionalRead = inputStream.read(additionalBytes)

                // Combine the bytes for validation
                val totalBytes = if (additionalRead > 0) {
                    firstBytes + additionalBytes.copyOfRange(0, additionalRead)
                } else {
                    firstBytes.copyOfRange(0, bytesRead)
                }

                val testString = String(totalBytes, StandardCharsets.UTF_8)

                // Check for common encoding issues
                if (testString.contains("�") || (testString.contains("?") && testString.contains("�"))) {
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

        // Determine which header format we're dealing with
        val isPrimaryFormat = EXPECTED_HEADERS_PRIMARY.any { headers.contains(it) }
        val isAlternativeFormat = EXPECTED_HEADERS_ALTERNATIVE.any { headers.contains(it) }

        val expectedHeaders = when {
            isPrimaryFormat -> EXPECTED_HEADERS_PRIMARY
            isAlternativeFormat -> EXPECTED_HEADERS_ALTERNATIVE
            else -> EXPECTED_HEADERS_PRIMARY // Default to primary format
        }

        // Check for minimum required headers
        val hasMinimumRequired = MINIMUM_REQUIRED_HEADERS.all { required ->
            headers.any { it.contains(required.replace("(", "").replace(")", "")) || it == required }
        }

        if (!hasMinimumRequired) {
            return HeaderValidationResult(
                false,
                "Missing required headers. File must contain at least: ${MINIMUM_REQUIRED_HEADERS.joinToString(", ")}"
            )
        }

        // Check for missing headers (only warn, don't fail)
        expectedHeaders.forEach { expectedHeader ->
            if (!headers.contains(expectedHeader)) {
                missingHeaders.add(expectedHeader)
            }
        }

        // Check for extra headers (only informational)
        headers.forEach { header ->
            if (!expectedHeaders.contains(header)) {
                extraHeaders.add(header)
            }
        }
        
        // Check for common misspellings based on format
        val commonMisspellings = if (isPrimaryFormat) {
            mapOf(
                "Время измерения" to "Время измерения",
                "Вес(кг)" to "Вес(кг)",
                "ИМТ" to "ИМТ",
                "Процент жира(%)" to "Процент жира(%)"
            )
        } else {
            mapOf(
                "Дта" to "Дата",
                "Время" to "Время",
                "Вес" to "Вес (кг)",
                "Жир" to "Жир (% тела)",
                "Воды" to "Воды (%)",
                "Скелет" to "Скелет мышц (%)"
            )
        }

        commonMisspellings.forEach { (misspelled, correct) ->
            if (headers.contains(misspelled) && misspelled != correct) {
                misspelledHeaders.add("$misspelled -> correct: $correct")
            }
        }

        // For primary format, we're more lenient - just require minimum headers
        val isValid = hasMinimumRequired
        val message = when {
            !isValid -> "Missing required headers: ${MINIMUM_REQUIRED_HEADERS.joinToString(", ")}"
            missingHeaders.isNotEmpty() -> "File validated successfully. Some expected headers missing: ${missingHeaders.take(3).joinToString(", ")}${if (missingHeaders.size > 3) "..." else ""}"
            extraHeaders.isNotEmpty() -> "File validated successfully. Extra headers found: ${extraHeaders.take(3).joinToString(", ")}${if (extraHeaders.size > 3) "..." else ""}"
            else -> "Headers validation passed"
        }

        if (isValid) {
            Log.d(TAG, "Header validation passed for format: ${if (isPrimaryFormat) "primary" else if (isAlternativeFormat) "alternative" else "unknown"}")
        } else {
            Log.w(TAG, "Header validation failed: $message")
        }

        return HeaderValidationResult(isValid, message)
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

        // Production Goodvibes rows are keyed by the primary headers ("Время измерения",
        // "Вес(кг)"), older exports by the alternative ones ("Дата", "Вес (кг)").
        // Detect the contract from the keys the record actually carries.
        val isPrimaryFormat = record.containsKey("Время измерения") || record.containsKey("Вес(кг)")

        // Check required fields and their formats
        val requiredFields = if (isPrimaryFormat) {
            mapOf(
                "Время измерения" to "date",
                "Вес(кг)" to "weight"
            )
        } else {
            mapOf(
                "Дата" to "date",
                "Вес (кг)" to "weight"
            )
        }

        requiredFields.forEach { (russianHeader, fieldName) ->
            val value = record[russianHeader]

            when {
                value.isNullOrBlank() -> {
                    missingFields.add(russianHeader)
                    modifiedValues[russianHeader] = getDefaultForField(fieldName)
                }
                fieldName == "date" && !isValidDate(value, isPrimaryFormat) -> {
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
        val optionalFields = if (isPrimaryFormat) {
            mapOf(
                "ИМТ" to "bmi",
                "Процент жира(%)" to "fat"
            )
        } else {
            mapOf(
                "ИМТ" to "bmi",
                "Жир (% тела)" to "fat"
            )
        }
        
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
    
    /**
     * Dispatches date validation per contract. The primary (Goodvibes) contract accepts any
     * date [CsvFieldMapper.normalizeDate] can rewrite to YYYY-MM-DD (including values that
     * are already in that shape); the alternative contract uses the fixed pattern list below.
     */
    private fun isValidDate(dateString: String, isPrimaryFormat: Boolean): Boolean {
        if (isPrimaryFormat) {
            // Fast path: already-normalized ISO date. The shape alone accepts
            // impossible dates like 2025-13-45, so range-check the components too.
            if (ISO_DATE_REGEX.matches(dateString)) {
                val parts = dateString.split("-")
                return CsvFieldMapper.isValidDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
            }
            return CsvFieldMapper.normalizeDate(dateString) != dateString
        }
        return isValidDate(dateString)
    }

    // Alternative (older export) contract: fixed set of supported date patterns
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