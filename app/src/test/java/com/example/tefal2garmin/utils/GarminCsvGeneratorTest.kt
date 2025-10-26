package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class GarminCsvGeneratorTest {
    
    @Test
    fun testGenerateGarminCsv_ValidRecords() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"),
            mapOf("Date" to "2024-01-16", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "18.1")
        )
        
        val outputFile = File.createTempFile("test_garmin.csv", null)
        
        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)
        
        assertTrue("Generation should succeed", result.success)
        assertEquals("Should process all records", 2, result.recordsProcessed)
        assertEquals("Should generate all records", 2, result.recordsGenerated)
        assertTrue("Should have no validation issues", result.validationIssues.isEmpty())
        assertNull("Should have no error", result.error)
        assertTrue("Output file should exist", outputFile.exists())
    }
    
    @Test
    fun testGenerateGarminCsv_InvalidRecords() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "invalid-bmi", "Fat" to "18.2"),
            mapOf("Weight" to "3000.0", "BMI" to "23.4") // Missing Date, invalid Weight
        )
        
        val outputFile = File.createTempFile("test_garmin_invalid.csv", null)
        
        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)
        
        assertFalse("Generation should fail due to validation", result.success)
        assertEquals("Should process all records", 2, result.recordsProcessed)
        assertEquals("Should generate no records", 0, result.recordsGenerated)
        assertFalse("Should have validation issues", result.validationIssues.isEmpty())
        assertNotNull("Should have error message", result.error)
        assertTrue("Error message should mention validation", result.error?.contains("Validation failed") ?: false)
    }
    
    @Test
    fun testValidateAllRecords_AllValid() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"),
            mapOf("Date" to "2024-01-16", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "18.1")
        )
        
        val result = GarminCsvGenerator.validateAllRecords(records)
        
        assertTrue("All records should be valid", result.isValid)
        assertEquals("Should have 2 total records", 2, result.totalRecords)
        assertEquals("Should have 2 valid records", 2, result.validRecords)
        assertEquals("Should have 0 invalid records", 0, result.invalidRecords)
        assertTrue("Should have no issues", result.allIssues.isEmpty())
    }
    
    @Test
    fun testValidateAllRecords_HasInvalidRecords() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "invalid-bmi", "Fat" to "18.2"),
            mapOf("Date" to "2024-01-16", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "17.8") // Valid record - has all required fields
        )
        
        val result = GarminCsvGenerator.validateAllRecords(records)
        
        assertFalse("Should not be valid due to issues", result.isValid)
        assertEquals("Should have 2 total records", 2, result.totalRecords)
        assertEquals("Should have 1 valid record", 1, result.validRecords)
        assertEquals("Should have 1 invalid record", 1, result.invalidRecords)
        assertFalse("Should have issues", result.allIssues.isEmpty())
    }
    
    @Test
    fun testCreateSampleFile() {
        val outputFile = File.createTempFile("test_sample_garmin.csv", null)
        
        GarminCsvGenerator.createSampleFile(outputFile)
        
        assertTrue("Sample file should exist", outputFile.exists())
        
        // Verify file content
        val content = outputFile.readText()
        assertTrue("Should contain Date,Weight,BMI,Fat header", content.contains("Date,Weight,BMI,Fat"))
        assertTrue("Should contain sample data", content.contains("2024-01-15,155.00,23.50,18.20"))
    }
}