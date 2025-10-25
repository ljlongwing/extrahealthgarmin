package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class CsvProcessingLoggerTest {
    
    @Test
    fun testGenerateSummaryReport_ValidReport() {
        val report = ValidationReport(
            totalRecords = 10,
            validRecords = 8,
            recordsWithMissingFields = 1,
            recordsWithInvalidFields = 1,
            recordsModified = 2,
            fieldModificationSummary = mapOf("Дата" to 1, "Вес (кг)" to 1),
            details = listOf("Record has issues: Missing: Дата", "Record has issues: Invalid: Вес (кг)")
        )
        
        val reportText = CsvProcessingLogger.generateSummaryReport(report)
        
        assertTrue("Report should contain title", reportText.contains("CSV Processing Summary Report"))
        assertTrue("Report should contain total records", reportText.contains("Total records processed: 10"))
        assertTrue("Report should contain valid records", reportText.contains("Valid records: 8"))
        assertTrue("Report should contain missing fields count", reportText.contains("Records with missing fields: 1"))
        assertTrue("Report should contain invalid fields count", reportText.contains("Records with invalid fields: 1"))
        assertTrue("Report should contain modified count", reportText.contains("Records modified: 2"))
        assertTrue("Report should contain field modifications", reportText.contains("Дата: 1 modifications"))
        assertTrue("Report should contain detailed issues", reportText.contains("Record has issues: Missing: Дата"))
        assertTrue("Report should contain success rate", reportText.contains("Success Rate: 80%"))
    }
    
    @Test
    fun testGenerateSummaryReport_EmptyReport() {
        val report = ValidationReport()
        
        val reportText = CsvProcessingLogger.generateSummaryReport(report)
        
        assertTrue("Report should contain title", reportText.contains("CSV Processing Summary Report"))
        assertTrue("Report should contain zero records", reportText.contains("Total records processed: 0"))
        assertTrue("Report should contain zero success rate", reportText.contains("Success Rate: 0%"))
        assertFalse("Report should not contain field modifications for empty report", reportText.contains("modifications:"))
    }
    
    @Test
    fun testGenerateSummaryReport_PerfectReport() {
        val report = ValidationReport(
            totalRecords = 5,
            validRecords = 5,
            recordsWithMissingFields = 0,
            recordsWithInvalidFields = 0,
            recordsModified = 0
        )
        
        val reportText = CsvProcessingLogger.generateSummaryReport(report)
        
        assertTrue("Report should contain title", reportText.contains("CSV Processing Summary Report"))
        assertTrue("Report should contain total records", reportText.contains("Total records processed: 5"))
        assertTrue("Report should contain valid records", reportText.contains("Valid records: 5"))
        assertTrue("Report should contain 100% success rate", reportText.contains("Success Rate: 100%"))
        assertFalse("Report should not contain field modifications", reportText.contains("Field Modifications Summary"))
    }
}