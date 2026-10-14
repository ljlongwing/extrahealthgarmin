package com.example.tefal2garmin.utils

class CsvLineParser {

    fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val currentField = StringBuilder()
        var currentIndex = 0
        var inQuotes = false

        while (currentIndex < line.length) {
            when (line[currentIndex]) {
                '"' -> {
                    if (inQuotes && currentIndex + 1 < line.length && line[currentIndex + 1] == '"') {
                        // Escaped quote within quoted field
                        currentField.append('"')
                        currentIndex += 2
                    } else {
                        // Toggle quote state
                        inQuotes = !inQuotes
                        currentIndex++
                    }
                }
                ',' -> {
                    if (inQuotes) {
                        // Comma within quoted field
                        currentField.append(',')
                    } else {
                        // Field separator
                        fields.add(currentField.toString())
                        currentField.clear()
                    }
                    currentIndex++
                }
                else -> {
                    // Regular character
                    currentField.append(line[currentIndex])
                    currentIndex++
                }
            }
        }

        // Add the last field
        fields.add(currentField.toString())

        return fields
    }
    
    fun mergeMultilineRecord(lines: List<String>, startIndex: Int): MergeResult {
        if (startIndex >= lines.size) {
            return MergeResult(emptyList(), startIndex, 0, "Start index beyond lines array")
        }

        val mergedRecords = mutableListOf<List<String>>()
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.isEmpty()) {
                i++
                continue
            }

            // Check if this line starts a multiline record (unclosed quotes)
            val quoteCount = line.count { it == '"' }
            val hasUnclosedQuote = quoteCount % 2 == 1

            if (hasUnclosedQuote) {
                // This is the start of a multiline record, merge until we find the closing quote
                val mergedLine = StringBuilder(line)
                // Running quote count: only count quotes in each newly appended line
                // instead of rescanning the whole buffer, keeping the merge linear
                var quoteCount = line.count { it == '"' }
                val startMergeIndex = i
                i++

                while (i < lines.size) {
                    val nextLine = lines[i]
                    mergedLine.append("\n").append(nextLine)
                    quoteCount += nextLine.count { it == '"' }
                    if (quoteCount % 2 == 0) {
                        // Found the closing quote
                        break
                    }
                    i++
                }

                // Parse the merged line
                val fields = parseCsvLine(mergedLine.toString())
                mergedRecords.add(fields)
                i++
            } else {
                // This line is a complete record
                val fields = parseCsvLine(line)
                mergedRecords.add(fields)
                i++
            }
        }

        return MergeResult(mergedRecords, i, mergedRecords.size, null)
    }
    
    fun detectAndMergeMultilineRecords(lines: List<String>): List<List<String>> {
        // Simple implementation: check for unclosed quotes to detect multiline records
        val allRecords = mutableListOf<List<String>>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.isEmpty()) {
                i++
                continue
            }

            // Check if this line starts a multiline record (unclosed quotes)
            val quoteCount = line.count { it == '"' }
            val hasUnclosedQuote = quoteCount % 2 == 1

            if (hasUnclosedQuote) {
                // This is the start of a multiline record, merge until we find the closing quote
                val mergedLine = StringBuilder(line)
                // Running quote count: only count quotes in each newly appended line
                // instead of rescanning the whole buffer, keeping the merge linear
                var quoteCount = line.count { it == '"' }
                i++

                while (i < lines.size) {
                    val nextLine = lines[i]
                    mergedLine.append("\n").append(nextLine)
                    quoteCount += nextLine.count { it == '"' }
                    if (quoteCount % 2 == 0) {
                        // Found the closing quote
                        break
                    }
                    i++
                }

                // Parse the merged line
                val fields = parseCsvLine(mergedLine.toString())
                allRecords.add(fields)
                i++
            } else {
                // This line is a complete record
                val fields = parseCsvLine(line)
                allRecords.add(fields)
                i++
            }
        }

        return allRecords
    }
    
    data class MergeResult(
        val mergedRecords: List<List<String>>,
        val nextIndex: Int,
        val mergedCount: Int,
        val error: String?
    )
}