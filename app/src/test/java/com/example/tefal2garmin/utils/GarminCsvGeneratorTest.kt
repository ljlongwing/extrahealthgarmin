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
        assertTrue("Should contain Date,Weight,Fat,BMI header", content.contains("Date,Weight,Fat,BMI"))
        assertTrue("Should contain sample data", content.contains("2024-01-15,155.00,18.20,23.50"))
    }

    @Test
    fun testGenerateGarminCsv_HeaderOrderAndPresence() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2")
        )

        val outputFile = File.createTempFile("test_header_order.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)
        assertTrue("Output file should exist", outputFile.exists())

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        assertEquals("Should have Body + header + 1 data line", 3, lines.size)

        // Verify Body is first line
        assertEquals("First line should be Body", "Body", lines[0])

        // Verify header is second line and in correct order
        val headerLine = lines[1]
        assertEquals("Header should be Date,Weight,Fat,BMI", "Date,Weight,Fat,BMI", headerLine)

        // Verify data line follows header order
        val dataLine = lines[2]
        assertEquals("Data should match header order", "2024-01-15,155.50,18.2,23.5", dataLine)
    }

    @Test
    fun testGenerateGarminCsv_NoExtraHeaders() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2", "ExtraField" to "ignored")
        )

        val outputFile = File.createTempFile("test_no_extra_headers.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        // Verify Body is first line
        assertEquals("First line should be Body", "Body", lines[0])

        // Verify header only contains required fields
        val headerLine = lines[1]
        assertEquals("Header should only contain required fields", "Date,Weight,Fat,BMI", headerLine)
        assertFalse("Header should not contain extra fields", headerLine.contains("ExtraField"))

        // Verify data line doesn't include extra fields
        val dataLine = lines[2]
        assertEquals("Data should only contain required fields", "2024-01-15,155.50,18.2,23.5", dataLine)
        assertFalse("Data should not contain extra fields", dataLine.contains("ignored"))
    }

    @Test
    fun testGenerateGarminCsv_MultipleRecordsHeaderConsistency() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"),
            mapOf("Date" to "2024-01-16", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "18.1"),
            mapOf("Date" to "2024-01-17", "Weight" to "154.00", "BMI" to "23.3", "Fat" to "18.0")
        )

        val outputFile = File.createTempFile("test_multiple_records_header.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        assertEquals("Should have Body + header + 3 data lines", 5, lines.size)

        // Verify Body is first line
        assertEquals("First line should be Body", "Body", lines[0])

        // Verify header is consistent
        val headerLine = lines[1]
        assertEquals("Header should be consistent", "Date,Weight,Fat,BMI", headerLine)

        // Verify each data line has exactly 4 fields in correct order
        for (i in 1..3) {
            val dataLine = lines[i]
            val fields = dataLine.split(",")
            assertEquals("Data line $i should have 4 fields", 4, fields.size)
        }
    }

    @Test
    fun testGenerateGarminCsv_NoQuotationMarks() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2")
        )

        val outputFile = File.createTempFile("test_no_quotes.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        assertFalse("Output should not contain quotation marks", content.contains("\""))
        assertFalse("Output should not contain escaped quotes", content.contains("\\\""))
    }

    @Test
    fun testGenerateGarminCsv_AllFieldsPresentInDataRows() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"),
            mapOf("Date" to "2024-01-16", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "18.1")
        )

        val outputFile = File.createTempFile("test_all_fields_present.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        // Skip header, check data lines
        for (i in 1 until lines.size) {
            val dataLine = lines[i]
            val fields = dataLine.split(",")

            assertEquals("Data line $i should have exactly 4 fields", 4, fields.size)

            // Ensure no field is empty (all required fields must be present)
            fields.forEachIndexed { fieldIndex, field ->
                assertFalse("Field ${fieldIndex + 1} in line $i should not be empty", field.isEmpty())
            }
        }
    }

    @Test
    fun testGenerateGarminCsv_SpecialCharactersHandling() {
        // Test with values that might contain special characters (though in practice these should be numeric)
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2")
        )

        val outputFile = File.createTempFile("test_special_chars.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        // Verify the data line is properly formatted
        val dataLine = lines[2]
        assertEquals("Data should be properly formatted", "2024-01-15,155.50,23.5,18.2", dataLine)

        // Ensure no unwanted characters are introduced
        assertFalse("Should not contain newlines in data", dataLine.contains("\n"))
        assertFalse("Should not contain carriage returns", dataLine.contains("\r"))
    }

    @Test
    fun testGenerateGarminCsv_EmptyRecordsRejected() {
        // Test that records with missing fields are rejected (validation should catch this)
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"), // Valid
            mapOf("Date" to "", "Weight" to "154.75", "BMI" to "23.4", "Fat" to "18.1"), // Empty date
            mapOf("Date" to "2024-01-17", "Weight" to "154.00", "BMI" to "", "Fat" to "18.0") // Empty BMI
        )

        val outputFile = File.createTempFile("test_empty_fields.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        // Should fail due to validation
        assertFalse("Generation should fail due to empty fields", result.success)
        assertTrue("Should have validation issues", result.validationIssues.isNotEmpty())

        // Verify no file is created or it's empty
        if (outputFile.exists()) {
            val content = outputFile.readText()
            val lines = content.lines().filter { it.isNotEmpty() }
            // Should only have header if any records were processed, but since validation fails, might be empty
            assertTrue("Should have minimal or no content due to validation failure", lines.size <= 1)
        }
    }

    @Test
    fun testGenerateGarminCsv_FieldOrderConsistency() {
        val records = listOf(
            mapOf("Date" to "2024-01-15", "Weight" to "155.50", "BMI" to "23.5", "Fat" to "18.2"),
            mapOf("Fat" to "17.9", "BMI" to "23.3", "Date" to "2024-01-16", "Weight" to "154.75") // Different order in map
        )

        val outputFile = File.createTempFile("test_field_order_consistency.csv", null)

        val result = GarminCsvGenerator.generateGarminCsv(records, outputFile)

        assertTrue("Generation should succeed", result.success)

        val content = outputFile.readText()
        val lines = content.lines().filter { it.isNotEmpty() }

        assertEquals("Should have Body + header + 2 data lines", 4, lines.size)

        // Verify header order
        val headerLine = lines[0]
        assertEquals("Header should be in correct order", "Date,Weight,Fat,BMI", headerLine)

        // Verify both data lines follow the same order regardless of input map order
        val dataLine1 = lines[1]
        val dataLine2 = lines[2]

        assertEquals("First data line should follow header order", "2024-01-15,155.50,23.5,18.2", dataLine1)
        assertEquals("Second data line should follow header order", "2024-01-16,154.75,23.3,17.9", dataLine2)
    }
}