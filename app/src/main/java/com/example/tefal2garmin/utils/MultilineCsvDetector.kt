package com.example.tefal2garmin.utils

import android.util.Log
import java.util.regex.Pattern

class MultilineCsvDetector {
    
    companion object {
        private const val TAG = "MultilineCsvDetector"
        
        // Pattern to detect potential multiline record issues
        private val MULTILINE_INDICATORS = listOf(
            // Unmatched quotes
            Pattern.compile("""(?=(?:[^"]*"[^"]*")*[^"]*""[^"]*)*(?![^"]*$)"""),
            // Incomplete quoted fields
            Pattern.compile("""(?:[^"]*"[^"]*$)"""),
            // Suspicious character sequences
            Pattern.compile("""[\r\n]{2,}"""),
            // Fields with newlines without quotes
            Pattern.compile("""[^",\r\n]+[,\r\n]"""),
            // Uneven quote count
            Pattern.compile("""".*"[^"]*""[^"]*""")
        )
    }
    
    fun detectMultilineIssues(lines: List<String>): MultilineDetectionResult {
        val issues = mutableListOf<MultilineIssue>()
        var lineNumber = 1
        
        for (line in lines) {
            for (pattern in MULTILINE_INDICATORS) {
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    issues.add(
                        MultilineIssue(
                            lineNumber = lineNumber,
                            issueType = when {
                                MULTILINE_INDICATORS.indexOf(pattern) == 0 -> "Unmatched quotes"
                                MULTILINE_INDICATORS.indexOf(pattern) == 1 -> "Incomplete quoted field"
                                MULTILINE_INDICATORS.indexOf(pattern) == 2 -> "Multiple consecutive newlines"
                                MULTILINE_INDICATORS.indexOf(pattern) == 3 -> "Newlines in unquoted field"
                                MULTILINE_INDICATORS.indexOf(pattern) == 4 -> "Uneven quote count"
                                else -> "Unknown multiline issue"
                            },
                            description = "Line ${lineNumber}: ${matcher.group()}",
                            suggestedFix = when {
                                MULTILINE_INDICATORS.indexOf(pattern) == 0 -> "Check for missing closing quotes"
                                MULTILINE_INDICATORS.indexOf(pattern) == 1 -> "Ensure quoted fields are properly closed"
                                MULTILINE_INDICATORS.indexOf(pattern) == 2 -> "Remove excessive newlines within fields"
                                MULTILINE_INDICATORS.indexOf(pattern) == 3 -> "Quote fields containing newlines should be properly quoted"
                                MULTILINE_INDICATORS.indexOf(pattern) == 4 -> "Balance opening and closing quotes"
                                else -> "Review CSV formatting"
                            }
                        )
                    )
                }
            }
            lineNumber++
        }
        
        val hasIssues = issues.isNotEmpty()
        
        if (hasIssues) {
            Log.w(TAG, "Detected ${issues.size} potential multiline issues")
            issues.forEach { issue ->
                Log.w(TAG, "${issue.issueType}: ${issue.description}")
            }
        } else {
            Log.d(TAG, "No multiline issues detected")
        }
        
        return MultilineDetectionResult(hasIssues, issues)
    }
    
    fun shouldMergeWithNextLine(currentLine: String, nextLine: String): Boolean {
        // Heuristic to determine if current line should be merged with next
        return try {
            val quoteCount = currentLine.count { it == '"' }
            
            // If line ends with incomplete quote, it likely continues to next line
            if (quoteCount % 2 == 1 && currentLine.trim().endsWith("\"")) {
                true
            } else {
                // Count commas vs expected fields (rough heuristic)
                val expectedFieldCount = 9 // Based on Tefal CSV structure
                val actualCommas = currentLine.count { it == ',' }
                
                // If very few commas but seems incomplete, likely multiline
                actualCommas < (expectedFieldCount / 2) && !currentLine.trim().endsWith("\"")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in multiline detection heuristic", e)
            false
        }
    }
    
    data class MultilineDetectionResult(
        val hasIssues: Boolean,
        val issues: List<MultilineIssue>
    )
    
    data class MultilineIssue(
        val lineNumber: Int,
        val issueType: String,
        val description: String,
        val suggestedFix: String
    )
}