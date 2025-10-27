package com.example.tefal2garmin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.tefal2garmin.data.FileInfo
import com.example.tefal2garmin.data.CsvProcessingResult
import com.example.tefal2garmin.ui.MainUiState
import com.example.tefal2garmin.ui.MainViewModel
import com.example.tefal2garmin.ui.MainViewModelFactory
import com.example.tefal2garmin.utils.MimeTypeValidator
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    
    // UI Components
    private lateinit var welcomeText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var helpButton: Button
    private lateinit var tryAgainButton: Button
    
    // ViewModel
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    
    // SAF launcher for Downloads folder

    
    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()

        observeViewModel()
        
        // Handle share intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun observeViewModel() {
        viewModel.uiState.observe(this, Observer { state ->
            updateUI(state)
        })
    }
    
    private fun updateUI(state: MainUiState) {
        when {
            state.showInitial -> {
                showInitialState()
            }
            state.isLoading -> {
                showProcessingState()
            }
            state.showSuccess -> {
                state.processingResult?.let { result ->
                    // Automatically save file and open Garmin Connect
                    autoSaveAndOpenGarmin(result)
                }
            }
            state.showError -> {
                state.error?.let { error ->
                    showErrorState(error)
                }
            }
        }
        
        // Update status message
        if (state.statusMessage.isNotEmpty()) {
            statusText.text = state.statusMessage
        }
        
        // Update file info if available
        state.fileInfo?.let { fileInfo ->
            welcomeText.text = getString(R.string.file_received, fileInfo.fileName)
            statusText.text = getString(R.string.file_size_status, formatFileSize(fileInfo.fileSize))
        }
        
        // Show help button in appropriate states
        helpButton.visibility = when {
            state.showError -> View.VISIBLE
            state.showSuccess -> View.VISIBLE
            else -> View.INVISIBLE
        }
    }
    
    private fun initializeViews() {
        welcomeText = findViewById(R.id.welcomeText)
        instructionsText = findViewById(R.id.instructionsText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        helpButton = findViewById(R.id.helpButton)
        tryAgainButton = findViewById(R.id.tryAgainButton)
        
        // Set button click listeners
        // openGarminButton click listener removed - auto-open now
        
        helpButton.setOnClickListener {
            showHelpDialog()
        }
        
        tryAgainButton.setOnClickListener {
            viewModel.resetToInitialState()
        }
        
        // resultText click listener removed - auto-save now
    }
    

    
    private fun handleIntent(intent: Intent?) {
        Log.d(TAG, "Handling intent: ${intent?.action}")

        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                // Validate MIME type first
                val validationResult = MimeTypeValidator.validateIntentForCsv(intent)

                MimeTypeValidator.logMimeTypeHandling(
                    validationResult.mimeType,
                    validationResult.uri,
                    "Intent validation"
                )

                if (!validationResult.isValid) {
                    val errorMsg = validationResult.errorMessage ?: getString(R.string.unsupported_file_type)
                    Log.w(TAG, "Intent validation failed: $errorMsg")
                    showUnsupportedFileTypeError(validationResult.mimeType, errorMsg)
                    return
                }

                // Handle valid CSV content
                if (validationResult.uri != null) {
                    // URI-based sharing (file)
                    if (checkAndRequestPermissions()) {
                        viewModel.handleShareIntent(validationResult.uri)
                    }
                } else if (validationResult.textContent != null) {
                    // Text-based sharing
                    handleTextContent(validationResult.textContent, validationResult.mimeType)
                } else {
                    Log.w(TAG, "Valid intent but no content found")
                    viewModel.handleShareIntent(null)
                }
            }
            else -> {
                Log.d(TAG, "Ignoring unsupported intent action: ${intent?.action}")
                viewModel.handleShareIntent(null)
            }
        }
    }

    private fun handleTextContent(text: String, mimeType: String?) {
        // For text/plain or other text-based MIME types, check if content looks like CSV
        if (MimeTypeValidator.getCsvConfidenceLevel(mimeType) == MimeTypeValidator.CsvConfidence.MEDIUM ||
            MimeTypeValidator.getCsvConfidenceLevel(mimeType) == MimeTypeValidator.CsvConfidence.LOW) {

            if (MimeTypeValidator.appearsToBeCsv(text)) {
                Log.d(TAG, "Text content appears to be CSV, processing as CSV")
                viewModel.handleTextShare(text)
            } else {
                Log.w(TAG, "Text content does not appear to be CSV")
                showUnsupportedFileTypeError(mimeType, getString(R.string.content_not_csv))
            }
        } else {
            // High confidence MIME types - process directly
            viewModel.handleTextShare(text)
        }
    }

    private fun showUnsupportedFileTypeError(mimeType: String?, errorMessage: String) {
        val description = MimeTypeValidator.getMimeTypeDescription(mimeType)
        val fullMessage = getString(R.string.unsupported_file_dialog_message, description, errorMessage)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.unsupported_file_dialog_title))
            .setMessage(fullMessage)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
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
                    // Permission granted, process the intent again
                    handleIntent(intent)
                } else {
                    // Permission denied
                    viewModel.handleShareIntent(null) // Will show initial state
                }
            }
        }
    }
    
    private fun showInitialState() {
        welcomeText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        statusText.visibility = View.INVISIBLE
        progressBar.visibility = View.INVISIBLE
        helpButton.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.INVISIBLE
    }
    
    private fun showProcessingState() {
        welcomeText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        helpButton.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.INVISIBLE
    }
    
    private fun autoSaveAndOpenGarmin(result: CsvProcessingResult) {
        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = getString(R.string.success_file_processed)
        statusText.text = String.format(getString(R.string.success_records_summary), result.recordsProcessed, result.recordsGenerated)
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        helpButton.visibility = View.VISIBLE
        tryAgainButton.visibility = View.VISIBLE

        // Automatically save the file and open Garmin Connect
        saveFileAutomatically(result)
    }

    private fun saveFileAutomatically(result: CsvProcessingResult) {
        // Generate filename
        val fileName = viewModel.getOutputFileName() ?: "garmin_weight_data_${System.currentTimeMillis()}.csv"

        // Create file in Downloads directory automatically
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFile = File(downloadsDir, fileName)

        try {
            // Get CSV data
            val csvData = viewModel.getCsvData()
            if (csvData != null) {
                // Write file
                outputFile.writeText(csvData, Charsets.UTF_8)

                Log.d(TAG, "File automatically saved to: ${outputFile.absolutePath}")
                runOnUiThread {
                    statusText.text = getString(R.string.file_saved_auto, fileName)
                    // Automatically open Garmin Connect after a short delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        openGarminConnect()
                    }, 1500) // 1.5 second delay to show the success message
                }
            } else {
                Log.e(TAG, "No CSV data available to save")
                runOnUiThread {
                    statusText.text = getString(R.string.no_data_available)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-save file", e)
            runOnUiThread {
                statusText.text = getString(R.string.file_save_failed)
            }
        }
    }

    private fun openGarminConnect() {
        try {
            val garminUrl = viewModel.getGarminConnectUrl()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(garminUrl))
            startActivity(intent)
            Log.d(TAG, "Opened Garmin Connect: $garminUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Garmin Connect", e)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.toast_garmin_open_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showErrorState(message: String) {
        Log.e(TAG, "Error occurred: $message")

        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = getString(R.string.error_occurred)
        statusText.text = message
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.VISIBLE
    }
    
    private fun showHelpDialog() {
        val helpContent = getString(R.string.help_content)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.help_title))
            .setMessage(helpContent)
            .setPositiveButton(getString(R.string.help_close)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.help_troubleshooting)) { dialog, _ ->
                openTroubleshootingGuide()
            }
            .show()
    }
    
    private fun openTroubleshootingGuide() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("file:///android_asset/docs/complete-troubleshooting-guide.md")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open troubleshooting guide", e)
            Toast.makeText(this, getString(R.string.toast_help_open_failed), Toast.LENGTH_SHORT).show()
        }
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