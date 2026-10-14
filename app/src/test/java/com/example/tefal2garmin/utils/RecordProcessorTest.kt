package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class RecordProcessorTest {

    private val validator = CsvValidator()

    @Test
    fun testProcessRecord_ValidRecord() {
        val record = mapOf(
            "Время измерения" to "2024-01-15",
            "Вес(кг)" to "70.5",
            "ИМТ" to "23.5",
            "Процент жира(%)" to "18.2"
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed", result.success)
        assertNull("Should have no error", result.error)
        assertTrue("Validation should pass", result.validationResult.isValid)
        assertNotNull("Should produce Garmin format record", result.record)

        val garminRecord = result.record
        assertEquals("Should have Date field", "2024-01-15", garminRecord["Date"])
        assertEquals("Should have Weight field", "70.5", garminRecord["Weight"])
        assertEquals("Should have BMI field", "23.5", garminRecord["BMI"])
        assertEquals("Should have Fat field", "18.2", garminRecord["Fat"])
    }

    @Test
    fun testProcessRecord_RecordWithMissingFields() {
        // The record carries neither primary ("Время измерения"/"Вес(кг)") nor
        // alternative ("Дата"/"Вес (кг)") keys, so validation falls back to the
        // alternative contract and reports both of its required fields as missing.
        val record = mapOf(
            "ИМТ" to "23.5",
            "Процент жира(%)" to "18.2"
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed with modifications", result.success)
        assertNull("Should have no error", result.error)
        assertFalse("Validation should fail but processing should succeed", result.validationResult.isValid)
        assertEquals(
            "Defaults should be reported under the alternative keys",
            setOf("Дата", "Вес (кг)"),
            result.validationResult.modifiedValues.keys
        )

        val garminRecord = result.record
        // Conversion reads the ORIGINAL record values: no date/weight keys means
        // empty date and absent weight — defaults are reported, not substituted
        assertEquals("Date should be empty when the input had none", "", garminRecord["Date"])
        assertNull("Weight should be absent when the input had none", garminRecord["Weight"])
        assertEquals("Should preserve BMI", "23.5", garminRecord["BMI"])
        assertEquals("Should preserve Fat", "18.2", garminRecord["Fat"])
    }

    @Test
    fun testProcessRecord_RecordWithInvalidFields() {
        val record = mapOf(
            "Время измерения" to "invalid-date",
            "Вес(кг)" to "not-a-number"
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed with modifications", result.success)
        assertNull("Should have no error", result.error)
        assertFalse("Validation should fail but processing should succeed", result.validationResult.isValid)

        // Conversion passes the original (unparseable) values through: normalization
        // cannot rewrite them, and defaults are reported via modifiedValues only
        val garminRecord = result.record
        assertEquals("Unparseable date should pass through unchanged", "invalid-date", garminRecord["Date"])
        assertEquals("Unparseable weight should pass through unchanged", "not-a-number", garminRecord["Weight"])
        assertEquals("Default weight should be reported", "0.0", result.validationResult.modifiedValues["Вес(кг)"])
        assertTrue(
            "Default (today) date should be reported",
            result.validationResult.modifiedValues["Время измерения"]?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) == true
        )
    }

    @Test
    fun testProcessRecord_RecordWithValidations() {
        val record = mapOf(
            "Время измерения" to "15.01.2024",
            "Вес(кг)" to "75,3",
            "ИМТ" to "24.1",
            "Процент жира(%)" to "20.1"
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed", result.success)
        assertTrue("Validation should pass for valid formats", result.validationResult.isValid)

        val garminRecord = result.record
        assertEquals("Should normalize date format", "2024-01-15", garminRecord["Date"])
        assertEquals("Should normalize comma decimal in weight", "75.3", garminRecord["Weight"])
        assertEquals("Should preserve BMI", "24.1", garminRecord["BMI"])
        assertEquals("Should preserve Fat", "20.1", garminRecord["Fat"])
    }

    @Test
    fun testProcessRecord_EmptyOptionalFields() {
        val record = mapOf(
            "Время измерения" to "2024-01-15",
            "Вес(кг)" to "70.5",
            "ИМТ" to "23.5",
            "Процент жира(%)" to ""
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed with empty optional fields", result.success)
        assertTrue("Validation should pass with empty optional fields", result.validationResult.isValid)

        val garminRecord = result.record
        assertEquals("Should preserve date", "2024-01-15", garminRecord["Date"])
        assertEquals("Should have weight", "70.5", garminRecord["Weight"])
        assertEquals("Non-empty BMI should pass through", "23.5", garminRecord["BMI"])
        assertEquals("Empty Fat should stay empty", "", garminRecord["Fat"])
    }

    @Test
    fun testProcessRecord_GoodvibesRowWithCommaDecimalsAndRussianDate() {
        // Regression: a real European-decimal Goodvibes row must come out Garmin-valid.
        // Previously "79,65" flowed raw into GarminCsvSchema whose toDoubleOrNull()
        // rejected it, failing the whole CSV generation.
        val record = mapOf(
            "Время измерения" to "окт. 25, 2025 09:51:52",
            "Вес(кг)" to "79,65",
            "ИМТ" to "24,6",
            "Процент жира(%)" to "21,9"
        )

        val result = RecordProcessor.processRecord(record, validator)

        assertTrue("Processing should succeed", result.success)
        assertTrue("Validation should pass for a real Goodvibes row", result.validationResult.isValid)

        val garminRecord = result.record
        assertEquals("Russian date should be normalized to YYYY-MM-DD", "2025-10-25", garminRecord["Date"])
        assertEquals("Comma-decimal weight should be normalized", "79.65", garminRecord["Weight"])
        assertEquals("Comma-decimal BMI should be normalized", "24.6", garminRecord["BMI"])
        assertEquals("Comma-decimal fat should be normalized", "21.9", garminRecord["Fat"])
    }
}
