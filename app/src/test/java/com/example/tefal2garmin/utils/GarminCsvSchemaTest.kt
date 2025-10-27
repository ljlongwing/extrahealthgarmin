package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class GarminCsvSchemaTest {
    
    @Test
    fun testValidateRecord_ValidRecord() {
        val record = mapOf(
            "Date" to "2024-01-15",
            "Weight" to "155.50",
            "BMI" to "23.5",
            "Fat" to "18.2"
        )
        
        val result = GarminCsvSchema.validateRecord(record)
        
        assertTrue("Valid record should pass validation", result.isValid)
        assertTrue("Issues list should be empty", result.issues.isEmpty())

    }
    
    @Test
    fun testValidateRecord_MissingRequiredFields() {
        val record = mapOf(
            "Weight" to "155.50",
            "BMI" to "23.5"
            // Missing Date and Fat
        )
        
        val result = GarminCsvSchema.validateRecord(record)
        
        assertFalse("Record with missing fields should be invalid", result.isValid)
        assertEquals("Should detect 2 missing fields", 2, result.issues.size)
        assertTrue("Should detect missing Date", result.issues.any { it.contains("Missing required field: Date") })
        assertTrue("Should detect missing Fat", result.issues.any { it.contains("Missing required field: Fat") })
    }
    
    @Test
    fun testValidateRecord_InvalidDateFormat() {
        val record = mapOf(
            "Date" to "15/01/2024", // Invalid format
            "Weight" to "155.50",
            "BMI" to "23.5",
            "Fat" to "18.2"
        )
        
        val result = GarminCsvSchema.validateRecord(record)
        
        assertFalse("Record with invalid date should be invalid", result.isValid)
        assertEquals("Should detect invalid date", 1, result.issues.size)
        assertTrue("Should mention date format issue", result.issues.any { it.contains("Invalid date format") })
    }
    
    @Test
    fun testValidateRecord_InvalidNumericValues() {
        val record = mapOf(
            "Date" to "2024-01-15",
            "Weight" to "not-a-number",
            "BMI" to "23.5",
            "Fat" to "18.2"
        )
        
        val result = GarminCsvSchema.validateRecord(record)
        
        assertFalse("Record with invalid weight should be invalid", result.isValid)
        assertEquals("Should detect weight parsing issue", 1, result.issues.size)
        assertTrue("Should mention weight parsing error", result.issues.any { it.contains("Invalid weight format") })
    }
    
    @Test
    fun testFormatRecord_ValidRecord() {
        val record = mapOf(
            "Date" to "2024-01-15",
            "Weight" to "155.5",
            "BMI" to "23.5",
            "Fat" to "18.2"
        )
        
        val csvLine = GarminCsvSchema.formatRecord(record)
        
        assertEquals("Should format correctly", "2024-01-15,155.5,18.2,23.5", csvLine)
    }
    
    @Test
    fun testFormatRecord_RecordWithCommaDecimal() {
        val record = mapOf(
            "Date" to "2024-01-15",
            "Weight" to "155,5", // Comma decimal
            "BMI" to "23,5",
            "Fat" to "18,2"
        )
        
        val csvLine = GarminCsvSchema.formatRecord(record)
        
        assertEquals("Should convert comma decimal to point", "2024-01-15,155,5,18,2,23,5", csvLine)
    }
    
    @Test
    fun testGenerateHeader() {
        val header = GarminCsvSchema.generateHeader()

        assertEquals("Should generate correct header with Body prefix", "Body\nDate,Weight,Fat,BMI", header)
    }
    
    @Test
    fun testGetExpectedColumns() {
        val columns = GarminCsvSchema.getExpectedColumns()
        
        assertEquals("Should have 4 columns", 4, columns.size)
        assertTrue("Should contain Date", columns.contains("Date"))
        assertTrue("Should contain Weight", columns.contains("Weight"))
        assertTrue("Should contain BMI", columns.contains("BMI"))
        assertTrue("Should contain Fat", columns.contains("Fat"))
    }
    
    @Test
    fun testIsGarminColumn() {
        assertTrue("Should recognize Date column", GarminCsvSchema.isGarminColumn("Date"))
        assertTrue("Should recognize Weight column", GarminCsvSchema.isGarminColumn("Weight"))
        assertTrue("Should recognize BMI column", GarminCsvSchema.isGarminColumn("BMI"))
        assertTrue("Should recognize Fat column", GarminCsvSchema.isGarminColumn("Fat"))
        assertFalse("Should not recognize invalid column", GarminCsvSchema.isGarminColumn("Invalid"))
    }
}