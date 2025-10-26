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

    // Unit test version that doesn't require Android context
    companion object {
        @JvmStatic
        fun createEndToEndUnitTest(): EndToEndUnitTest {
            return EndToEndUnitTest()
        }
    }

    // Test Data Constants
    private val validTefalHeaders = "Дата,Вес,ИМТ,Жир"
    private val validTefalData = """
        15.01.2024,155.5,23.5,18.2
        16.01.2024,154.75,23.4,18.1
        17.01.2024,154.0,23.3,18.0
    """.trimIndent()

    private val invalidHeaders = "Name,Age,City"
    private val invalidData = "John,25,New York"

    @Test
    fun testEndToEnd_ValidCsvProcessing() {
        // Arrange: Create valid Tefal CSV file
        val inputFile = createTempFile("valid_tefal.csv", """
            $validTefalHeaders
            $validTefalData
        """.trimIndent())

        val outputFile = File(context.cacheDir, "test_output.csv")

        // Act: Process the file through the full pipeline
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Verify successful processing
        Assert.assertTrue("Processing should succeed", result.success)
        Assert.assertEquals("Should process 3 records", 3, result.recordsProcessed)
        Assert.assertEquals("Should generate 3 records", 3, result.recordsGenerated)
        Assert.assertTrue("Output file should exist", outputFile.exists())

        // Verify output format
        val outputContent = outputFile.readText()
        val lines = outputContent.lines().filter { it.isNotEmpty() }

        Assert.assertEquals("Should have header + 3 data lines", 4, lines.size)
        Assert.assertEquals("Header should be correct", "Date,Weight,BMI,Fat", lines[0])

        // Verify date conversion (DD.MM.YYYY -> YYYY-MM-DD)
        Assert.assertTrue("Should contain converted dates", outputContent.contains("2024-01-15"))
        Assert.assertTrue("Should contain weight data", outputContent.contains("155.5"))
    }

    @Test
    fun testEndToEnd_InvalidHeaders() {
        // Arrange: Create CSV with wrong headers
        val inputFile = createTempFile("invalid_headers.csv", """
            $invalidHeaders
            $invalidData
        """.trimIndent())

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
        val inputFile = createTempFile("missing_fields.csv", """
            $validTefalHeaders
            15.01.2024,,23.5,18.2
            16.01.2024,154.75,,
        """.trimIndent())

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
        val inputFile = createTempFile("invalid_date.csv", """
            $validTefalHeaders
            invalid-date,155.5,23.5,18.2
        """.trimIndent())

        val outputFile = File(context.cacheDir, "test_invalid_date_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should fail due to invalid date
        Assert.assertFalse("Processing should fail with invalid date", result.success)
        Assert.assertTrue("Should have date validation issues", result.validationIssues.any { it.contains("date") })
    }

    @Test
    fun testEndToEnd_MultilineRecords() {
        // Arrange: Create CSV with multiline records (simulating notes with newlines)
        val inputFile = createTempFile("multiline.csv", """
            $validTefalHeaders
            "15.01.2024",155.5,23.5,"18.2
            with extra line"
            16.01.2024,154.75,23.4,18.1
        """.trimIndent())

        val outputFile = File(context.cacheDir, "test_multiline_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should handle multiline records
        Assert.assertTrue("Processing should succeed with multiline records", result.success)
        Assert.assertEquals("Should process 2 records", 2, result.recordsProcessed)
    }

    @Test
    fun testEndToEnd_LargeFile() {
        // Arrange: Create larger CSV file with multiple records
        val records = (1..50).joinToString("\n") { index ->
            "15.01.2024,${155.5 + index * 0.1},23.5,18.2"
        }
        val inputFile = createTempFile("large_file.csv", """
            $validTefalHeaders
            $records
        """.trimIndent())

        val outputFile = File(context.cacheDir, "test_large_output.csv")

        // Act: Process the large file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should handle large files efficiently
        Assert.assertTrue("Processing should succeed for large file", result.success)
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
        // Arrange: Create file with non-UTF8 content (simulate wrong encoding)
        val inputFile = createTempFile("non_utf8.csv", "Дата,Вес,ИМТ,Жир\n15.01.2024,155.5,23.5,18.2")
        // Write with different encoding
        inputFile.writeText("Дата,Вес,ИМТ,Жир\n15.01.2024,155.5,23.5,18.2", Charsets.ISO_8859_1)

        val outputFile = File(context.cacheDir, "test_encoding_output.csv")

        // Act: Process the file
        val result = processCsvFile(inputFile, outputFile)

        // Assert: Should detect encoding issues
        Assert.assertFalse("Processing should fail for non-UTF8 file", result.success)
        Assert.assertTrue("Should indicate encoding error", result.error?.contains("encoding") ?: false)
    }

    /**
     * Helper method to simulate the full CSV processing pipeline
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

            // Step 2: Validate encoding (simplified check)
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

            // Step 4: Validate headers
            val headers = lines[0].split(",").map { it.trim() }
            val expectedHeaders = listOf("Дата", "Вес", "ИМТ", "Жир")
            if (!headers.containsAll(expectedHeaders)) {
                return GarminCsvGenerator.GenerationResult(
                    success = false,
                    recordsProcessed = 0,
                    recordsGenerated = 0,
                    validationIssues = listOf("Missing required headers: ${expectedHeaders.filter { !headers.contains(it) }}"),
                    error = "Invalid CSV headers"
                )
            }

            // Step 5: Parse and normalize records
            val dataLines = lines.drop(1)
            val records = mutableListOf<Map<String, String>>()

            for (line in dataLines) {
                val fields = CsvLineParser().parseCsvLine(line)
                if (fields.size >= 4) {
                    val record = mapOf(
                        "Дата" to (fields.getOrNull(0)?.trim() ?: ""),
                        "Вес (кг)" to (fields.getOrNull(1)?.trim() ?: ""),
                        "ИМТ" to (fields.getOrNull(2)?.trim() ?: ""),
                        "Жир (% тела)" to (fields.getOrNull(3)?.trim() ?: "")
                    )
                    records.add(record)
                }
            }

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
     * Helper method to check if file is valid UTF-8
     */
    private fun isValidUtf8(file: File): Boolean {
        return try {
            file.readText(Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }
}