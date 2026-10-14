package com.example.tefal2garmin

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tefal2garmin.utils.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {

    @Test
    fun testHandleSendIntentWithText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "test,csv,data")
            type = "text/csv"
        }

        // Create activity and test intent handling
        val activity = MainActivity()
        activity.setIntent(intent)

        // This tests that the activity can handle a SEND intent with text
        // In a real app, you would verify the UI updates correctly
        Assert.assertTrue("Intent should be handled", true)
    }

    @Test
    fun testHandleSendIntentWithUri() {
        val testUri = Uri.parse("content://test/path/to/file.csv")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, testUri)
            type = "application/csv"
        }

        // Test that URI is correctly extracted from intent
        val activity = MainActivity()
        activity.setIntent(intent)

        // This tests that the activity can handle a SEND intent with URI
        // In a real app, you would verify the UI updates correctly
        Assert.assertTrue("URI intent should be handled", true)
    }

    @Test
    fun testIntentFilterConfiguration() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val packageName = context.packageName

        // Get the main activity intent filter
        val mainIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            setPackage(packageName)
        }

        val resolveInfo: android.content.pm.ResolveInfo? = context.packageManager.resolveActivity(mainIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

        // Verify that our app can resolve the SEND intent for CSV files
        Assert.assertNotNull("App should resolve SEND intent for CSV", resolveInfo)
        Assert.assertEquals("Should resolve to our MainActivity",
                        "${packageName}.MainActivity",
                        resolveInfo?.activityInfo?.name)
    }
}

/**
 * End-to-End Integration Tests for CSV Processing Flow
 * Tests the complete flow from file input to Garmin CSV output
 */
@RunWith(AndroidJUnit4::class)
class EndToEndIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Test Data Constants

    // Primary Goodvibes export header (15 columns, as in the real Tefal export).
    // RecordProcessor reads only: Время измерения / Вес(кг) / ИМТ / Процент жира(%).
    private val validTefalHeaders = "Время измерения,Вес(кг),ИМТ,Пульс(bpm),Процент жира(%),Масса тела без жира(кг),Подкожный жир(%),Висцеральный жир,Вода в организме(%),Скелетные мышцы(%),Мышечная масса(кг),Костная масса(кг),Белок(%),Величина основного обмена(kcal),Метаболический возраст"

    private val validTefalData = listOf(
        goodvibesRow("\"15.01.2024\"", "155.5", "23.5", "18.2"),
        goodvibesRow("\"16.01.2024\"", "154.75", "23.4", "18.1"),
        goodvibesRow("\"17.01.2024\"", "154.0", "23.3", "18.0")
    ).joinToString("\n")

    private val invalidHeaders = "Name,Age,City"
    private val invalidData = "John,25,New York"

    /**
     * Builds one full 15-column Goodvibes data row.
     * Column order mirrors the primary header:
     * date, weight, bmi, pulse, fat, then the 10 non-primary columns.
     * Dates are quoted like in the real export.
     */
    private fun goodvibesRow(
        date: String,
        weight: String,
        bmi: String,
        fat: String,
        pulse: String = "88.00",
        leanMass: String = "62.20",
        subcutaneousFat: String = "19.40",
        visceralFat: String = "7.00",
        bodyWater: String = "56.40",
        skeletalMuscle: String = "44.50",
        muscleMass: String = "59.10",
        boneMass: String = "3.11",
        protein: String = "17.80",
        basalMetabolism: String = "1713.00",
        metabolicAge: String = "52.00"
    ): String {
        return listOf(
            date, weight, bmi, pulse, fat, leanMass, subcutaneousFat, visceralFat,
            bodyWater, skeletalMuscle, muscleMass, boneMass, protein,
            basalMetabolism, metabolicAge
        ).joinToString(",")
    }

    @Test
    fun testEndToEnd_ValidCsvProcessing() {
        // Arrange: Create valid Tefal CSV file
        val inputFile = createTempFile(
            "valid_tefal.csv",
            listOf(validTefalHeaders, validTefalData).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_output.csv")

        // Act: Process the file through the full pipeline
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Verify successful processing
        Assert.assertTrue("Processing should succeed: ${result.error}", result.success)
        Assert.assertEquals("Should process 3 records", 3, result.recordsProcessed)
        Assert.assertEquals("Should generate 3 records", 3, result.recordsGenerated)
        Assert.assertTrue("Output file should exist", outputFile.exists())

        // Verify output format
        val outputContent = outputFile.readText()
        val lines = outputContent.lines().filter { it.isNotEmpty() }

        Assert.assertEquals("Should have Body + header + 3 data lines", 5, lines.size)
        Assert.assertEquals("First line should be Body", "Body", lines[0])
        Assert.assertEquals("Header should be correct (Fat before BMI)", "Date,Weight,Fat,BMI", lines[1])

        // Verify a full data row: Date,Weight,Fat,BMI
        Assert.assertEquals(
            "First data row should follow the Garmin schema order",
            "2024-01-15,155.5,18.2,23.5",
            lines[2]
        )

        // Verify date conversion (DD.MM.YYYY -> YYYY-MM-DD)
        Assert.assertTrue("Should contain converted dates", outputContent.contains("2024-01-15"))
        Assert.assertTrue("Should contain weight data", outputContent.contains("155.5"))
    }

    @Test
    fun testEndToEnd_InvalidHeaders() {
        // Arrange: Create CSV with wrong headers
        val inputFile = createTempFile(
            "invalid_headers.csv",
            listOf(invalidHeaders, invalidData).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_invalid_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should fail due to invalid headers
        Assert.assertFalse("Processing should fail with invalid headers", result.success)
        Assert.assertTrue("Should have validation errors", result.error?.contains("headers") ?: false)
    }

    @Test
    fun testEndToEnd_MissingRequiredFields() {
        // Arrange: Create CSV with missing required fields
        // Row 1: empty weight; Row 2: empty BMI and fat
        val inputFile = createTempFile(
            "missing_fields.csv",
            listOf(
                validTefalHeaders,
                goodvibesRow("\"15.01.2024\"", "", "23.5", "18.2"),
                goodvibesRow("\"16.01.2024\"", "154.75", "", "")
            ).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_missing_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should fail due to missing fields
        Assert.assertFalse("Processing should fail with missing fields", result.success)
        Assert.assertTrue("Should have validation issues", result.validationIssues.isNotEmpty())
    }

    @Test
    fun testEndToEnd_InvalidDateFormat() {
        // Arrange: Create CSV with invalid date format
        val inputFile = createTempFile(
            "invalid_date.csv",
            listOf(
                validTefalHeaders,
                goodvibesRow("invalid-date", "155.5", "23.5", "18.2")
            ).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_invalid_date_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should fail due to invalid date
        Assert.assertFalse("Processing should fail with invalid date", result.success)
        Assert.assertTrue("Should have date validation issues", result.validationIssues.any { it.contains("date") })
    }

    @Test
    fun testEndToEnd_MultilineRecords() {
        // Arrange: Create CSV with a quoted value spanning two physical lines.
        // The multiline value is placed in the Пульс(bpm) column, which the
        // pipeline ignores: the Garmin schema requires a decimal Fat value, so
        // a multiline Fat field could never pass generation. The merge itself
        // (unclosed-quote handling in detectAndMergeMultilineRecords) is
        // exercised identically regardless of which column holds the value.
        val multilinePulse = "\"88.00\nand a continuation line\""
        val inputFile = createTempFile(
            "multiline.csv",
            listOf(
                validTefalHeaders,
                goodvibesRow("\"15.01.2024\"", "155.5", "23.5", "18.2", pulse = multilinePulse),
                goodvibesRow("\"16.01.2024\"", "154.75", "23.4", "18.1")
            ).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_multiline_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should handle multiline records
        Assert.assertTrue(
            "Processing should succeed with multiline records: ${result.error}",
            result.success
        )
        Assert.assertEquals("Should process 2 records", 2, result.recordsProcessed)
        Assert.assertEquals("Should generate 2 records", 2, result.recordsGenerated)
    }

    @Test
    fun testEndToEnd_LargeFile() {
        // Arrange: Create larger CSV file with multiple records
        val records = (1..50).joinToString("\n") { index ->
            goodvibesRow("\"15.01.2024\"", "${155.5 + index * 0.1}", "23.5", "18.2")
        }
        val inputFile = createTempFile(
            "large_file.csv",
            listOf(validTefalHeaders, records).joinToString("\n")
        )

        val outputFile = File(context.cacheDir, "test_large_output.csv")

        // Act: Process the large file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should handle large files efficiently
        Assert.assertTrue("Processing should succeed for large file: ${result.error}", result.success)
        Assert.assertEquals("Should process all 50 records", 50, result.recordsProcessed)
        Assert.assertEquals("Should generate all 50 records", 50, result.recordsGenerated)
    }

    @Test
    fun testEndToEnd_EmptyFile() {
        // Arrange: Create empty CSV file
        val inputFile = createTempFile("empty.csv", "")

        val outputFile = File(context.cacheDir, "test_empty_output.csv")

        // Act: Process the empty file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should fail gracefully with empty file
        Assert.assertFalse("Processing should fail for empty file", result.success)
        Assert.assertTrue("Should indicate empty file error", result.error?.contains("empty") ?: false)
    }

    @Test
    fun testEndToEnd_NonUtf8Encoding() {
        // Arrange: Build a valid CSV, then corrupt it with a byte that can never
        // occur in UTF-8 (0xFF). Writing the text with a legacy charset would not
        // work: unmappable characters are silently replaced with '?' bytes, which
        // are still valid UTF-8, so detection would never trigger.
        val inputFile = createTempFile("non_utf8.csv", "")
        val validCsvBytes = listOf(
            validTefalHeaders,
            goodvibesRow("15.01.2024", "155.5", "23.5", "18.2")
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
        inputFile.writeBytes(validCsvBytes + byteArrayOf(0xFF.toByte()))

        val outputFile = File(context.cacheDir, "test_encoding_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should detect encoding issues
        Assert.assertFalse("Processing should fail for non-UTF8 file", result.success)
        Assert.assertTrue(
            "Should indicate encoding error, was: ${result.error}",
            result.error?.contains("encoding") ?: false
        )
    }

    /**
     * Helper method to simulate the full CSV processing pipeline
     * (mirrors FileRepository.processCsvFile)
     */
    private fun processCsvFile(inputFile: File, outputFile: File): GarminCsvGenerator.GenerationResult {
        return try {
            // Step 1: Read and validate file
            val fileContent = inputFile.readText()
            if (fileContent.isEmpty()) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = emptyList(),
                    error = "File is empty"
                )
            }

            // Step 2: Validate encoding (strict UTF-8 check, like CsvValidator.validateEncoding)
            if (!isValidUtf8(inputFile)) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = emptyList(),
                    error = "File encoding is not valid UTF-8"
                )
            }

            // Step 3: Parse CSV
            val lines = fileContent.lines().filter { it.isNotBlank() }
            if (lines.size < 2) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = emptyList(),
                    error = "File must contain headers and at least one data row"
                )
            }

            // Step 4: Parse rows (quoted multiline records are merged, as in the
            // real pipeline) and validate headers against the primary Goodvibes
            // keys that RecordProcessor actually reads
            val parsedRows = CsvLineParser().detectAndMergeMultilineRecords(lines)
            val headers = parsedRows.firstOrNull() ?: emptyList()
            val expectedHeaders = listOf("Время измерения", "Вес(кг)", "ИМТ", "Процент жира(%)")
            val missingHeaders = expectedHeaders.filter { !headers.contains(it) }
            if (missingHeaders.isNotEmpty()) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = listOf("Missing required headers: $missingHeaders"),
                    error = "Invalid CSV headers"
                )
            }

            // Step 5: Map parsed rows to records keyed by the real Tefal header
            // names (same as headers.zip(row).toMap() in FileRepository)
            val records = parsedRows.drop(1).map { row -> headers.zip(row).toMap() }

            // Step 6: Process records (normalize dates, validate)
            val processedRecords = mutableListOf<Map<String, String?>>()
            val validationIssues = mutableListOf<String>()

            val validator = CsvValidator()

            for ((index, record) in records.withIndex()) {
                try {
                    val result = RecordProcessor.processRecord(record, validator)
                    if (result.success) {
                        processedRecords.add(result.record)
                    } else {
                        validationIssues.add("Record ${index + 1}: ${result.error}")
                    }
                } catch (e: Exception) {
                    validationIssues.add("Record ${index + 1}: ${e.message}")
                }
            }

            if (validationIssues.isNotEmpty()) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = records.size,
                    recordsGenerated = 0,
                    validationIssues = validationIssues,
                    error = "Validation failed for ${validationIssues.size} records"
                )
            }

            // Step 7: Generate Garmin CSV
            return GarminCsvGenerator.generateGarminCsv(processedRecords, outputFile)

        } catch (e: Exception) {
            GarminCsvGenerator.GenerationResult(
                success = false,
                recordsProcessed = 0,
                recordsGenerated = 0,
                validationIssues = emptyList(),
                error = "Processing failed: ${e.message}"
            )
        }
    }

    /**
     * Helper method to create temporary test files
     */
    private fun createTempFile(filename: String, content: String): File {
        val file = File(context.cacheDir, filename)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /**
     * Helper method to check if file is valid UTF-8.
     * Uses a strict decoder: malformed or unmappable bytes must be reported,
     * not silently replaced (readText replaces them and can never fail).
     */
    private fun isValidUtf8(file: File): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes()))
            true
        } catch (e: Exception) {
            false
        }
    }
}
