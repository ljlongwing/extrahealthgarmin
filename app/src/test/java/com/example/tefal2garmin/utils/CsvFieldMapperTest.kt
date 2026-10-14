package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class CsvFieldMapperTest {

    // Tests for date conversion and Russian month abbreviations

    @Test
    fun testNormalizeDate_RussianMonthAbbreviations() {
        // Test various Russian month abbreviations
        val testCases = mapOf(
            "15 янв 2024" to "2024-01-15",
            "5 фев 2023" to "2023-02-05",
            "24 мар 2024" to "2024-03-24",
            "1 апр 2024" to "2024-04-01",
            "10 мая 2024" to "2024-05-10",
            "15 июн 2024" to "2024-06-15",
            "20 июл 2024" to "2024-07-20",
            "25 авг 2024" to "2024-08-25",
            "30 сен 2024" to "2024-09-30",
            "5 окт 2024" to "2024-10-05",
            "10 ноя 2024" to "2024-11-10",
            "25 дек 2024" to "2024-12-25"
        )

        testCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed to convert '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_DotFormat() {
        val testCases = mapOf(
            "15.01.2024" to "2024-01-15",
            "05.02.2023" to "2023-02-05",
            "24.12.2024" to "2024-12-24",
            "1.1.2024" to "2024-01-01"  // Single digit day and month
        )

        testCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed to convert '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_InvalidFormats() {
        val invalidDates = listOf(
            "invalid-date",
            "2024-13-45",  // Invalid month/day
            "not-a-date",
            "31 фев 2024",  // Invalid date (Feb 31st)
            "abc.def.ghi"
        )

        invalidDates.forEach { input ->
            val result = CsvFieldMapper.normalizeDate(input)
            // Invalid dates should be returned as-is (fallback behavior)
            assertEquals("Invalid date should be returned unchanged", input, result)
        }
    }

    @Test
    fun testNormalizeDate_EdgeCases() {
        // Test edge cases
        val edgeCases = mapOf(
            "1 янв 2024" to "2024-01-01",  // Single digit day
            "31 дек 2024" to "2024-12-31",  // Last day of year
            "29 фев 2024" to "2024-02-29"   // Leap year date
        )

        edgeCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed edge case '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_WhitespaceHandling() {
        val testCases = mapOf(
            "  15 янв 2024  " to "2024-01-15",  // Leading/trailing spaces
            "15  янв  2024" to "2024-01-15",    // Multiple spaces
            "15.01.2024 " to "2024-01-15"       // Trailing space in dot format
        )

        testCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed whitespace handling for '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_CaseInsensitivity() {
        // Russian month abbreviations should be case insensitive
        val testCases = mapOf(
            "15 ЯНВ 2024" to "2024-01-15",  // Uppercase
            "15 Янв 2024" to "2024-01-15",  // Mixed case
            "15 ФЕВ 2024" to "2024-02-15"   // Uppercase
        )

        testCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed case insensitivity for '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_MalformedRussianDates() {
        val malformedDates = listOf(
            "янв 2024",        // Missing day
            "15 2024",         // Missing month
            "15 янв",          // Missing year
            "15 xyz 2024",     // Invalid month
            "15 янв 2024 extra" // Extra content
        )

        malformedDates.forEach { input ->
            val result = CsvFieldMapper.normalizeDate(input)
            // Malformed dates should be returned as-is
            assertEquals("Malformed date should be returned unchanged", input, result)
        }
    }

    @Test
    fun testNormalizeDate_FullRussianMonthNames() {
        // Current behavior: full (genitive) month names in "DD месяца YYYY" form
        // are NOT supported — only the 3-letter tokens are — so they pass through
        // unchanged. This pins that behavior explicitly.
        val fullMonthCases = listOf("15 января 2024", "5 февраля 2024")

        fullMonthCases.forEach { input ->
            assertEquals(
                "Full month name should pass through unchanged",
                input,
                CsvFieldMapper.normalizeDate(input)
            )
        }
    }

    @Test
    fun testNormalizeDate_PrimaryGoodvibesMonthTokens() {
        // All 12 month tokens of the primary Goodvibes format, including the
        // longer CLDR short forms (февр., сент., нояб.) and dot-less "мая"
        val testCases = mapOf(
            "янв. 5, 2025 09:00:00" to "2025-01-05",
            "февр. 15, 2025 10:00:00" to "2025-02-15",
            "мар. 10, 2025" to "2025-03-10",
            "апр. 10, 2025" to "2025-04-10",
            "мая 10, 2025" to "2025-05-10",
            "июн. 10, 2025" to "2025-06-10",
            "июл. 10, 2025" to "2025-07-10",
            "авг. 10, 2025" to "2025-08-10",
            "сент. 10, 2025" to "2025-09-10",
            "окт. 25, 2025 09:51:52" to "2025-10-25",
            "нояб. 10, 2025" to "2025-11-10",
            "дек. 25, 2025" to "2025-12-25"
        )

        testCases.forEach { (input, expected) ->
            assertEquals("Failed primary-format token '$input'", expected, CsvFieldMapper.normalizeDate(input))
        }
    }

    @Test
    fun testNormalizeDate_PrimaryFormatInvalidDates() {
        // Feb 30 does not exist even in the leap year 2024 -> unchanged
        assertEquals("февр. 30, 2024", CsvFieldMapper.normalizeDate("февр. 30, 2024"))
        // 2-digit year does not satisfy the required \d{4} -> unchanged
        assertEquals("окт. 25, 25", CsvFieldMapper.normalizeDate("окт. 25, 25"))
    }

    @Test
    fun testNormalizeDate_PrimaryFormatCaseInsensitive() {
        assertEquals("2025-10-25", CsvFieldMapper.normalizeDate("ОКТ. 25, 2025"))
    }

    @Test
    fun testNormalizeDate_NominativeMonthUnsupported() {
        // Nominative "май" is not part of the (genitive) month token set -> unchanged
        assertEquals("25 май 2025", CsvFieldMapper.normalizeDate("25 май 2025"))
    }

    @Test
    fun testNormalizeDate_YearRange() {
        // Test various years
        val yearCases = mapOf(
            "15 янв 2020" to "2020-01-15",
            "15 янв 2025" to "2025-01-15",
            "15 янв 1999" to "1999-01-15"
        )

        yearCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Year handling for '$input'", expected, result)
        }
    }
}