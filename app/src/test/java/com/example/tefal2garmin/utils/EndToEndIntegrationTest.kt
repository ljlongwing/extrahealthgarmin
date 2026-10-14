package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert
import java.io.ByteArrayInputStream
import java.io.File

/**
 * End-to-End Integration Tests for CSV Processing Flow
 * Tests the complete flow from file input to Garmin CSV output
 * This is a unit test version that doesn't require Android context
 */
class EndToEndIntegrationTest {

    // Test Data Constants (primary Goodvibes format, as produced by the Tefal app)
    private val validTefalHeaders = "Время измерения,Вес(кг),ИМТ,Процент жира(%)"

    private val invalidHeaders = "Name,Age,City"

    @Test
    fun testEndToEnd_ValidCsvProcessing() {
        // This is a simplified integration test that verifies the key components work together
        // Rather than testing the full file I/O pipeline, we test the core processing logic

        // Arrange: Create sample records as they would be parsed (primary Goodvibes headers)
        val sampleRecords = listOf(
            mapOf("Время измерения" to "15.01.2024", "Вес(кг)" to "155.5", "ИМТ" to "23.5", "Процент жира(%)" to "18.2"),
            mapOf("Время измерения" to "16.01.2024", "Вес(кг)" to "154.75", "ИМТ" to "23.4", "Процент жира(%)" to "18.1")
        )

        // Act: Process records through the validation and transformation pipeline
        val validator = CsvValidator()
        val processedRecords = mutableListOf<Map<String, String?>>()
        var successCount = 0

        for (record in sampleRecords) {
            val result = RecordProcessor.processRecord(record, validator)
            if (result.success) {
                processedRecords.add(result.record)
                successCount++
            }
        }

        // Assert: Verify processing succeeded
        Assert.assertEquals("Should successfully process all records", 2, successCount)
        Assert.assertEquals("Should have processed records", 2, processedRecords.size)

        // Verify the processed records have the correct Garmin format
        val firstRecord = processedRecords[0]
        Assert.assertEquals("Date should be converted to YYYY-MM-DD", "2024-01-15", firstRecord["Date"])
        Assert.assertEquals("Weight should pass through unchanged", "155.5", firstRecord["Weight"])
        Assert.assertEquals("BMI should pass through unchanged", "23.5", firstRecord["BMI"])
        Assert.assertEquals("Fat should pass through unchanged", "18.2", firstRecord["Fat"])

        // Test CSV generation with processed records
        val outputFile = File.createTempFile("test_output.csv", null)
        val generationResult = GarminCsvGenerator.generateGarminCsv(processedRecords, outputFile)

        Assert.assertTrue("CSV generation should succeed", generationResult.success)
        Assert.assertEquals("Should generate all records", 2, generationResult.recordsGenerated)
        Assert.assertTrue("Output file should exist", outputFile.exists())

        // Verify output format
        val outputContent = outputFile.readText()
        val lines = outputContent.lines().filter { it.isNotEmpty() }
        Assert.assertEquals("Should have Body + header + 2 data lines", 4, lines.size)
        Assert.assertEquals("First line should be Body", "Body", lines[0])
        Assert.assertEquals("Header should be correct", "Date,Weight,Fat,BMI", lines[1])
        Assert.assertTrue("Should contain converted dates", outputContent.contains("2024-01-15"))
    }

    @Test
    fun testEndToEnd_InvalidHeaders() {
        // Act: Validate an unknown header line through the real validator
        val result = CsvValidator().validateHeaders(invalidHeaders)

        // Assert: Should detect missing headers and name the minimum required set
        Assert.assertFalse("Unknown headers should be rejected", result.isValid)
        Assert.assertTrue(
            "Message should name the minimum required headers, was: ${result.errorMessage}",
            result.errorMessage?.contains("Missing required headers") == true &&
                result.errorMessage?.contains("Время измерения") == true
        )
    }

    @Test
    fun testEndToEnd_MissingRequiredFields() {
        // Arrange: Create record (primary Goodvibes headers) with a missing required weight
        val incompleteRecord = mapOf(
            "Время измерения" to "15.01.2024",
            "Вес(кг)" to "",
            "ИМТ" to "23.5",
            "Процент жира(%)" to "18.2"
        )

        // Act: Process the record
        val validator = CsvValidator()
        val result = RecordProcessor.processRecord(incompleteRecord, validator)

        // Assert: Processing succeeds, but validation reports the missing weight
        // with a default value; the conversion passes the original blank through
        Assert.assertNotNull("Should process record", result)
        Assert.assertTrue("Processing should succeed", result.success)
        Assert.assertFalse("Validation should flag the missing weight", result.validationResult.isValid)
        Assert.assertTrue("Weight should be reported missing", result.validationResult.missingFields.contains("Вес(кг)"))
        Assert.assertEquals("Default weight should be reported", "0.0", result.validationResult.modifiedValues["Вес(кг)"])
        Assert.assertEquals("Original blank weight passes through", "", result.record["Weight"])
    }

    @Test
    fun testEndToEnd_InvalidDateFormat() {
        // Arrange: Create record with a date normalization cannot parse
        val invalidRecord = mapOf(
            "Время измерения" to "99.99.9999",
            "Вес(кг)" to "155.5",
            "ИМТ" to "23.5",
            "Процент жира(%)" to "18.2"
        )

        // Act: Process the record
        val validator = CsvValidator()
        val result = RecordProcessor.processRecord(invalidRecord, validator)

        // Assert: Processing succeeds; validation reports the invalid date with a
        // today-default, and the unparseable date passes through unchanged
        Assert.assertNotNull("Should process record", result)
        Assert.assertTrue("Processing should succeed", result.success)
        Assert.assertFalse("Unparseable date should fail validation", result.validationResult.isValid)
        Assert.assertTrue(
            "Date should be reported invalid",
            result.validationResult.invalidFields.any { it.contains("Время измерения") }
        )
        Assert.assertTrue(
            "Default (today) date should be reported",
            result.validationResult.modifiedValues["Время измерения"]?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) == true
        )
        Assert.assertEquals("Unparseable date passes through unchanged", "99.99.9999", result.record["Date"])
    }

    @Test
    fun testEndToEnd_MultilineRecords() {
        // Arrange: A quoted Tefal date value split across two physical lines —
        // the date contains a comma, so the row's opening quote is unclosed on
        // the first line and the unclosed-quote merge must stitch the record
        // back together before parsing
        val lines = listOf(
            validTefalHeaders,
            "\"окт. 25, 2025 09:51:52",
            "\",79.65,24.60,21.90"
        )

        // Act: Route the lines through the real merge step, then map + convert
        val records = CsvLineParser().detectAndMergeMultilineRecords(lines)
        val headers = records[0]
        val recordMap = headers.zip(records[1]).toMap()
        val result = RecordProcessor.processRecord(recordMap, CsvValidator())

        // Assert: The multiline record is merged into one row and converts fully
        Assert.assertEquals("Header + one merged record expected", 2, records.size)
        Assert.assertEquals("Merged record should have 4 fields", 4, records[1].size)
        Assert.assertEquals(
            "Stitched date field should keep its embedded newline",
            "окт. 25, 2025 09:51:52\n",
            records[1][0]
        )
        Assert.assertTrue("Merged record should convert successfully", result.success)
        Assert.assertEquals("Date should be normalized despite the trailing newline", "2025-10-25", result.record["Date"])
        Assert.assertEquals("Weight should be mapped", "79.65", result.record["Weight"])
        Assert.assertEquals("Fat should be mapped", "21.90", result.record["Fat"])
        Assert.assertEquals("BMI should be mapped", "24.60", result.record["BMI"])
    }

    @Test
    fun testEndToEnd_LargeFile() {
        // Arrange: Create multiple records (primary Goodvibes headers) to test batch processing
        val records = (1..10).map { index ->
            mapOf("Время измерения" to "15.01.2024", "Вес(кг)" to "${155.5 + index * 0.1}", "ИМТ" to "23.5", "Процент жира(%)" to "18.2")
        }

        // Act: Process multiple records
        val validator = CsvValidator()
        val processedRecords = mutableListOf<Map<String, String?>>()
        var successCount = 0

        for (record in records) {
            val result = RecordProcessor.processRecord(record, validator)
            if (result.success) {
                processedRecords.add(result.record)
                successCount++
            }
        }

        // Assert: Should handle multiple records efficiently
        Assert.assertEquals("Should successfully process all records", 10, successCount)
        Assert.assertEquals("Should have processed records", 10, processedRecords.size)

        // Test CSV generation with multiple records
        val outputFile = File.createTempFile("test_large_output.csv", null)
        val generationResult = GarminCsvGenerator.generateGarminCsv(processedRecords, outputFile)

        Assert.assertTrue("CSV generation should succeed", generationResult.success)
        Assert.assertEquals("Should generate all records", 10, generationResult.recordsGenerated)
    }

    @Test
    fun testEndToEnd_EmptyFile() {
        val validator = CsvValidator()

        // Act + Assert: An empty file is rejected
        val emptyResult = validator.validateDelimiter(emptyList())
        Assert.assertFalse("Empty file should be rejected", emptyResult.isValid)
        Assert.assertTrue(
            "Should report an empty file, was: ${emptyResult.errorMessage}",
            emptyResult.errorMessage?.contains("File is empty") == true
        )

        // Semicolon-delimited headers are rejected
        val semicolonResult = validator.validateDelimiter(listOf(validTefalHeaders.replace(",", ";")))
        Assert.assertFalse("Semicolon delimiter should be rejected", semicolonResult.isValid)
        Assert.assertTrue(
            "Should name the semicolon problem, was: ${semicolonResult.errorMessage}",
            semicolonResult.errorMessage?.contains("semicolon") == true
        )

        // Tab-delimited headers are rejected too
        val tabResult = validator.validateDelimiter(listOf(validTefalHeaders.replace(",", "\t")))
        Assert.assertFalse("Tab delimiter should be rejected", tabResult.isValid)
        Assert.assertTrue(
            "Should name the tab problem, was: ${tabResult.errorMessage}",
            tabResult.errorMessage?.contains("tab") == true
        )
    }

    @Test
    fun testEndToEnd_NonUtf8Encoding() {
        val validator = CsvValidator()
        val headerBytes = validTefalHeaders.toByteArray(Charsets.UTF_8)

        // (a) A UTF-8 BOM must be rejected
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + headerBytes
        val bomResult = validator.validateEncoding(ByteArrayInputStream(bomBytes))
        Assert.assertFalse("UTF-8 BOM should be rejected", bomResult.isValid)
        Assert.assertTrue(
            "Should report the BOM, was: ${bomResult.errorMessage}",
            bomResult.errorMessage?.contains("BOM") == true
        )

        // (b) Valid Cyrillic header bytes pass
        val validResult = validator.validateEncoding(ByteArrayInputStream(headerBytes))
        Assert.assertTrue("Valid UTF-8 content should pass: ${validResult.errorMessage}", validResult.isValid)

        // (c) A valid header followed by a byte that can never occur in UTF-8 (0xFF)
        val corruptBytes = headerBytes + byteArrayOf(0xFF.toByte())
        val corruptResult = validator.validateEncoding(ByteArrayInputStream(corruptBytes))
        Assert.assertFalse("Non-UTF-8 byte should be rejected", corruptResult.isValid)
        Assert.assertTrue(
            "Should report an encoding problem, was: ${corruptResult.errorMessage}",
            corruptResult.errorMessage?.contains("encoding") == true
        )
    }

    @Test
    fun testEndToEnd_GoodvibesDmitryFormat() {
        // Test the actual Goodvibes-Dmitry CSV file format with Russian date format
        val csvContent = """
            Время измерения,Вес(кг),ИМТ,Пульс(bpm),Процент жира(%),Масса тела без жира(кг),Подкожный жир(%),Висцеральный жир,Вода в организме(%),Скелетные мышцы(%),Мышечная масса(кг),Костная масса(кг),Белок(%),Величина основного обмена(kcal),Метаболический возраст
            "окт. 25, 2025 09:51:52",79.65,24.60,88.00,21.90,62.20,19.40,7.00,56.40,44.50,59.10,3.11,17.80,1713.00,52.00
            "окт. 21, 2025 08:20:53",80.25,24.80,94.00,21.70,62.80,19.20,7.00,56.50,44.60,59.70,3.14,17.90,1727.00,52.00
        """.trimIndent()

        // Parse CSV content manually for testing (simulating what CsvReader would do)
        val lines = csvContent.lines().filter { it.isNotEmpty() }
        val headers = CsvLineParser().parseCsvLine(lines[0])
        val records = lines.drop(1).map { line ->
            val values = CsvLineParser().parseCsvLine(line)
            headers.zip(values).toMap()
        }

        Assert.assertEquals("Should have parsed headers", 15, headers.size)
        Assert.assertEquals("Should have 2 data records", 2, records.size)

        // Test header validation (skip logging in tests)
        // Just test that the headers contain the required fields
        val hasRequiredFields = headers.contains("Время измерения") && headers.contains("Вес(кг)") && headers.contains("ИМТ")
        Assert.assertTrue("Headers should contain required fields for Goodvibes-Dmitry format", hasRequiredFields)

        // Test date parsing directly
        val dateNormalizer = CsvFieldMapper
        val parsedDate1 = dateNormalizer.normalizeDate("окт. 25, 2025 09:51:52")
        val parsedDate2 = dateNormalizer.normalizeDate("окт. 21, 2025 08:20:53")

        Assert.assertEquals("Date should be parsed from 'окт. 25, 2025 09:51:52'", "2025-10-25", parsedDate1)
        Assert.assertEquals("Date should be parsed from 'окт. 21, 2025 08:20:53'", "2025-10-21", parsedDate2)

        // Test that headers contain expected fields
        Assert.assertTrue("Should contain date field", headers.contains("Время измерения"))
        Assert.assertTrue("Should contain weight field", headers.contains("Вес(кг)"))
        Assert.assertTrue("Should contain BMI field", headers.contains("ИМТ"))
        Assert.assertTrue("Should contain fat field", headers.contains("Процент жира(%)"))

        // Test record mapping (simplified)
        val firstRecord = records[0]
        val mappedRecord = dateNormalizer.mapToGarminFormat(
            weightKg = firstRecord["Вес(кг)"],
            bmi = firstRecord["ИМТ"],
            fatPercentage = firstRecord["Процент жира(%)"],
            date = parsedDate1
        )

        Assert.assertEquals("Mapped date should be correct", "2025-10-25", mappedRecord["Date"])
        Assert.assertNotNull("Weight should be present", mappedRecord["Weight"])
        Assert.assertEquals("BMI should be correct", "24.60", mappedRecord["BMI"])
        Assert.assertEquals("Fat percentage should be correct", "21.90", mappedRecord["Fat"])

        // Test CSV generation with sample records
        val sampleRecords = listOf(mappedRecord)
        val outputFile = File.createTempFile("goodvibes_output.csv", null)
        val generationResult = GarminCsvGenerator.generateGarminCsv(sampleRecords, outputFile)

        Assert.assertTrue("CSV generation should succeed", generationResult.success)
        Assert.assertEquals("Should generate records", 1, generationResult.recordsGenerated)
        Assert.assertTrue("Output file should exist", outputFile.exists())

        // Verify output contains correctly formatted dates
        val outputContent = outputFile.readText()
        Assert.assertTrue("Should contain converted date", outputContent.contains("2025-10-25"))
    }
}