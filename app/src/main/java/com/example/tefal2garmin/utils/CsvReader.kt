package com.example.tefal2garmin.utils

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.tefal2garmin.utils.CsvLineParser

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
        val parser = CsvLineParser()
        return parser.detectAndMergeMultilineRecords(lines)
    }
}