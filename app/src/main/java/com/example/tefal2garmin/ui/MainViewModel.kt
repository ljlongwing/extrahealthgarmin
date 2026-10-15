package com.example.tefal2garmin.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.tefal2garmin.data.GarminRepository
import com.example.tefal2garmin.data.GarminUploadOrchestrator
import com.example.tefal2garmin.data.LoginStatus
import com.example.tefal2garmin.data.UploadOutcome
import com.example.tefal2garmin.garmin.GarminWeighInRecord

/**
 * Semantic upload status. MainActivity maps each value to an R.string — the
 * ViewModel never formats user-visible text itself.
 */
enum class GarminUploadStatus {
    /** An upload request is in flight. */
    UPLOADING,
    /** All new records were uploaded via the API. */
    SUCCESS,
    /** Nothing new to upload — everything is already on Garmin. */
    NOT_NEEDED,
    /** Some records were uploaded; the rest stay retriable (nothing is written anywhere). */
    PARTIAL,
    /** Nothing was uploaded; the records stay retriable for the next sync. */
    RETRYABLE,
    /** No stored token — the user must sign in before uploading. */
    LOGIN_REQUIRED
}

data class MainUiState(
    val garminConnected: Boolean = false,
    val uploadStatus: GarminUploadStatus? = null,
    /** Size of the batch the running upload was started with (for "x of y" copy). */
    val totalRecords: Int = 0,
    val uploadedCount: Int = 0,
    val uploadMessages: List<String> = emptyList(),
    val showLoginDialog: Boolean = false,
    val loginNeedsMfa: Boolean = false,
    val loginError: String? = null
)

class MainViewModel(
    private val garminUploader: GarminUploadOrchestrator,
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableLiveData<MainUiState>()
    val uiState: LiveData<MainUiState> = _uiState

    /**
     * Records of the upload that is pending (awaiting login or in flight) —
     * never credentials, only the data itself. A successful dialog login
     * retries exactly this payload; nothing is ever written to a file.
     */
    private var pendingRecords: List<GarminWeighInRecord> = emptyList()

    /**
     * The in-flight login coroutine. [onLoginCancelled] cancels it so a late
     * Success can never run the upload retry (or flip any state) after the
     * dialog was already dismissed.
     */
    private var loginJob: Job? = null

    init {
        _uiState.value = MainUiState(garminConnected = garminUploader.hasStoredToken())
    }

    /**
     * Starts an upload of [records]. LoginRequired keeps the records in
     * [pendingRecords] so a successful dialog login can retry once with
     * exactly the same payload.
     */
    fun startGarminUpload(records: List<GarminWeighInRecord>) {
        pendingRecords = records
        updateState {
            it.copy(
                uploadStatus = GarminUploadStatus.UPLOADING,
                totalRecords = records.size,
                uploadedCount = 0,
                uploadMessages = emptyList(),
                showLoginDialog = false,
                loginNeedsMfa = false,
                loginError = null
            )
        }
        viewModelScope.launch { performUpload(records) }
    }

    private suspend fun performUpload(records: List<GarminWeighInRecord>) {
        try {
            handleUploadOutcome(garminUploader.uploadNewRecords(records))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Garmin upload failed", e)
            updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.RETRYABLE,
                    uploadMessages = listOf(e.message ?: e.javaClass.simpleName)
                )
            }
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
                    uploadMessages = outcome.failureMessages
                )
            }
            is UploadOutcome.Retryable -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.RETRYABLE,
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
                    // dialog is already dismissed — no state flip, no upload.
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
                        if (pendingRecords.isNotEmpty()) {
                            // Retry the upload once with the SAME records
                            performUpload(pendingRecords)
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
     * User cancelled the login dialog. The in-flight login Job is cancelled
     * first so a late Success can never run the upload retry or flip state
     * after the dialog shipped. The pending records stay un-journaled — the
     * next login (or the next sync, once a data source exists) retries them;
     * nothing is written anywhere.
     */
    fun onLoginCancelled() {
        Log.d(TAG, "Garmin login cancelled — records stay pending for the next sync")
        loginJob?.cancel()
        loginJob = null
        updateState {
            it.copy(showLoginDialog = false, loginNeedsMfa = false, loginError = null)
        }
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
            val garminRepository = GarminRepository(application)
            return MainViewModel(garminRepository, application)
        }
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
