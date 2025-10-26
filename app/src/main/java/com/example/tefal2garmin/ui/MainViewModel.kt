package com.example.tefal2garmin.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tefal2garmin.data.FileInfo
import com.example.tefal2garmin.data.FileRepository
import com.example.tefal2garmin.data.CsvProcessingResult
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator
import kotlinx.coroutines.launch

data class MainUiState(
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val fileInfo: FileInfo? = null,
    val processingResult: CsvProcessingResult? = null,
    val error: String? = null,
    val showSuccess: Boolean = false,
    val showError: Boolean = false,
    val showInitial: Boolean = true
)

class MainViewModel(
    private val fileRepository: FileRepository,
    application: Application
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
        private const val GARMIN_CONNECT_URL = "https://connect.garmin.com/modern/import-data"
    }
    
    private val _uiState = MutableLiveData<MainUiState>()
    val uiState: LiveData<MainUiState> = _uiState
    
    init {
        _uiState.value = MainUiState()
    }
    
    fun handleShareIntent(uri: Uri?) {
        if (uri != null) {
            processFile(uri)
        } else {
            updateState { it.copy(showInitial = true) }
        }
    }
    
    fun handleTextShare(text: String) {
        updateState { 
            it.copy(
                statusMessage = "Processing text content (${text.length} chars)...",
                isLoading = true,
                showInitial = false
            )
        }
        // In a real implementation, we might process the text content
        // For now, we'll just show an error
        updateState { 
            it.copy(
                isLoading = false,
                error = "Text sharing not supported. Please share a CSV file.",
                showError = true
            )
        }
    }
    
    private fun processFile(uri: Uri) {
        updateState { 
            it.copy(
                isLoading = true,
                showInitial = false,
                showError = false,
                showSuccess = false,
                error = null
            )
        }
        
        viewModelScope.launch {
            try {
                // Get file info first
                val fileInfo = fileRepository.getFileInfo(uri)
                updateState { 
                    it.copy(
                        fileInfo = fileInfo,
                        statusMessage = "Preparing to process ${fileInfo.fileName}..."
                    )
                }
                
                // Process the CSV file
                val result = fileRepository.processCsvFile(uri) { progress ->
                    updateState { it.copy(statusMessage = progress) }
                }
                
                result.fold(
                    onSuccess = { processingResult ->
                        updateState { 
                            it.copy(
                                isLoading = false,
                                processingResult = processingResult,
                                showSuccess = true,
                                statusMessage = "File processed successfully!"
                            )
                        }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Error processing file", exception)
                        updateState { 
                            it.copy(
                                isLoading = false,
                                error = exception.message ?: "Unknown error occurred",
                                showError = true
                            )
                        }
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in processFile", e)
                updateState { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error occurred",
                        showError = true
                    )
                }
            }
        }
    }
    
    fun resetToInitialState() {
        Log.d(TAG, "Resetting to initial state")
        _uiState.value = MainUiState()
    }
    
    fun saveOutputFile() {
        _uiState.value?.let { state ->
            state.processingResult?.let { result ->
                if (result.csvData != null && result.outputFileName != null) {
                    // Trigger file save with prepared data
                    // This will be handled by MainActivity
                    updateState { 
                        it.copy(
                            statusMessage = "Ready to save ${result.outputFileName}..."
                        )
                    }
                }
            }
        }
    }
    
    fun getOutputFileName(): String? {
        return _uiState.value?.processingResult?.outputFileName
    }
    
    fun getCsvData(): String? {
        return _uiState.value?.processingResult?.csvData
    }
    
    fun getGarminConnectUrl(): String {
        return GARMIN_CONNECT_URL
    }
    
    private fun updateState(update: (MainUiState) -> MainUiState) {
        _uiState.value = _uiState.value?.let(update) ?: MainUiState()
    }
}

@Suppress("UNCHECKED_CAST")
class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    
    companion object {
        fun createInstance(application: Application): MainViewModel {
            val contentResolver = application.contentResolver
            val csvReader = CsvReader(contentResolver)
            val csvValidator = CsvValidator()
            val fileRepository = FileRepository(contentResolver, csvReader, csvValidator, application)
            return MainViewModel(fileRepository, application)
        }
    }
    
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}