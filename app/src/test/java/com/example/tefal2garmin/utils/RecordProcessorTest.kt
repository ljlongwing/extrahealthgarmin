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
        assertNotNull("Should have Weight field", garminRecord["Weight"])
        assertEquals("Should have BMI field", "23.5", garminRecord["BMI"])
        assertEquals("Should have Fat field", "18.2", garminRecord["Fat"])
    }
    
    @Test
    fun testProcessRecord_RecordWithMissingFields() {
        val record = mapOf(
            "ИМТ" to "23.5",
            "Процент жира(%)" to "18.2"
        )
        
        val result = RecordProcessor.processRecord(record, validator)
        
        assertTrue("Processing should succeed with modifications", result.success)
        assertNull("Should have no error", result.error)
        assertFalse("Validation should fail but processing should succeed", result.validationResult.isValid)
        
        val garminRecord = result.record
        assertNotNull("Should have Date field with default", garminRecord["Date"])
        assertNotNull("Should have Weight field with default", garminRecord["Weight"])
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
        
        val garminRecord = result.record
        assertNotNull("Should have Date field with default", garminRecord["Date"])
        assertNotNull("Should have Weight field with default", garminRecord["Weight"])
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
        assertNotNull("Should handle comma decimal in weight", garminRecord["Weight"])
        assertEquals("Should preserve BMI", "24.5", garminRecord["BMI"])
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
        assertNotNull("Should have weight", garminRecord["Weight"])
        assertEquals("BMI should be empty for empty input", "", garminRecord["BMI"])
        assertEquals("Fat should be empty for empty input", "", garminRecord["Fat"])
    }
}