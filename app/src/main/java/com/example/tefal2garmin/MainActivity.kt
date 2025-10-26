package com.example.tefal2garmin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.tefal2garmin.data.FileInfo
import com.example.tefal2garmin.data.CsvProcessingResult
import com.example.tefal2garmin.ui.MainUiState
import com.example.tefal2garmin.ui.MainViewModel
import com.example.tefal2garmin.ui.MainViewModelFactory
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    
    // UI Components
    private lateinit var welcomeText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var openGarminButton: Button
    private lateinit var tryAgainButton: Button
    
    // ViewModel
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    
    // SAF launcher for Downloads folder
    private lateinit var saveFileLauncher: ActivityResultLauncher<String>
    
    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        initializeSaveFileLauncher()
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
                    showSuccessState(result.recordsProcessed, result.recordsGenerated)
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
            welcomeText.text = "File received: ${fileInfo.fileName}"
            statusText.text = "Size: ${formatFileSize(fileInfo.fileSize)} | Starting validation..."
        }
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
            viewModel.resetToInitialState()
        }
        
        resultText.setOnClickListener {
            viewModel.uiState.value?.let { state ->
                if (state.showSuccess) {
                    // Open file picker to save to Downloads
                    saveFileLauncher.launch(null)
                }
            }
        }
    }
    
    private fun initializeSaveFileLauncher() {
        saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                Log.d(TAG, "Output file saved to: ${uri.toString()}")
                runOnUiThread {
                    resultText.text = "File saved to: ${uri.lastPathSegment ?: "unknown"}"
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
                        viewModel.handleShareIntent(sharedUri)
                    }
                } else if (sharedText != null) {
                    viewModel.handleTextShare(sharedText)
                } else {
                    viewModel.handleShareIntent(null)
                }
            }
            else -> {
                viewModel.handleShareIntent(null)
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
        welcomeText.text = "Welcome to Tefal2Garmin Sync"
        instructionsText.visibility = View.VISIBLE
        instructionsText.text = "Share a CSV file from Tefal Goodvibes app to convert it for Garmin Connect import"
        progressBar.visibility = View.INVISIBLE
        statusText.visibility = View.INVISIBLE
        resultText.visibility = View.INVISIBLE
        openGarminButton.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.INVISIBLE
    }
    
    private fun showProcessingState() {
        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = "Processing file..."
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        resultText.visibility = View.INVISIBLE
        openGarminButton.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.INVISIBLE
    }
    
    private fun showSuccessState(recordsProcessed: Int, recordsGenerated: Int) {
        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = getString(R.string.success_file_processed)
        statusText.text = String.format(getString(R.string.success_records_summary), recordsProcessed, recordsGenerated)
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        resultText.visibility = View.VISIBLE
        resultText.text = getString(R.string.success_ready_to_save)
        openGarminButton.visibility = View.VISIBLE
        tryAgainButton.visibility = View.VISIBLE
    }
    
    private fun showErrorState(message: String) {
        Log.e(TAG, "Error occurred: $message")
        
        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = "Error occurred"
        statusText.text = message
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        resultText.visibility = View.VISIBLE
        resultText.text = "Please try again"
        openGarminButton.visibility = View.INVISIBLE
        tryAgainButton.visibility = View.VISIBLE
    }
    
    private fun openGarminConnect() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.getGarminConnectUrl()))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Garmin Connect: ${e.message}", e)
            Toast.makeText(this, "Failed to open Garmin Connect. Please check if you have a browser installed.", Toast.LENGTH_LONG).show()
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