package com.example.tefal2garmin.utils

import android.util.Log
import java.util.regex.Pattern

class CsvLineParser {
    
    companion object {
        private const val TAG = "CsvLineParser"
        
        // Pattern to match CSV fields including quoted fields
        private val CSV_FIELD_PATTERN = Pattern.compile(
            // Match either: unquoted field, or quoted field (which may contain commas, quotes, and newlines)
            "(?:^([^\",\\n\\r]*)(?=,|$)|\"((?:[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)*)\"(?=,|$))"
        )
    }
    
    fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var currentIndex = 0
        var inQuotes = false
        
        while (currentIndex < line.length) {
            when {
                !inQuotes && line[currentIndex] == '"' -> {
                    // Start of quoted field
                    inQuotes = true
                    currentIndex++
                }
                inQuotes && line[currentIndex] == '"' -> {
                    // Check for escaped quote
                    if (currentIndex + 1 < line.length && line[currentIndex + 1] == '"') {
                        // Escaped quote, add both to field and skip both
                        fields.add(if (fields.size > currentIndex) fields[currentIndex] + "\"" else "")
                        fields[fields.size] = (fields[fields.size] ?: "") + "\"\""
                        currentIndex += 2
                    } else {
                        // End of quoted field
                        inQuotes = false
                        currentIndex++
                    }
                }
                !inQuotes && line[currentIndex] == ',' -> {
                    // Field separator
                    fields.add(if (fields.size > currentIndex) fields[currentIndex] + "," else "")
                    currentIndex++
                }
                inQuotes || line[currentIndex] != ',' -> {
                    // Regular character within field or quoted field
                    val currentField = if (fields.size > currentIndex) fields[currentIndex] + line[currentIndex] else line[currentIndex].toString()
                    fields[fields.size] = currentField
                    currentIndex++
                }
                else -> {
                    // Unexpected state
                    Log.w(TAG, "Unexpected parsing state at index $currentIndex in line: $line")
                    currentIndex++
                }
            }
        }
        
        // Add the last field if we have one
        if (fields.isNotEmpty() || currentIndex > 0) {
            val lastFieldStart = if (fields.size > 0) fields.size else 0
            val remainingText = line.substring(lastFieldStart).trim()
            if (remainingText.isNotEmpty()) {
                fields.add(remainingText)
            }
        }
        
        return fields.map { it.trim() }
    }
    
    fun mergeMultilineRecord(lines: List<String>, startIndex: Int): MergeResult {
        if (startIndex >= lines.size) {
            return MergeResult(emptyList(), startIndex, 0, "Start index beyond lines array")
        }
        
        val mergedLines = mutableListOf<String>()
        var currentRecord = mutableListOf<String>()
        var i = startIndex
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            if (line.isEmpty()) {
                i++
                continue
            }
            
            val fields = parseCsvLine(line)
            currentRecord.addAll(fields)
            
            // Check if this line completes the record
            // A complete Tefal record should have 9-10 fields
            if (fields.size >= 8) {
                // This line likely completes a record
                val mergedRecord = currentRecord.toList()
                mergedLines.add(mergedRecord.joinToString(","))
                currentRecord.clear()
                i++
            } else {
                // This line continues the record
                i++
            }
        }
        
        // Add any remaining fields
        if (currentRecord.isNotEmpty()) {
            mergedLines.add(currentRecord.joinToString(","))
        }
        
        return MergeResult(mergedLines, i, 0, null)
    }
    
    fun detectAndMergeMultilineRecords(lines: List<String>): List<List<String>> {
        val detector = MultilineCsvDetector()
        val detectionResult = detector.detectMultilineIssues(lines)
        
        if (!detectionResult.hasIssues) {
            // No issues detected, parse normally
            return lines.map { parseCsvLine(it) }
        }
        
        // Apply merging based on detected issues
        val allRecords = mutableListOf<List<String>>()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            if (line.isEmpty()) {
                i++
                continue
            }
            
            // Simple heuristic: if line has very few commas, it might be continuation
            val fields = parseCsvLine(line)
            val shouldMergeWithNext = i + 1 < lines.size && 
                (fields.size < 5 || // Tefal should have ~9-10 fields
                lines[i + 1].count { it == ',' } < fields.size / 2)
            
            if (shouldMergeWithNext) {
                val mergeResult = mergeMultilineRecord(lines, i)
                allRecords.add(mergeResult.mergedLines)
                i = mergeResult.nextIndex
            } else {
                // This line is a complete record
                allRecords.add(fields)
                i++
            }
        }
        
        Log.d(TAG, "Detected and merged ${detectionResult.issues.size} multiline issues")
        Log.d(TAG, "Processed ${allRecords.size} total records")
        
        return allRecords
    }
    
    data class MergeResult(
        val mergedLines: List<String>,
        val nextIndex: Int,
        val mergedCount: Int,
        val error: String?
    )
}