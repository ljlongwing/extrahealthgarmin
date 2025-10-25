package com.example.tefal2garmin.utils

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class CsvReader(private val contentResolver: ContentResolver) {
    
    companion object {
        private const val TAG = "CsvReader"
    }
    
    fun readCsvFile(uri: Uri): List<String> {
        val lines = mutableListOf<String>()
        
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { lines.add(it) }
                    }
                }
            }
            Log.d(TAG, "Successfully read ${lines.size} lines from CSV file")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CSV file", e)
            throw e
        }
        
        return lines
    }
    
    fun parseCsvLines(lines: List<String>): List<List<String>> {
        val parsedData = mutableListOf<List<String>>()
        
        for (line in lines) {
            try {
                val fields = parseCsvLine(line)
                parsedData.add(fields)
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing line: $line", e)
                // Continue processing other lines even if one fails
            }
        }
        
        Log.d(TAG, "Successfully parsed ${parsedData.size} CSV records")
        return parsedData
    }
    
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            when {
                !inQuotes && line[i] == '"' -> {
                    // Start of quoted field
                    inQuotes = true
                }
                inQuotes && line[i] == '"' -> {
                    // End of quoted field, check for escaped quote
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote, add to current field
                        currentField.append('"')
                        i++ // Skip the escaped quote
                    } else {
                        // End of quoted field
                        inQuotes = false
                    }
                }
                !inQuotes && line[i] == ',' -> {
                    // Field separator
                    fields.add(currentField.toString().trim())
                    currentField = StringBuilder()
                }
                else -> {
                    currentField.append(line[i])
                }
            }
            i++
        }
        
        // Add the last field
        fields.add(currentField.toString().trim())
        
        return fields
    }
}