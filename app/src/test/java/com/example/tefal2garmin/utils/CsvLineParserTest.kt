package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*

class CsvLineParserTest {

    private val parser = CsvLineParser()

    // Tests for multiline CSV record parsing

    @Test
    fun testParseCsvLine_SingleLineRecord() {
        val line = "2024-01-15,70.5,23.5,18.2"
        val result = parser.parseCsvLine(line)

        assertEquals(4, result.size)
        assertEquals("2024-01-15", result[0])
        assertEquals("70.5", result[1])
        assertEquals("23.5", result[2])
        assertEquals("18.2", result[3])
    }

    @Test
    fun testParseCsvLine_WithQuotedFields() {
        val line = "\"2024-01-15\",\"70.5\",\"23.5\",\"18.2\""
        val result = parser.parseCsvLine(line)

        assertEquals(4, result.size)
        assertEquals("2024-01-15", result[0])
        assertEquals("70.5", result[1])
        assertEquals("23.5", result[2])
        assertEquals("18.2", result[3])
    }

    @Test
    fun testParseCsvLine_WithEmbeddedCommasInQuotes() {
        val line = "\"Date, with comma\",\"Weight, 70.5\",\"BMI, 23.5\",\"Fat, 18.2\""
        val result = parser.parseCsvLine(line)

        assertEquals(4, result.size)
        assertEquals("Date, with comma", result[0])
        assertEquals("Weight, 70.5", result[1])
        assertEquals("BMI, 23.5", result[2])
        assertEquals("Fat, 18.2", result[3])
    }

    @Test
    fun testParseCsvLine_WithNewlinesInQuotedFields() {
        val line = "\"Date\nwith newline\",\"Weight\n70.5\",\"BMI\n23.5\",\"Fat\n18.2\""
        val result = parser.parseCsvLine(line)

        assertEquals(4, result.size)
        assertEquals("Date\nwith newline", result[0])
        assertEquals("Weight\n70.5", result[1])
        assertEquals("BMI\n23.5", result[2])
        assertEquals("Fat\n18.2", result[3])
    }

    @Test
    fun testParseCsvLine_WithEscapedQuotes() {
        val line = "\"Field with \"\"escaped\"\" quotes\",\"Normal field\""
        val result = parser.parseCsvLine(line)

        assertEquals(2, result.size)
        assertEquals("Field with \"escaped\" quotes", result[0])
        assertEquals("Normal field", result[1])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_NoMultilineIssues() {
        val lines = listOf(
            "2024-01-15,70.5,23.5,18.2",
            "2024-01-16,71.0,23.8,18.5"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(2, result.size)
        assertEquals(4, result[0].size)
        assertEquals(4, result[1].size)
        assertEquals("2024-01-15", result[0][0])
        assertEquals("2024-01-16", result[1][0])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_WithIncompleteQuotedField() {
        val lines = listOf(
            "\"Incomplete quoted field",
            "continuation of field\",70.5,23.5,18.2"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(1, result.size)
        assertEquals(4, result[0].size)
        assertEquals("Incomplete quoted field\ncontinuation of field", result[0][0])
        assertEquals("70.5", result[0][1])
        assertEquals("23.5", result[0][2])
        assertEquals("18.2", result[0][3])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_WithEmbeddedNewlinesInQuotes() {
        val lines = listOf(
            "\"Date",
            "with",
            "multiple",
            "lines\",70.5,23.5,18.2"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(1, result.size)
        assertEquals(4, result[0].size)
        assertEquals("Date\nwith\nmultiple\nlines", result[0][0])
        assertEquals("70.5", result[0][1])
        assertEquals("23.5", result[0][2])
        assertEquals("18.2", result[0][3])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_MixedMultilineAndSingleLine() {
        val lines = listOf(
            "\"Multiline",
            "record\",70.5,23.5,18.2",
            "2024-01-16,71.0,23.8,18.5",
            "\"Another",
            "multiline",
            "entry\",72.0,24.0,19.0"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(3, result.size)

        // First record (multiline)
        assertEquals(4, result[0].size)
        assertEquals("Multiline\nrecord", result[0][0])
        assertEquals("70.5", result[0][1])

        // Second record (single line)
        assertEquals(4, result[1].size)
        assertEquals("2024-01-16", result[1][0])
        assertEquals("71.0", result[1][1])

        // Third record (multiline)
        assertEquals(4, result[2].size)
        assertEquals("Another\nmultiline\nentry", result[2][0])
        assertEquals("72.0", result[2][1])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_WithEmptyLines() {
        val lines = listOf(
            "",
            "\"Multiline",
            "record\",70.5,23.5,18.2",
            "",
            "2024-01-16,71.0,23.8,18.5"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(2, result.size)
        assertEquals(4, result[0].size)
        assertEquals("Multiline\nrecord", result[0][0])
        assertEquals(4, result[1].size)
        assertEquals("2024-01-16", result[1][0])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_ComplexQuotedFields() {
        val lines = listOf(
            "\"Field with \"\"quotes\"\" and",
            "newlines\",\"Second field",
            "continuation\",Third field,Fourth field"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(1, result.size)
        assertEquals(4, result[0].size)
        assertEquals("Field with \"quotes\" and\nnewlines", result[0][0])
        assertEquals("Second field\ncontinuation", result[0][1])
        assertEquals("Third field", result[0][2])
        assertEquals("Fourth field", result[0][3])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_IncompleteRecordAtEnd() {
        val lines = listOf(
            "2024-01-15,70.5,23.5,18.2",
            "\"Incomplete at end"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals(2, result.size)
        assertEquals(4, result[0].size)
        assertEquals("2024-01-15", result[0][0])

        // The incomplete record should be handled gracefully
        assertEquals(1, result[1].size)
        assertEquals("Incomplete at end", result[1][0])
    }

    @Test
    fun testMergeMultilineRecord_SimpleMerge() {
        val lines = listOf(
            "\"First part",
            "second part\",value2,value3"
        )

        val mergeResult = parser.mergeMultilineRecord(lines, 0)

        assertNotNull(mergeResult.mergedRecords)
        assertEquals(1, mergeResult.mergedRecords.size)
        assertEquals(3, mergeResult.mergedRecords[0].size)
        assertEquals("First part\nsecond part", mergeResult.mergedRecords[0][0])
        assertEquals("value2", mergeResult.mergedRecords[0][1])
        assertEquals("value3", mergeResult.mergedRecords[0][2])
        assertEquals(2, mergeResult.nextIndex)
    }

    @Test
    fun testMergeMultilineRecord_NoMergeNeeded() {
        val lines = listOf(
            "complete,line,with,commas",
            "another,complete,line"
        )

        val mergeResult = parser.mergeMultilineRecord(lines, 0)

        assertNotNull(mergeResult.mergedRecords)
        assertEquals(2, mergeResult.mergedRecords.size)

        // First record
        assertEquals(4, mergeResult.mergedRecords[0].size)
        assertEquals("complete", mergeResult.mergedRecords[0][0])
        assertEquals("line", mergeResult.mergedRecords[0][1])
        assertEquals("with", mergeResult.mergedRecords[0][2])
        assertEquals("commas", mergeResult.mergedRecords[0][3])

        // Second record
        assertEquals(3, mergeResult.mergedRecords[1].size)
        assertEquals("another", mergeResult.mergedRecords[1][0])
        assertEquals("complete", mergeResult.mergedRecords[1][1])
        assertEquals("line", mergeResult.mergedRecords[1][2])

        assertEquals(2, mergeResult.nextIndex)
    }

    @Test
    fun testDetectAndMergeMultilineRecords_EscapedQuotesDoNotTriggerMerge() {
        // A single complete line: the field's surrounding quotes plus an escaped
        // "" pair give an even quote count, so nothing must be merged
        val lines = listOf("\"a \"\"b\"\" c\",1,2,3")

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals("One record, no merge expected", 1, result.size)
        assertEquals("Record should have 4 fields", 4, result[0].size)
        assertEquals("a \"b\" c", result[0][0])
        assertEquals("1", result[0][1])
        assertEquals("2", result[0][2])
        assertEquals("3", result[0][3])
    }

    @Test
    fun testDetectAndMergeMultilineRecords_StrayQuoteSwallowsNextLine() {
        // Pins CURRENT behavior: a stray quote inside an unquoted field leaves
        // odd quote parity, so the parser treats following lines as continuations
        // and the next physically valid line is merged into the same record
        // (everything after the stray quote lands in one quoted field). Changing
        // these semantics to proper RFC 4180 field tracking is out of scope here.
        val lines = listOf(
            "155.5,23.5,he said \"hi,18.2",
            "2024-01-16,71.0,23.8,18.5"
        )

        val result = parser.detectAndMergeMultilineRecords(lines)

        assertEquals("Stray-quote line merges with the next line into one record", 1, result.size)
        assertEquals(3, result[0].size)
        assertEquals("155.5", result[0][0])
        assertEquals("23.5", result[0][1])
        assertTrue(
            "The next line should be swallowed into the quoted field: ${result[0][2]}",
            result[0][2].contains("2024-01-16")
        )
    }
}