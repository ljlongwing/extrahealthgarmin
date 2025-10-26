package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert

class MimeTypeValidatorTest {

    @Test
    fun testIsCsvMimeType_ValidTypes() {
        // Test official CSV MIME types
        Assert.assertTrue("text/csv should be valid", MimeTypeValidator.isCsvMimeType("text/csv"))
        Assert.assertTrue("text/comma-separated-values should be valid", MimeTypeValidator.isCsvMimeType("text/comma-separated-values"))
        Assert.assertTrue("application/csv should be valid", MimeTypeValidator.isCsvMimeType("application/csv"))
        Assert.assertTrue("application/x-csv should be valid", MimeTypeValidator.isCsvMimeType("application/x-csv"))
    }

    @Test
    fun testIsCsvMimeType_FallbackTypes() {
        // Test fallback MIME types
        Assert.assertTrue("text/plain should be valid", MimeTypeValidator.isCsvMimeType("text/plain"))
        Assert.assertTrue("application/vnd.ms-excel should be valid", MimeTypeValidator.isCsvMimeType("application/vnd.ms-excel"))
        Assert.assertTrue("application/octet-stream should be valid", MimeTypeValidator.isCsvMimeType("application/octet-stream"))
        Assert.assertTrue("text/* should be valid", MimeTypeValidator.isCsvMimeType("text/*"))
    }

    @Test
    fun testIsCsvMimeType_TextWildcard() {
        // Test text/* wildcard
        Assert.assertTrue("text/html should be valid (text/*)", MimeTypeValidator.isCsvMimeType("text/html"))
        Assert.assertTrue("text/xml should be valid (text/*)", MimeTypeValidator.isCsvMimeType("text/xml"))
        Assert.assertTrue("text/javascript should be valid (text/*)", MimeTypeValidator.isCsvMimeType("text/javascript"))
    }

    @Test
    fun testIsCsvMimeType_InvalidTypes() {
        // Test invalid MIME types
        Assert.assertFalse("image/png should be invalid", MimeTypeValidator.isCsvMimeType("image/png"))
        Assert.assertFalse("application/pdf should be invalid", MimeTypeValidator.isCsvMimeType("application/pdf"))
        Assert.assertFalse("audio/mp3 should be invalid", MimeTypeValidator.isCsvMimeType("audio/mp3"))
        Assert.assertFalse("null should be invalid", MimeTypeValidator.isCsvMimeType(null))
        Assert.assertFalse("empty string should be invalid", MimeTypeValidator.isCsvMimeType(""))
    }

    @Test
    fun testGetCsvConfidenceLevel() {
        // Test confidence levels
        Assert.assertEquals("text/csv should be HIGH confidence",
            MimeTypeValidator.CsvConfidence.HIGH, MimeTypeValidator.getCsvConfidenceLevel("text/csv"))
        Assert.assertEquals("application/csv should be HIGH confidence",
            MimeTypeValidator.CsvConfidence.HIGH, MimeTypeValidator.getCsvConfidenceLevel("application/csv"))

        Assert.assertEquals("text/plain should be MEDIUM confidence",
            MimeTypeValidator.CsvConfidence.MEDIUM, MimeTypeValidator.getCsvConfidenceLevel("text/plain"))
        Assert.assertEquals("application/octet-stream should be MEDIUM confidence",
            MimeTypeValidator.CsvConfidence.MEDIUM, MimeTypeValidator.getCsvConfidenceLevel("application/octet-stream"))

        Assert.assertEquals("text/html should be LOW confidence",
            MimeTypeValidator.CsvConfidence.LOW, MimeTypeValidator.getCsvConfidenceLevel("text/html"))

        Assert.assertEquals("image/png should be NONE confidence",
            MimeTypeValidator.CsvConfidence.NONE, MimeTypeValidator.getCsvConfidenceLevel("image/png"))
    }

    @Test
    fun testValidateIntentForCsv_EmptyIntent() {
        val result = MimeTypeValidator.validateIntentForCsv(null)

        Assert.assertFalse("Null intent should be rejected", result.isValid)
        Assert.assertNotNull("Error message should be present", result.errorMessage)
    }

    @Test
    fun testAppearsToBeCsv_ValidCsv() {
        val csvContent = """
            Name,Age,City
            John,25,New York
            Jane,30,London
            Bob,35,Paris
        """.trimIndent()

        Assert.assertTrue("Valid CSV should be recognized", MimeTypeValidator.appearsToBeCsv(csvContent))
    }

    @Test
    fun testAppearsToBeCsv_InvalidContent() {
        // Test various non-CSV content
        Assert.assertFalse("Empty content should not be CSV", MimeTypeValidator.appearsToBeCsv(""))
        Assert.assertFalse("Single line should not be CSV", MimeTypeValidator.appearsToBeCsv("Just some text"))
        Assert.assertFalse("No commas should not be CSV", MimeTypeValidator.appearsToBeCsv("Line 1\nLine 2\nLine 3"))
        Assert.assertFalse("Too many commas should not be CSV", MimeTypeValidator.appearsToBeCsv("a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z"))
    }

    @Test
    fun testGetMimeTypeDescription() {
        Assert.assertEquals("CSV file (RFC 4180 standard)", MimeTypeValidator.getMimeTypeDescription("text/csv"))
        Assert.assertEquals("Plain text file (may contain CSV)", MimeTypeValidator.getMimeTypeDescription("text/plain"))
        Assert.assertEquals("unknown/type", MimeTypeValidator.getMimeTypeDescription("unknown/type"))
        Assert.assertEquals("Unknown", MimeTypeValidator.getMimeTypeDescription(null))
    }


}