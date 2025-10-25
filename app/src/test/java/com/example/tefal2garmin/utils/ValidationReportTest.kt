package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class ValidationReportTest {
    
    @Test
    fun testAddRecord_ValidRecord() {
        val report = ValidationReport()
        val validationResult = CsvValidator.RecordValidationResult(
            isValid = true,
            missingFields = emptyList(),
            invalidFields = emptyList(),
            modifiedValues = emptyMap(),
            message = "Record is valid"
        )
        
        val newReport = report.addRecord(validationResult)
        
        assertEquals("Total records should increase", 1, newReport.totalRecords)
        assertEquals("Valid records should increase", 1, newReport.validRecords)
        assertEquals("Records with missing fields should not change", 0, newReport.recordsWithMissingFields)
        assertEquals("Records with invalid fields should not change", 0, newReport.recordsWithInvalidFields)
        assertEquals("Records modified should not change", 0, newReport.recordsModified)
        assertTrue("Details should be empty for valid record", newReport.details.isEmpty())
    }
    
    @Test
    fun testAddRecord_RecordWithMissingFields() {
        val report = ValidationReport()
        val validationResult = CsvValidator.RecordValidationResult(
            isValid = false,
            missingFields = listOf("Дата", "Вес (кг)"),
            invalidFields = emptyList(),
            modifiedValues = mapOf("Дата" to "2024-01-01", "Вес (кг)" to "0.0"),
            message = "Record has issues: Missing: Дата, Вес (кг)"
        )
        
        val newReport = report.addRecord(validationResult)
        
        assertEquals("Total records should increase", 1, newReport.totalRecords)
        assertEquals("Valid records should not change", 0, newReport.validRecords)
        assertEquals("Records with missing fields should increase", 1, newReport.recordsWithMissingFields)
        assertEquals("Records with invalid fields should not change", 0, newReport.recordsWithInvalidFields)
        assertEquals("Records modified should increase", 1, newReport.recordsModified)
        assertEquals("Should track field modifications", 2, newReport.fieldModificationSummary.size)
        assertEquals("Should track date modifications", 1, newReport.fieldModificationSummary["Дата"])
        assertEquals("Should track weight modifications", 1, newReport.fieldModificationSummary["Вес (кг)"])
        assertEquals("Should have details", 2, newReport.details.size)
    }
    
    @Test
    fun testAddRecord_RecordWithInvalidFields() {
        val report = ValidationReport()
        val validationResult = CsvValidator.RecordValidationResult(
            isValid = false,
            missingFields = emptyList(),
            invalidFields = listOf("ИМТ (invalid BMI value)"),
            modifiedValues = mapOf("ИМТ" to "0.0"),
            message = "Record has issues: Invalid: ИМТ (invalid BMI value)"
        )
        
        val newReport = report.addRecord(validationResult)
        
        assertEquals("Total records should increase", 1, newReport.totalRecords)
        assertEquals("Valid records should not change", 0, newReport.validRecords)
        assertEquals("Records with missing fields should not change", 0, newReport.recordsWithMissingFields)
        assertEquals("Records with invalid fields should increase", 1, newReport.recordsWithInvalidFields)
        assertEquals("Records modified should increase", 1, newReport.recordsModified)
        assertEquals("Should track field modifications", 1, newReport.fieldModificationSummary.size)
        assertEquals("Should track BMI modifications", 1, newReport.fieldModificationSummary["ИМТ"])
    }
    
    @Test
    fun testAddRecord_MultipleRecords() {
        val report = ValidationReport()
        
        // Add valid record
        val validResult = CsvValidator.RecordValidationResult(
            isValid = true,
            missingFields = emptyList(),
            invalidFields = emptyList(),
            modifiedValues = emptyMap(),
            message = "Record is valid"
        )
        
        // Add record with missing fields
        val missingResult = CsvValidator.RecordValidationResult(
            isValid = false,
            missingFields = listOf("Дата"),
            invalidFields = emptyList(),
            modifiedValues = mapOf("Дата" to "2024-01-01"),
            message = "Record has issues: Missing: Дата"
        )
        
        val newReport = report.addRecord(validResult).addRecord(missingResult)
        
        assertEquals("Total records should be 2", 2, newReport.totalRecords)
        assertEquals("Valid records should be 1", 1, newReport.validRecords)
        assertEquals("Records with missing fields should be 1", 1, newReport.recordsWithMissingFields)
        assertEquals("Records modified should be 1", 1, newReport.recordsModified)
    }
    
    @Test
    fun testHasIssues() {
        val validReport = ValidationReport(totalRecords = 5, validRecords = 5, recordsModified = 0)
        assertFalse("Report with all valid records should have no issues", validReport.hasIssues())
        
        val invalidRecordsReport = ValidationReport(totalRecords = 5, validRecords = 3, recordsModified = 0)
        assertTrue("Report with invalid records should have issues", invalidRecordsReport.hasIssues())
        
        val modifiedRecordsReport = ValidationReport(totalRecords = 5, validRecords = 5, recordsModified = 2)
        assertTrue("Report with modified records should have issues", modifiedRecordsReport.hasIssues())
    }
}