package com.example.tefal2garmin

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import java.io.File
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.tefal2garmin.data.CsvProcessingResult
import com.example.tefal2garmin.ui.GarminUploadStatus
import com.example.tefal2garmin.ui.MainUiState
import com.example.tefal2garmin.ui.MainViewModel
import com.example.tefal2garmin.ui.MainViewModelFactory
import com.example.tefal2garmin.utils.MimeTypeValidator

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var welcomeText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var helpButton: Button
    private lateinit var tryAgainButton: Button

    // Garmin connection entry point (task tefal2garmin-garmin-api-import-j5e.4).
    // Created programmatically — the layout file stays untouched; the views are
    // constrained into the existing ConstraintLayout below tryAgainButton.
    private lateinit var connectGarminButton: Button
    private lateinit var garminConnectedText: TextView

    // Login dialog (AlertDialog + programmatic custom view, no fragments).
    // Nullable: the refs (and the input contents) are cleared on every dialog
    // dismiss — credentials must not outlive the dialog.
    private var loginDialog: AlertDialog? = null
    private var loginEmailInput: EditText? = null
    private var loginPasswordInput: EditText? = null
    private var loginMfaInput: EditText? = null
    private var loginErrorText: TextView? = null

    // Guards so repeated LiveData emissions never re-trigger one-shot actions
    // (dialog lifecycle) — see renderLoginDialog. The residual save-once
    // decision lives in the ViewModel (claimResidualSave) so rotation
    // cannot re-save + re-open the browser.
    private var lastShowLoginDialog = false
    private var lastGarminConnected = false

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
                    showSuccessState(result)
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

        // Garmin upload/login rendering — after the blocks above so upload
        // status text wins over the generic status/file-info messages
        renderGarminConnection(state)
        renderUploadOutcome(state)
        renderLoginDialog(state)
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

        addGarminConnectionViews()

        // Debug-only live-spike trigger for the Garmin DI OAuth2 auth client
        // (task tefal2garmin-garmin-api-import-j5e.1): hidden long-press on
        // the status text. The implementation lives in the debug source set
        // (release links a no-op — the GARMINSPIKE_* BuildConfig fields
        // exist only in debug). Credentials come from BuildConfig fields fed
        // by Gradle properties:
        //   ./gradlew assembleDebug -PGARMINSPIKE_EMAIL=... \
        //     -PGARMINSPIKE_PASSWORD=... [-PGARMINSPIKE_MFA_CODE=...]
        // Watch with: adb logcat -s MainActivity GarminAuth GarminHttp
        if (BuildConfig.DEBUG) {
            // Attached to the instructions text as well because statusText is
            // INVISIBLE on the initial screen (showInitialState) and invisible
            // views receive no touch events — the spike must be reachable on
            // a fresh launch.
            instructionsText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
            statusText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
        }

        // resultText click listener removed - auto-save now
    }

    /**
     * Builds the "Connect Garmin" entry point in code (the XML layout is out of
     * scope for this task): an outlined button when no token is stored, and a
     * connected-status label once the ViewModel reports a stored token.
     */
    private fun addGarminConnectionViews() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ConstraintLayout

        connectGarminButton = Button(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = getString(R.string.connect_garmin)
            setOnClickListener { showLoginDialog() }
        }
        val buttonParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToBottom = tryAgainButton.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(16)
        }
        root.addView(connectGarminButton, buttonParams)

        garminConnectedText = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.garmin_connected)
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
        }
        val labelParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToBottom = connectGarminButton.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(8)
        }
        root.addView(garminConnectedText, labelParams)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Button vs connected-label visibility follows the stored-token state. */
    private fun renderGarminConnection(state: MainUiState) {
        connectGarminButton.visibility = if (state.garminConnected) View.GONE else View.VISIBLE
        garminConnectedText.visibility = if (state.garminConnected) View.VISIBLE else View.GONE
    }

    /**
     * Maps the ViewModel upload status to status strings and drives the
     * residual-CSV fallback: Success/NotNeeded produce NO Downloads file and
     * NO browser; Partial/Fallback hand the residual CSV to the existing
     * save+open machinery.
     */
    private fun renderUploadOutcome(state: MainUiState) {
        val status = state.uploadStatus
        if (status == null) {
            // No upload pipeline for this result (csvData was never generated):
            // preserve the pre-API behavior — save the file and open Garmin
            // Connect. The transient emission before the UPLOADING status has
            // a non-null processingResult.csvData and never reaches here.
            if (state.showSuccess && state.processingResult?.csvData == null) {
                legacyAutoSaveAndOpen()
            }
            return
        }
        when (status) {
            GarminUploadStatus.UPLOADING ->
                statusText.text = getString(R.string.upload_in_progress)
            GarminUploadStatus.SUCCESS ->
                statusText.text = getString(R.string.upload_success, state.uploadedCount)
            GarminUploadStatus.NOT_NEEDED ->
                statusText.text = getString(R.string.upload_not_needed)
            GarminUploadStatus.PARTIAL -> {
                statusText.text = buildString {
                    append(getString(R.string.upload_partial, state.uploadedCount, state.residualCount))
                    state.uploadMessages.filter { it.isNotBlank() }.forEach {
                        append("\n").append(it)
                    }
                }
                saveResidualIfNeeded(state)
            }
            GarminUploadStatus.FALLBACK -> {
                statusText.text = buildString {
                    append(getString(R.string.upload_fallback, state.residualCount))
                    state.uploadMessages.filter { it.isNotBlank() }.forEach {
                        append("\n").append(it)
                    }
                }
                saveResidualIfNeeded(state)
            }
            GarminUploadStatus.LOGIN_REQUIRED ->
                statusText.text = getString(R.string.login_required)
        }
    }

    /** Saves the residual CSV once per conversion outcome ( Downloads + browser ). */
    private fun saveResidualIfNeeded(state: MainUiState) {
        val residualCsv = state.residualCsv ?: return
        if (state.residualCount <= 0) return // nothing new — no file, no browser
        val fileName = viewModel.getOutputFileName() ?: return
        val saveKey = "$fileName:${state.uploadStatus}:${state.residualCount}:${residualCsv.hashCode()}"
        // Claim lives in the ViewModel: rotation re-renders the same state but
        // can never claim the same key twice.
        if (!viewModel.claimResidualSave(saveKey)) return
        saveFileAutomatically(residualCsv, fileName)
    }

    /** Pre-API behavior kept for results that never enter the upload pipeline. */
    private fun legacyAutoSaveAndOpen() {
        val csvData = viewModel.getCsvData()
        if (csvData == null) {
            statusText.text = getString(R.string.no_data_available)
            return
        }
        val fileName = viewModel.getOutputFileName()
            ?: "garmin_weight_data_${System.currentTimeMillis()}.csv"
        saveFileAutomatically(csvData, fileName)
    }

    /**
     * Debug-only live spike for the Garmin DI OAuth2 auth client — the real
     * implementation lives in the debug source set (needs the debug-only
     * GARMINSPIKE_* BuildConfig fields); release builds link a no-op.
     */
    private fun triggerGarminAuthSpike() {
        GarminAuthSpike.trigger(this)
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

    private fun showSuccessState(result: CsvProcessingResult) {
        welcomeText.visibility = View.INVISIBLE
        instructionsText.text = getString(R.string.success_file_processed)
        statusText.text = String.format(getString(R.string.success_records_summary), result.recordsProcessed, result.recordsGenerated)
        statusText.visibility = View.VISIBLE
        progressBar.visibility = View.INVISIBLE
        helpButton.visibility = View.VISIBLE
        tryAgainButton.visibility = View.VISIBLE
        // No unconditional save/browser here anymore — renderUploadOutcome
        // decides between the API-upload outcome and the residual CSV path.
    }

    /**
     * Writes [content] to the public Downloads directory as [fileName]. On
     * success the Garmin Connect import page opens after the usual 1.5 s
     * pause; on failure the file-save-failed status is shown and the browser
     * is NOT opened (a browser without a saved file is a dead end).
     *
     * API 29+ goes through the MediaStore Downloads collection — raw writes
     * to the scoped-storage public Downloads tree silently fail on modern
     * Android (on-device E2E 2026-10-14: browser opened, no file appeared).
     * Below API 29 the legacy direct File path is kept.
     */
    private fun saveFileAutomatically(content: String, fileName: String) {
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreDownloads(content, fileName)
        } else {
            saveLegacyDownloadsFile(content, fileName)
        }

        if (saved) {
            runOnUiThread {
                statusText.text = getString(R.string.file_saved_auto, fileName)
                // Automatically open Garmin Connect after a short delay
                Handler(Looper.getMainLooper()).postDelayed({
                    openGarminConnect()
                }, 1500) // 1.5 second delay to show the success message
            }
        } else {
            runOnUiThread {
                statusText.text = getString(R.string.file_save_failed)
            }
        }
    }

    /** API 29+: MediaStore Downloads collection (scoped-storage friendly). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStoreDownloads(content: String, fileName: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "MediaStore Downloads insert returned no uri for $fileName")
            false
        } else {
            val written = try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore Downloads write failed", e)
                false
            }
            if (written) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                val published = contentResolver.update(uri, values, null, null) > 0
                if (published) {
                    Log.d(TAG, "File automatically saved via MediaStore: $fileName")
                } else {
                    contentResolver.delete(uri, null, null)
                }
                published
            } else {
                contentResolver.delete(uri, null, null) // drop the dangling pending row
                false
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to auto-save file via MediaStore", e)
        false
    }

    /** Legacy (pre-API-29) direct write into the public Downloads directory. */
    private fun saveLegacyDownloadsFile(content: String, fileName: String): Boolean = try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outputFile = File(downloadsDir, fileName)
        outputFile.writeText(content, Charsets.UTF_8)
        Log.d(TAG, "File automatically saved to: ${outputFile.absolutePath}")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to auto-save file", e)
        false
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

    /**
     * Owns the login AlertDialog lifecycle across LiveData emissions: opens it
     * when the ViewModel asks (upload hit LoginRequired or the Connect button
     * was tapped), updates the MFA field / inline error in place, and closes
     * it when the ViewModel reports a closed dialog or a successful login.
     */
    private fun renderLoginDialog(state: MainUiState) {
        val closeDueToState =
            (lastShowLoginDialog && !state.showLoginDialog) ||
                (state.garminConnected && !lastGarminConnected)
        if (closeDueToState && loginDialog?.isShowing == true) {
            loginDialog?.dismiss()
        }

        // Drop stale references (user dismissed via back/outside touch); the
        // dismiss listener also clears the input fields and their refs.
        if (loginDialog != null && loginDialog?.isShowing != true) {
            dropLoginDialog()
        }

        if (state.showLoginDialog && loginDialog?.isShowing != true) {
            showLoginDialog()
        }

        lastShowLoginDialog = state.showLoginDialog
        lastGarminConnected = state.garminConnected

        val dialog = loginDialog ?: return
        val mfaInput = loginMfaInput ?: return
        val errorText = loginErrorText ?: return
        mfaInput.visibility = if (state.loginNeedsMfa) View.VISIBLE else View.GONE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
            if (state.loginNeedsMfa) View.VISIBLE else View.GONE
        errorText.text = state.loginError
            ?.let { getString(R.string.login_failed, it) }
            ?: ""
        errorText.visibility = if (state.loginError == null) View.GONE else View.VISIBLE
    }

    /**
     * Clears every login input (email/password/MFA), drops the Activity's
     * field references and the dialog ref. Runs on every dialog dismiss —
     * cancel, back/outside touch, and success all end in a dismiss — so
     * credentials never outlive the dialog.
     */
    private fun dropLoginDialog() {
        loginEmailInput?.setText("")
        loginPasswordInput?.setText("")
        loginMfaInput?.setText("")
        loginEmailInput = null
        loginPasswordInput = null
        loginMfaInput = null
        loginErrorText = null
        loginDialog = null
    }

    /**
     * Login dialog: email + password, then a verification-code field once the
     * ViewModel reports NeedsMfa. Credentials live only in the dialog's own
     * input fields and go straight into the login call — nothing is stored.
     */
    private fun showLoginDialog() {
        val padding = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        // Local non-null handles: the fields are nullable (cleared on
        // dismiss), but inside this builder they are freshly assigned.
        val emailInput = EditText(this).apply {
            hint = getString(R.string.login_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.login_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod()
        }
        val mfaInput = EditText(this).apply {
            hint = getString(R.string.mfa_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
        }
        val errorText = TextView(this).apply {
            visibility = View.GONE
        }
        loginEmailInput = emailInput
        loginPasswordInput = passwordInput
        loginMfaInput = mfaInput
        loginErrorText = errorText
        container.addView(emailInput)
        container.addView(passwordInput)
        container.addView(mfaInput)
        container.addView(errorText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_title))
            .setView(container)
            .setPositiveButton(getString(R.string.login_submit), null)
            .setNeutralButton(getString(R.string.resend_code), null)
            .setNegativeButton(getString(R.string.login_cancel), null)
            .setOnCancelListener { viewModel.onLoginCancelled() }
            .create()
        // Every exit path (cancel button, back, outside touch, success) ends
        // in a dismiss — clear the inputs and drop the refs there.
        dialog.setOnDismissListener { dropLoginDialog() }
        dialog.show()

        // Custom click listeners (installed after show()) replace the default
        // dismiss-on-click so NeedsMfa/Failed keep the dialog open with the
        // entered credentials intact.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mfaCode = mfaInput.text.toString().trim().takeIf { it.isNotEmpty() }
            viewModel.loginToGarmin(
                emailInput.text.toString().trim(),
                passwordInput.text.toString(),
                mfaCode
            )
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // Resend = re-run login with the same credentials and no code
            viewModel.loginToGarmin(
                emailInput.text.toString().trim(),
                passwordInput.text.toString(),
                null
            )
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.cancel() // fires the cancel listener -> onLoginCancelled
        }
        loginDialog = dialog
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
            val guideText = assets.open("docs/complete-troubleshooting-guide.md")
                .bufferedReader()
                .use { it.readText() }

            val escapedText = guideText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
            val html = "<html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "</head><body>" +
                "<pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:13px;padding:8px\">" +
                escapedText +
                "</pre></body></html>"

            val webView = WebView(this)
            webView.settings.javaScriptEnabled = false
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.help_troubleshooting))
                .setView(webView)
                .setPositiveButton(getString(R.string.help_close)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
            // Release chromium resources once the dialog goes away; repeated opens
            // would otherwise leak every WebView instance
            dialog.setOnDismissListener { webView.destroy() }
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
