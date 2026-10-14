package com.example.tefal2garmin.utils

class RecordProcessor {

    companion object {
        fun processRecord(
            record: Map<String, String>,
            validator: CsvValidator
        ): ProcessingResult {
            // Validate record
            val validationResult = validator.validateRecord(record)

            // Convert to Garmin format, reading the ORIGINAL record values.
            // Defaults from validationResult.modifiedValues are reported to the caller but
            // deliberately not substituted into the output; normalization happens here instead.
            val garminFormat = try {
                // Extract values using primary format field names (from Goodvibes CSV).
                // Blank optional values are dropped (null) so their keys stay absent
                // from the mapped record instead of flowing through as empty strings.
                // Weight stays a required pass-through: a blank weight row is a
                // genuinely unusable row and is skipped later by the schema.
                val rawDate = record["Время измерения"] ?: ""
                val normalizedDate = CsvFieldMapper.normalizeDate(rawDate)
                val weight = record["Вес(кг)"]?.let { CsvFieldMapper.normalizeWeight(it) }
                val bmi = record["ИМТ"]?.takeIf { it.isNotBlank() }?.let { CsvFieldMapper.normalizeBmi(it) }
                val fat = record["Процент жира(%)"]?.takeIf { it.isNotBlank() }?.let { CsvFieldMapper.normalizeFat(it) }

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
