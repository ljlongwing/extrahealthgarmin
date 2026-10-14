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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.tefal2garmin.data.FileInfo
import com.example.tefal2garmin.data.FileRepository
import com.example.tefal2garmin.data.CsvProcessingResult
import com.example.tefal2garmin.data.GarminRepository
import com.example.tefal2garmin.data.GarminUploadOrchestrator
import com.example.tefal2garmin.data.LoginStatus
import com.example.tefal2garmin.data.UploadOutcome
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator

/**
 * Semantic post-conversion upload status. MainActivity maps each value to an
 * R.string — the ViewModel never formats user-visible text itself.
 */
enum class GarminUploadStatus {
    /** An upload request is in flight. */
    UPLOADING,
    /** All new records were uploaded via the API. */
    SUCCESS,
    /** Nothing new to upload — everything is already on Garmin. */
    NOT_NEEDED,
    /** Some records uploaded; the remainder was written to a residual CSV. */
    PARTIAL,
    /** API upload impossible; the full remainder went to the residual CSV. */
    FALLBACK,
    /** No stored token — the user must sign in before uploading. */
    LOGIN_REQUIRED
}

data class MainUiState(
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val fileInfo: FileInfo? = null,
    val processingResult: CsvProcessingResult? = null,
    val error: String? = null,
    val showSuccess: Boolean = false,
    val showError: Boolean = false,
    val showInitial: Boolean = true,
    // Garmin API upload integration (task tefal2garmin-garmin-api-import-j5e.4)
    val garminConnected: Boolean = false,
    val uploadStatus: GarminUploadStatus? = null,
    val uploadedCount: Int = 0,
    val residualCsv: String? = null,
    val residualCount: Int = 0,
    val uploadMessages: List<String> = emptyList(),
    val showLoginDialog: Boolean = false,
    val loginNeedsMfa: Boolean = false,
    val loginError: String? = null
)

class MainViewModel(
    private val fileRepository: FileRepository,
    private val garminUploader: GarminUploadOrchestrator,
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val GARMIN_CONNECT_URL = "https://connect.garmin.com/modern/import-data"
        private const val LOGIN_CANCELLED_REASON = "Login cancelled"
    }

    private val _uiState = MutableLiveData<MainUiState>()
    val uiState: LiveData<MainUiState> = _uiState

    /**
     * CSV payload of the conversion whose upload is pending (awaiting login or
     * in flight). Never holds credentials — only the converted data.
     */
    private var pendingUploadCsv: String? = null

    /**
     * The in-flight login coroutine. [onLoginCancelled] cancels it so a late
     * Success can never run [performUpload] (or flip any state) after the
     * residual fallback already shipped.
     */
    private var loginJob: Job? = null

    /**
     * Residual-save keys already claimed for this ViewModel's lifetime. Lives
     * here (not in the Activity) so an Activity rotation cannot re-trigger
     * the one-shot Downloads save + browser open.
     */
    private val claimedResidualSaveKeys = mutableSetOf<String>()

    init {
        _uiState.value = MainUiState(garminConnected = garminUploader.hasStoredToken())
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
        pendingUploadCsv = null
        updateState {
            it.copy(
                isLoading = true,
                showInitial = false,
                showError = false,
                showSuccess = false,
                error = null,
                uploadStatus = null,
                uploadedCount = 0,
                residualCsv = null,
                residualCount = 0,
                uploadMessages = emptyList(),
                showLoginDialog = false,
                loginNeedsMfa = false,
                loginError = null
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
                    viewModelScope.launch(Dispatchers.Main) {
                        updateState { it.copy(statusMessage = progress) }
                    }
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
                        // Epic requirement 5: conversion success no longer saves
                        // a Downloads file or opens the browser unconditionally —
                        // the Garmin API is tried first and the residual CSV is
                        // produced only when the API path cannot take everything.
                        startGarminUpload(processingResult)
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

    /**
     * Runs the upload pipeline for a finished conversion. LoginRequired keeps
     * the CSV in [pendingUploadCsv] so a successful dialog login can retry
     * once with exactly the same payload.
     */
    private fun startGarminUpload(result: CsvProcessingResult) {
        val csvData = result.csvData ?: return // nothing generated — legacy save path
        pendingUploadCsv = csvData
        updateState { it.copy(uploadStatus = GarminUploadStatus.UPLOADING) }
        viewModelScope.launch { performUpload(csvData) }
    }

    private suspend fun performUpload(csvData: String) {
        try {
            handleUploadOutcome(garminUploader.uploadNewFromCsv(csvData))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Garmin upload failed", e)
            fallbackToResidualCsv(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleUploadOutcome(outcome: UploadOutcome) {
        when (outcome) {
            is UploadOutcome.NotNeeded -> updateState {
                it.copy(uploadStatus = GarminUploadStatus.NOT_NEEDED)
            }
            is UploadOutcome.Success -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.SUCCESS,
                    uploadedCount = outcome.uploadedCount
                )
            }
            is UploadOutcome.Partial -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.PARTIAL,
                    uploadedCount = outcome.uploadedCount,
                    residualCsv = outcome.residualCsv,
                    residualCount = outcome.residualCount,
                    uploadMessages = outcome.messages
                )
            }
            is UploadOutcome.Fallback -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.FALLBACK,
                    residualCsv = outcome.residualCsv,
                    residualCount = outcome.residualCount,
                    uploadMessages = listOf(outcome.reason)
                )
            }
            is UploadOutcome.LoginRequired -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.LOGIN_REQUIRED,
                    showLoginDialog = true
                )
            }
        }
    }

    /**
     * Signs in to Garmin. Called from the login dialog for the initial submit,
     * the MFA resume, and the "resend code" action (which re-runs login with
     * the same credentials and no code). Credentials go straight into the
     * login call and are never stored on this ViewModel.
     */
    fun loginToGarmin(email: String, password: String, mfaCode: String? = null) {
        loginJob = viewModelScope.launch {
            try {
                val status = garminUploader.login(email, password, mfaCode)
                if (!isActive) {
                    // Cancelled while the login coroutine was suspended: the
                    // fallback already shipped — no state flip, no upload.
                    return@launch
                }
                when (status) {
                    is LoginStatus.Success -> {
                        updateState {
                            it.copy(
                                garminConnected = true,
                                showLoginDialog = false,
                                loginNeedsMfa = false,
                                loginError = null
                            )
                        }
                        pendingUploadCsv?.let { csv ->
                            // Retry the upload once with the SAME csv data
                            performUpload(csv)
                        }
                    }
                    is LoginStatus.NeedsMfa -> updateState {
                        it.copy(loginNeedsMfa = true, showLoginDialog = true, loginError = null)
                    }
                    is LoginStatus.Failed -> updateState {
                        it.copy(loginError = status.reason, showLoginDialog = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e // cancelled by onLoginCancelled — it owns the state now
            } catch (e: Exception) {
                Log.e(TAG, "Garmin login failed", e)
                updateState {
                    it.copy(loginError = e.message ?: e.javaClass.simpleName, showLoginDialog = true)
                }
            }
        }
    }

    /**
     * User cancelled the login dialog. Round-1 epic decision: the whole
     * conversion falls back to the residual-CSV (Downloads + browser) path.
     * The in-flight login Job is cancelled first so a late Success can never
     * run [performUpload] or flip state after the fallback shipped, and the
     * residual is the JOURNAL-FILTERED one from the repository (only rows
     * that were never confirmed imported).
     */
    fun onLoginCancelled() {
        Log.d(TAG, "Garmin login cancelled — falling back to residual CSV")
        loginJob?.cancel()
        loginJob = null
        val residual = pendingUploadCsv
        pendingUploadCsv = null
        updateState {
            it.copy(showLoginDialog = false, loginNeedsMfa = false, loginError = null)
        }
        if (residual != null) {
            fallbackToResidual(residual, LOGIN_CANCELLED_REASON)
        }
    }

    /** API upload blew up: everything not yet journaled becomes the residual CSV. */
    private fun fallbackToResidualCsv(reason: String) {
        val residual = pendingUploadCsv ?: return
        pendingUploadCsv = null
        fallbackToResidual(residual, reason)
    }

    /** Shared cancel/exception fallback: journal-filtered residual via the repository. */
    private fun fallbackToResidual(csvData: String, reason: String) {
        viewModelScope.launch {
            try {
                handleUploadOutcome(garminUploader.fallbackResidual(csvData, reason))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Residual fallback failed", e)
                updateState {
                    it.copy(
                        uploadStatus = GarminUploadStatus.FALLBACK,
                        residualCsv = csvData,
                        residualCount = it.processingResult?.recordsGenerated ?: 0,
                        uploadMessages = listOf(reason)
                    )
                }
            }
        }
    }

    /**
     * Returns true exactly once per distinct [saveKey]. The decision state
     * lives in the ViewModel, so rotating the Activity (which re-renders the
     * same LiveData emission) cannot re-save the residual CSV to Downloads
     * and re-open the browser per rotation.
     */
    fun claimResidualSave(saveKey: String): Boolean = claimedResidualSaveKeys.add(saveKey)

    fun resetToInitialState() {
        Log.d(TAG, "Resetting to initial state")
        loginJob?.cancel()
        loginJob = null
        pendingUploadCsv = null
        _uiState.value = MainUiState(garminConnected = garminUploader.hasStoredToken())
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
            val garminRepository = GarminRepository(application)
            return MainViewModel(fileRepository, garminRepository, application)
        }
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
