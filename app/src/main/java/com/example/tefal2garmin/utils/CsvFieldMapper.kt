package com.example.tefal2garmin.utils

import android.util.Log

class CsvFieldMapper {
    
    companion object {
        private const val TAG = "CsvFieldMapper"
        
        // Expected Tefal column headers
        private val TEFAL_HEADERS = listOf(
            "Дата", "Время", "Вес (кг)", "ИМТ", "Жир (% тела)", "Воды (%)", "Скелет мышц (%)", 
            "Другое", "Физическая активность (уровень)", "Примечания"
        )
        
        // Target Garmin schema field names
        private val GARMIN_FIELDS = listOf(
            "Date", "Weight", "BMI", "Fat"
        )
        
        // Russian month abbreviation mapping
        private val RUSSIAN_MONTHS = mapOf(
            "янв" to "01", "фев" to "02", "мар" to "03", "апр" to "04", "мая" to "05", "июн" to "06",
            "июл" to "07", "авг" to "08", "сен" to "09", "окт" to "10", "ноя" to "11", "дек" to "12"
        )

        private fun isValidDate(day: Int, month: Int, year: Int): Boolean {
            if (month < 1 || month > 12) return false
            if (day < 1) return false

            val daysInMonth = when (month) {
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }

            return day <= daysInMonth
        }
        
        private fun normalizeWeight(weightValue: String): String {
            return weightValue.replace(",", ".").trim()
        }
        
        private fun normalizeBmi(bmiValue: String): String {
            return bmiValue.replace(",", ".").trim()
        }
        
        private fun normalizeFat(fatValue: String): String {
            return fatValue.replace(",", ".").trim()
        }
        
        fun normalizeDate(dateValue: String): String {
            val cleanDate = dateValue.trim()
            
            // Handle various Russian date formats and convert to YYYY-MM-DD
            return when {
                cleanDate.lowercase().matches(Regex("""\d{1,2}\s*(янв|фев|мар|апр|мая|июн|июл|авг|сен|окт|ноя|дек)\s*\d{4}""")) -> {
                    // Format: "DD MMMM YYYY" (e.g., "24 января 2024")
                    val parts = cleanDate.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (parts.size >= 3) {
                        val day = parts[0].padStart(2, '0')
                        val month = parts[1].lowercase()
                        val monthNumber = RUSSIAN_MONTHS[month]
                        val year = parts[2]

                        if (monthNumber != null) {
                            val dayInt = day.toIntOrNull() ?: 1
                            val monthInt = monthNumber.toIntOrNull() ?: 1
                            val yearInt = year.toIntOrNull() ?: 2024

                            // Validate date (basic check for valid day/month combinations)
                            if (isValidDate(dayInt, monthInt, yearInt)) {
                                String.format("%04d-%02d-%02d", yearInt, monthInt, dayInt)
                            } else {
                                cleanDate
                            }
                        } else {
                            cleanDate
                        }
                    } else {
                        cleanDate
                    }
                }
                
                cleanDate.matches(Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")) -> {
                    // Format: "D.M.YYYY" or "DD.MM.YYYY" (e.g., "1.1.2024" or "15.01.2024")
                    val parts = cleanDate.split(".")
                    if (parts.size >= 3) {
                        val dayInt = parts[0].toIntOrNull() ?: 1
                        val monthInt = parts[1].toIntOrNull() ?: 1
                        val yearInt = parts[2].toIntOrNull() ?: 2024

                        // Validate date
                        if (isValidDate(dayInt, monthInt, yearInt)) {
                            String.format("%04d-%02d-%02d", yearInt, monthInt, dayInt)
                        } else {
                            cleanDate
                        }
                    } else {
                        cleanDate
                    }
                }
                
                else -> {
                    cleanDate
                }
            }
        }
        
        fun createFieldMapping(csvHeaders: List<String>): Map<String, String> {
            val mapping = mutableMapOf<String, String>()
            val normalizedHeaders = csvHeaders.map { header ->
                header.trim().lowercase().replace(" ", "").replace("(", "").replace(")", "")
            }
            
            // Map Tefal headers to Garmin fields
            GARMIN_FIELDS.forEach { garminField ->
                val tefalField = TEFAL_HEADERS.find { tefalHeader ->
                    normalizedHeaders.contains(tefalHeader.replace(" ", "").replace("(", "").replace(")", ""))
                }
                
                if (tefalField != null) {
                    mapping[garminField] = tefalField ?: "Unknown"
                }
            }
            
            // Add unmapped Tefal fields as "Other" (for logging purposes)
            normalizedHeaders.forEach { header ->
                if (!mapping.containsKey(header)) {
                    mapping[header] = "Other"
                }
            }
            
            Log.d(TAG, "Created field mapping: $mapping")
            return mapping
        }
        
        fun mapToGarminFormat(
            weightKg: String?,
            bmi: String?,
            fatPercentage: String?,
            date: String
        ): Map<String, String?> {
            val result = mutableMapOf<String, String?>()
            
            // Standard fields
            result["Date"] = date
            result["Weight"] = weightKg?.let { 
                // Convert from kg to lbs if needed (Garmin uses imperial)
                if (it.contains("кг")) {
                    try {
                        val kgValue = it.replace(",", ".").trim().toFloatOrNull()
                        kgValue?.let { kg ->
                            val lbs = kg * 2.20462f // Convert kg to lbs
                            String.format("%.2f", lbs)
                        } ?: it
                    } catch (e: Exception) {
                        it
                    }
                } else {
                    it
                }
            }
            
            // Add optional fields
            bmi?.let { result["BMI"] = it }
            fatPercentage?.let { result["Fat"] = it }
            
            return result
        }
    }
}