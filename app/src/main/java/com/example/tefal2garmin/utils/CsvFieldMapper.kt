package com.example.tefal2garmin.utils

class CsvFieldMapper {

    companion object {
        // Russian month abbreviation mapping
        private val RUSSIAN_MONTHS = mapOf(
            "янв" to "01", "фев" to "02", "мар" to "03", "апр" to "04", "мая" to "05", "июн" to "06",
            "июл" to "07", "авг" to "08", "сен" to "09", "окт" to "10", "ноя" to "11", "дек" to "12"
        )

        fun isValidDate(day: Int, month: Int, year: Int): Boolean {
            if (month < 1 || month > 12) return false
            if (day < 1) return false

            val daysInMonth = when (month) {
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }

            return day <= daysInMonth
        }

        /**
         * Normalizes a weight value: European decimal comma to dot, surrounding whitespace trimmed.
         */
        fun normalizeWeight(weightValue: String): String {
            return weightValue.replace(",", ".").trim()
        }

        /**
         * Normalizes a BMI value: European decimal comma to dot, surrounding whitespace trimmed.
         */
        fun normalizeBmi(bmiValue: String): String {
            return bmiValue.replace(",", ".").trim()
        }

        /**
         * Normalizes a fat percentage value: European decimal comma to dot, surrounding whitespace trimmed.
         */
        fun normalizeFat(fatValue: String): String {
            return fatValue.replace(",", ".").trim()
        }

        fun normalizeDate(dateValue: String): String {
            val cleanDate = dateValue.trim()

            // Handle various Russian date formats and convert to YYYY-MM-DD
            return when {
                // Format: "окт. 25, 2025 09:51:52" (Tefal Goodvibes primary format).
                // The month word may be a longer CLDR short form ("февр.", "сент.",
                // "нояб.") or dot-less genitive ("мая"), so match the full month word
                // and fall back to its 3-letter prefix for the lookup below.
                cleanDate.lowercase().matches(Regex("""(янв|фев|мар|апр|мая|июн|июл|авг|сен|окт|ноя|дек)[а-яё]*\.?\s*\d{1,2},\s*\d{4}.*""")) -> {
                    val parts = cleanDate.split("\\s+|,\\s*".toRegex()).filter { it.isNotEmpty() }
                    if (parts.size >= 3) {
                        // All lookup keys are 3 letters; take(3) splits CLDR forms
                        // like "февр." down to the "фев" prefix ("мая" is already 3)
                        val monthAbbrev = parts[0].replace(".", "").lowercase().take(3)
                        val day = parts[1].padStart(2, '0')
                        val year = parts[2]

                        val monthNumber = RUSSIAN_MONTHS[monthAbbrev]

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

        fun mapToGarminFormat(
            weightKg: String?,
            bmi: String?,
            fatPercentage: String?,
            date: String
        ): Map<String, String?> {
            val result = mutableMapOf<String, String?>()

            // Standard fields (weight arrives pre-normalized from RecordProcessor)
            result["Date"] = date
            result["Weight"] = weightKg

            // Add optional fields
            bmi?.let { result["BMI"] = it }
            fatPercentage?.let { result["Fat"] = it }

            return result
        }
    }
}
