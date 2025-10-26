package com.example.tefal2garmin

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Button
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
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator
import com.example.tefal2garmin.utils.MultilineCsvDetector
import com.example.tefal2garmin.utils.RecordProcessor
import com.example.tefal2garmin.utils.GarminCsvGenerator
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    
    // UI Components
    private lateinit var welcomeText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var openGarminButton: Button
    private lateinit var tryAgainButton: Button
    
    // Processing components
    private lateinit var csvReader: CsvReader
    private lateinit var csvValidator: CsvValidator
    private lateinit var multilineDetector: MultilineCsvDetector
    private lateinit var recordProcessor: RecordProcessor
    private lateinit var garminCsvGenerator: GarminCsvGenerator
    
    // State
    private enum class UiState {
        INITIAL, PROCESSING, SUCCESS, ERROR
    }
    
    private var currentState = UiState.INITIAL
    private var sharedFileUri: Uri? = null
    private var processedRecords = 0
    private var generatedRecords = 0
    private var errorMessage: String? = null
    private var outputUri: Uri? = null
    
    // SAF launcher for Downloads folder
    private lateinit var saveFileLauncher: ActivityResultLauncher<String>
    
    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        private const val GARMIN_CONNECT_URL = "https://connect.garmin.com/modern/import-data"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        initializeProcessingComponents()
        initializeSaveFileLauncher()
        
        // Handle share intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun initializeViews() {
        welcomeText = findViewById(R.id.welcomeText)
        instructionsText = findViewById(R.id.instructionsText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        openGarminButton = findViewById(R.id.openGarminButton)
        tryAgainButton = findViewById(R.id.tryAgainButton)
        
        // Set button click listeners
        openGarminButton.setOnClickListener {
            openGarminConnect()
        }
        
        tryAgainButton.setOnClickListener {
            resetToInitialState()
        }
        
        resultText.setOnClickListener {
            if (currentState == UiState.SUCCESS) {
                // Open file picker to save to Downloads
                saveFileLauncher.launch(null)
            }
        }
    }
    
    private fun initializeProcessingComponents() {
        csvReader = CsvReader(contentResolver)
        csvValidator = CsvValidator()
        multilineDetector = MultilineCsvDetector()
        recordProcessor = RecordProcessor()
        garminCsvGenerator = GarminCsvGenerator()
    }
    
    private fun initializeSaveFileLauncher() {
        saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                outputUri = uri
                Log.d(TAG, "Output file saved to: ${outputUri?.toString()}")
                runOnUiThread {
                    resultText.text = "File saved to: ${outputUri?.lastPathSegment ?: "unknown"}"
                }
            } else {
                Log.w(TAG, "User cancelled file save")
            }
        }
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
                        showFileInfo(sharedUri)
                        
                        // Start processing and validating file
                        processReceivedFile(sharedUri)
                    }
                } else if (sharedText != null) {
                    showTextSharedContent(sharedText)
                } else {
                    showInitialState()
                }
            }
            else -> {
                showInitialState()
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
                    showErrorState("Storage permission is required to read CSV files")
                }
            }
        }
    }
    
    private fun showInitialState() {
        currentState = UiState.INITIAL
        welcomeText.visibility = android.view.View.VISIBLE
        instructionsText.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.INVISIBLE
        statusText.visibility = android.view.View.INVISIBLE
        resultText.visibility = android.view.View.INVISIBLE
        openGarminButton.visibility = android.view.View.INVISIBLE
        tryAgainButton.visibility = android.view.View.INVISIBLE
    }
    
    private fun showFileInfo(uri: Uri) {
        val fileName = getFileName(uri)
        val fileSize = getFileSize(uri)
        val message = "Received: $fileName"
        
        welcomeText.text = message
        instructionsText.text = "Validating and processing..."
        statusText.text = "Size: ${formatFileSize(fileSize)}"
        statusText.visibility = android.view.View.VISIBLE
    }
    
    private fun showTextSharedContent(text: String) {
        currentState = UiState.PROCESSING
        welcomeText.text = "CSV content shared"
        instructionsText.text = "Processing text content..."
        statusText.text = "Length: ${text.length} characters"
        statusText.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.VISIBLE
    }
    
    private fun showProcessingState(message: String) {
        currentState = UiState.PROCESSING
        welcomeText.visibility = android.view.View.INVISIBLE
        instructionsText.text = message
        statusText.text = "Processing..."
        statusText.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.VISIBLE
        resultText.visibility = android.view.View.INVISIBLE
        openGarminButton.visibility = android.view.View.INVISIBLE
        tryAgainButton.visibility = android.view.View.INVISIBLE
    }
    
    private fun showSuccessState(recordsProcessed: Int, recordsGenerated: Int) {
        currentState = UiState.SUCCESS
        processedRecords = recordsProcessed
        generatedRecords = recordsGenerated
        
        welcomeText.visibility = android.view.View.INVISIBLE
        instructionsText.text = "Conversion completed successfully!"
        statusText.text = "Processed $recordsProcessed records, generated $recordsGenerated Garmin records"
        statusText.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.INVISIBLE
        resultText.visibility = android.view.View.VISIBLE
        resultText.text = "File saved - click button below to save"
        openGarminButton.visibility = android.view.View.VISIBLE
        tryAgainButton.visibility = android.view.View.VISIBLE
    }
    
    private fun showErrorState(message: String) {
        currentState = UiState.ERROR
        errorMessage = message
        
        welcomeText.visibility = android.view.View.INVISIBLE
        instructionsText.text = "Error occurred"
        statusText.text = message
        statusText.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.INVISIBLE
        resultText.visibility = android.view.View.VISIBLE
        resultText.text = "Please try again"
        openGarminButton.visibility = android.view.View.INVISIBLE
        tryAgainButton.visibility = android.view.View.VISIBLE
    }
    
    private fun resetToInitialState() {
        currentState = UiState.INITIAL
        errorMessage = null
        sharedFileUri = null
        processedRecords = 0
        generatedRecords = 0
        
        welcomeText.text = "Welcome to Tefal2Garmin Sync"
        instructionsText.text = "Share a CSV file from the Tefal Goodvibes app to convert it for Garmin Connect import"
        statusText.visibility = android.view.View.INVISIBLE
        progressBar.visibility = android.view.View.INVISIBLE
        resultText.visibility = android.view.View.INVISIBLE
        openGarminButton.visibility = android.view.View.INVISIBLE
        tryAgainButton.visibility = android.view.View.INVISIBLE
        
        welcomeText.visibility = android.view.View.VISIBLE
        instructionsText.visibility = android.view.View.VISIBLE
    }
    
    private fun processReceivedFile(uri: Uri) {
        showProcessingState("Processing CSV file...")
        
        // Start processing in background to avoid blocking UI thread
        Thread {
            try {
                val fileName = getFileName(uri)
                val fileSize = getFileSize(uri)
                
                Log.d(TAG, "Starting to process received file: $uri")
                Log.d(TAG, "File name: $fileName, size: $fileSize bytes")
                
                // Open input stream and validate encoding
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Validate file encoding
                    val encodingResult = csvValidator.validateEncoding(inputStream)
                    if (!encodingResult.isValid) {
                        runOnUiThread {
                            showErrorState(encodingResult.errorMessage ?: "Invalid file encoding")
                        }
                        return@Thread
                    }
                    
                    // Reset stream to read from beginning
                    inputStream.reset()
                    
                    // Read CSV data
                    val lines = csvReader.readCsvFile(uri)
                    if (lines.isEmpty()) {
                        runOnUiThread {
                            showErrorState("The CSV file is empty")
                        }
                        return@Thread
                    }
                    
                    // Validate delimiter
                    val delimiterResult = csvValidator.validateDelimiter(lines)
                    if (!delimiterResult.isValid) {
                        runOnUiThread {
                            showErrorState(delimiterResult.errorMessage ?: "Invalid delimiter")
                        }
                        return@Thread
                    }
                    
                    // Validate headers
                    val headerLine = lines[0]
                    val headerResult = csvValidator.validateHeaders(headerLine)
                    if (!headerResult.isValid) {
                        runOnUiThread {
                            showErrorState(headerResult.errorMessage ?: "Invalid headers")
                        }
                        return@Thread
                    }
                    
                    runOnUiThread {
                        showProcessingState("Processing ${lines.size} lines...")
                    }
                    
                    // Process records and convert to Garmin format
                    val parsedData = csvReader.parseCsvLines(lines)
                    val headers = parsedData.firstOrNull() ?: emptyList()
                    
                    val processedDataList = parsedData.drop(1).map { row ->
                        val recordMap = headers.zip(row).toMap()
                        RecordProcessor.processRecord(recordMap, csvValidator)
                    }
                    val processedData = processedDataList.mapNotNull { result ->
                        if (result.success) result.record else null
                    }
                    val outputFileName = generateOutputFileName()
                    
                    // Generate output file
                    val result = GarminCsvGenerator.generateGarminCsv(processedData, outputFileName) { current, total ->
                        runOnUiThread {
                            statusText.text = "Processing... $current/$total"
                        }
                    }
                    
                    runOnUiThread {
                        if (result.success) {
                            showSuccessState(parsedData.size, result.recordsGenerated)
                        } else {
                            showErrorState(result.error ?: "Unknown error occurred")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file", e)
                runOnUiThread {
                    showErrorState("Error processing file: ${e.message}")
                }
            }
        }.start()
    }
    
    private fun generateOutputFileName(): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "garmin-import-$timestamp.csv"
        return File(getExternalFilesDir(null), fileName)
    }
    
    private fun openGarminConnect() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GARMIN_CONNECT_URL))
        startActivity(intent)
    }
    
    private fun getFileName(uri: Uri): String {
        var fileName = uri.lastPathSegment ?: "unknown_file.csv"
        
        try {
            @Suppress("DEPRECATION")
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
            @Suppress("DEPRECATION")
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
    
    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$bytes bytes"
        }
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