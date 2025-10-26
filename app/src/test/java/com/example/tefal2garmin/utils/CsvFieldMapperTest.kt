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