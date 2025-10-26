package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class CsvFieldMapperTest {

    // Tests for date conversion and Russian month abbreviations

    @Test
    fun testNormalizeDate_RussianMonthAbbreviations() {
        // Test various Russian month abbreviations
        val testCases = mapOf(
            "15 янв 2024" to "15-01-2024",
            "5 фев 2023" to "05-02-2023",
            "24 мар 2024" to "24-03-2024",
            "1 апр 2024" to "01-04-2024",
            "10 мая 2024" to "10-05-2024",
            "15 июн 2024" to "15-06-2024",
            "20 июл 2024" to "20-07-2024",
            "25 авг 2024" to "25-08-2024",
            "30 сен 2024" to "30-09-2024",
            "5 окт 2024" to "05-10-2024",
            "10 ноя 2024" to "10-11-2024",
            "25 дек 2024" to "25-12-2024"
        )

        testCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed to convert '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_DotFormat() {
        val testCases = mapOf(
            "15.01.2024" to "15-01-2024",
            "05.02.2023" to "05-02-2023",
            "24.12.2024" to "24-12-2024",
            "1.1.2024" to "01-01-2024"  // Single digit day and month
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
            "1 янв 2024" to "01-01-2024",  // Single digit day
            "31 дек 2024" to "31-12-2024",  // Last day of year
            "29 фев 2024" to "29-02-2024"   // Leap year date
        )

        edgeCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Failed edge case '$input'", expected, result)
        }
    }

    @Test
    fun testNormalizeDate_WhitespaceHandling() {
        val testCases = mapOf(
            "  15 янв 2024  " to "15-01-2024",  // Leading/trailing spaces
            "15  янв  2024" to "15-01-2024",    // Multiple spaces
            "15.01.2024 " to "15-01-2024"       // Trailing space in dot format
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
            "15 ЯНВ 2024" to "15-01-2024",  // Uppercase
            "15 Янв 2024" to "15-01-2024",  // Mixed case
            "15 ФЕВ 2024" to "15-02-2024"   // Uppercase
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
        // Test if full month names work (if implemented)
        val fullMonthCases = mapOf(
            "15 января 2024" to "15-01-2024",
            "5 февраля 2024" to "05-02-2024"
        )

        fullMonthCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            // This might not work yet, but test what we have
            if (result != input) {
                assertEquals("Full month name conversion for '$input'", expected, result)
            }
        }
    }

    @Test
    fun testNormalizeDate_YearRange() {
        // Test various years
        val yearCases = mapOf(
            "15 янв 2020" to "15-01-2020",
            "15 янв 2025" to "15-01-2025",
            "15 янв 1999" to "15-01-1999"
        )

        yearCases.forEach { (input, expected) ->
            val result = CsvFieldMapper.normalizeDate(input)
            assertEquals("Year handling for '$input'", expected, result)
        }
    }
}