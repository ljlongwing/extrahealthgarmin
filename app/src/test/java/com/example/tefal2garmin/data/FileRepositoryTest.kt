package com.example.tefal2garmin.data

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the skip-and-count contract of FileRepository.processCsvFile: rows that
 * fail the Garmin schema are skipped and reported via recordsSkipped instead of
 * aborting the whole export.
 *
 * Mockito cannot instrument android.jar classes on the current JDK and
 * ContentResolver.openInputStream is final, so the content is supplied through
 * a CsvReader subclass. openInputStream returns null in unit tests
 * (testOptions.unitTests.returnDefaultValues), which skips the encoding
 * pre-check — that check is covered directly in EndToEndIntegrationTest.
 */
class FileRepositoryTest {

    // Full 15-column primary Goodvibes header, as exported by the Tefal app
    private val primaryHeaders = "Время измерения,Вес(кг),ИМТ,Пульс(bpm),Процент жира(%),Масса тела без жира(кг),Подкожный жир(%),Висцеральный жир,Вода в организме(%),Скелетные мышцы(%),Мышечная масса(кг),Костная масса(кг),Белок(%),Величина основного обмена(kcal),Метаболический возраст"

    /** Builds one full 15-column data row; date/weight/bmi/fat are the mapped columns. */
    private fun goodvibesRow(date: String, weight: String, bmi: String, fat: String): String {
        return listOf(
            date, weight, bmi, "88.00", fat, "62.20", "19.40", "7.00", "56.40",
            "44.50", "59.10", "3.11", "17.80", "1713.00", "52.00"
        ).joinToString(",")
    }

    /**
     * A test-only Uri (see android.net.TestUri): no framework Uri instance can
     * exist in unit tests, and processCsvFile never dereferences the Uri — it
     * is passed only to the stubbed ContentResolver and the fake CsvReader.
     */
    private val testUri: Uri = android.net.TestUri()

    private fun buildRepository(rows: List<String>): FileRepository {
        // Abstract class with no abstract members: an empty anonymous subclass is
        // the simplest constructible instance; openInputStream stays the final
        // stub returning null (see class KDoc)
        val resolver = object : ContentResolver(null) {}
        val lines = listOf(primaryHeaders) + rows
        val csvReader = object : CsvReader(resolver) {
            override fun readCsvFile(uri: Uri): List<String> = lines
        }
        // Context is unused by processCsvFile; ContextWrapper is the simplest
        // concrete Context subclass constructible without Android instrumentation
        val context: Context = ContextWrapper(null)
        return FileRepository(resolver, csvReader, CsvValidator(), context)
    }

    @Test
    fun testProcessCsvFile_AllValidRows_GeneratesAll() {
        val repository = buildRepository(
            listOf(
                goodvibesRow("\"окт. 25, 2025 09:51:52\"", "79.65", "24.60", "21.90"),
                goodvibesRow("\"окт. 21, 2025 08:20:53\"", "80.25", "24.80", "21.70")
            )
        )

        val result = runBlocking { repository.processCsvFile(testUri) { } }

        assertTrue("Processing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("All rows should be processed", 2, payload.recordsProcessed)
        assertEquals("All rows should be generated", 2, payload.recordsGenerated)
        assertEquals("No rows should be skipped", 0, payload.recordsSkipped)
        assertTrue(
            "CSV data should start with the Garmin header block",
            payload.csvData!!.startsWith("Body\nDate,Weight,Fat,BMI\n")
        )
        assertTrue(
            "First row should follow the Date,Weight,Fat,BMI order",
            payload.csvData.contains("2025-10-25,79.65,21.90,24.60")
        )
    }

    @Test
    fun testProcessCsvFile_BlankOptionalRow_SkippedButNotFatal() {
        // The Garmin schema requires Fat, so a row where the scale did not measure
        // fat is skipped — but it must not abort the whole export
        val repository = buildRepository(
            listOf(
                goodvibesRow("\"окт. 25, 2025 09:51:52\"", "79.65", "24.60", ""),
                goodvibesRow("\"окт. 21, 2025 08:20:53\"", "80.25", "24.80", "21.70")
            )
        )

        val result = runBlocking { repository.processCsvFile(testUri) { } }

        assertTrue(
            "Export should still succeed with one blank-optional row: ${result.exceptionOrNull()}",
            result.isSuccess
        )
        val payload = result.getOrThrow()
        assertEquals("The blank-fat row should be skipped", 1, payload.recordsSkipped)
        assertEquals("Only the good row should be generated", 1, payload.recordsGenerated)
        assertEquals("Both rows were still processed", 2, payload.recordsProcessed)
    }

    @Test
    fun testProcessCsvFile_GarbageWeightRow_SkippedButNotFatal() {
        val repository = buildRepository(
            listOf(
                goodvibesRow("\"окт. 25, 2025 09:51:52\"", "abc", "24.60", "21.90"),
                goodvibesRow("\"окт. 21, 2025 08:20:53\"", "80.25", "24.80", "21.70")
            )
        )

        val result = runBlocking { repository.processCsvFile(testUri) { } }

        assertTrue(
            "Export should still succeed with one garbage row: ${result.exceptionOrNull()}",
            result.isSuccess
        )
        val payload = result.getOrThrow()
        assertEquals("The garbage-weight row should be skipped", 1, payload.recordsSkipped)
        assertEquals("Only the good row should be generated", 1, payload.recordsGenerated)
        assertTrue(
            "The good row must still be present in the output",
            payload.csvData!!.contains("2025-10-21,80.25,21.70,24.80")
        )
    }
}
