package com.example.tefal2garmin

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import android.provider.OpenableColumns
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator

class MainActivity : AppCompatActivity() {
    
    private lateinit var textView: TextView
    private var sharedFileUri: Uri? = null
    private lateinit var csvReader: CsvReader
    private lateinit var csvValidator: CsvValidator
    
    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        textView = findViewById(R.id.textView)
        csvReader = CsvReader(contentResolver)
        csvValidator = CsvValidator()
        
        // Handle share intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
                
                if (sharedUri != null) {
                    // Check permissions before processing
                    if (checkAndRequestPermissions()) {
                        // Store URI for later processing
                        sharedFileUri = sharedUri
                        val message = "CSV file received: ${getFileName(sharedUri)}"
                        textView.text = message
                        Log.d(TAG, "Received CSV file URI: $sharedUri")
                        
                        // Start processing and validating file
                        processReceivedFile(sharedUri)
                    }
                } else if (sharedText != null) {
                    val message = "CSV content shared via TEXT: ${sharedText.take(100)}${if (sharedText.length > 100) "..." else ""}"
                    textView.text = message
                    Log.d(TAG, "Received CSV text content")
                } else {
                    textView.text = "Welcome to Tefal2Garmin Sync"
                }
            }
            else -> {
                textView.text = "Welcome to Tefal2Garmin Sync"
            }
        }
    }
    
    private fun checkAndRequestPermissions(): Boolean {
        // For Android 13+ (API 33+) no storage permission needed for content URIs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        
        // For older versions, check READ_EXTERNAL_STORAGE permission
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        val hasPermission = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        
        return if (!hasPermission) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(permission),
                STORAGE_PERMISSION_REQUEST_CODE
            )
            false // Will be handled in onRequestPermissionsResult
        } else {
            true
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, process file if we have a URI
                    sharedFileUri?.let { uri ->
                        processReceivedFile(uri)
                    }
                } else {
                    // Permission denied
                    Toast.makeText(
                        this,
                        "Storage permission is required to read CSV files",
                        Toast.LENGTH_LONG
                    ).show()
                    textView.text = "Permission denied. Cannot process file."
                }
            }
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var fileName = uri.lastPathSegment ?: "unknown_file.csv"
        
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve file name from ContentResolver", e)
        }
        
        return fileName
    }
    
    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve file size from ContentResolver", e)
        }
        
        return size
    }
    
    private fun processReceivedFile(uri: Uri) {
        try {
            Log.d(TAG, "Starting to process received file: $uri")
            
            val fileName = getFileName(uri)
            val fileSize = getFileSize(uri)
            
            Log.d(TAG, "File name: $fileName, size: $fileSize bytes")
            
            // Open input stream and validate encoding
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // Validate file encoding
                val encodingResult = csvValidator.validateEncoding(inputStream)
                if (!encodingResult.isValid) {
                    handleError("Encoding Error", encodingResult.errorMessage ?: "Invalid file encoding")
                    return@use
                }
                
                // Reset stream to read from beginning
                inputStream.reset()
                
                // Read CSV data
                val lines = csvReader.readCsvFile(uri)
                if (lines.isEmpty()) {
                    handleError("Empty File", "The CSV file is empty")
                    return@use
                }
                
                // Validate delimiter
                val delimiterResult = csvValidator.validateDelimiter(lines)
                if (!delimiterResult.isValid) {
                    handleError("Delimiter Error", delimiterResult.errorMessage ?: "Invalid delimiter")
                    return@use
                }
                
                // Validate headers
                val headerLine = lines[0]
                val headerResult = csvValidator.validateHeaders(headerLine)
                if (!headerResult.isValid) {
                    handleError("Header Error", headerResult.errorMessage ?: "Invalid headers")
                    return@use
                }
                
                val parsedData = csvReader.parseCsvLines(lines)
                val message = "Successfully validated and processed ${parsedData.size} records from $fileName (${fileSize} bytes)"
                textView.text = message
                Log.d(TAG, "File processing and validation completed successfully")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing file", e)
            handleError("Processing Error", "Error processing file: ${e.message}")
        }
    }
    
    private fun handleError(errorType: String, message: String) {
        Log.e(TAG, "$errorType: $message")
        textView.text = "$errorType: $message"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getParcelableExtra(name) as? T
        }
    }
}