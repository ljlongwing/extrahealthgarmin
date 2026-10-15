package com.example.tefal2garmin

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Observer
import com.example.tefal2garmin.ui.GarminUploadStatus
import com.example.tefal2garmin.ui.MainUiState
import com.example.tefal2garmin.ui.MainViewModel
import com.example.tefal2garmin.ui.MainViewModelFactory

/**
 * Interim home screen (Strangler phase 1b): app title, an awaiting-source
 * status line and the Garmin connection entry point with its login dialog.
 * The weight-data source (Health Connect) arrives in the next update; until
 * then uploads are started programmatically through the ViewModel.
 */
class MainActivity : AppCompatActivity() {

    // Interim home screen views
    private lateinit var titleText: TextView
    private lateinit var awaitingSourceText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    // Garmin connection entry point (task tefal2garmin-garmin-api-import-j5e.4).
    // Created programmatically — the layout file stays untouched; the views are
    // constrained into the existing ConstraintLayout below statusText.
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
    // (dialog lifecycle) — see renderLoginDialog.
    private var lastShowLoginDialog = false
    private var lastGarminConnected = false

    // ViewModel
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this, Observer { state ->
            updateUI(state)
        })
    }

    private fun updateUI(state: MainUiState) {
        // Upload/login status text wins over the awaiting-source line
        renderUploadStatus(state)
        renderGarminConnection(state)
        renderLoginDialog(state)
    }

    private fun initializeViews() {
        titleText = findViewById(R.id.titleText)
        awaitingSourceText = findViewById(R.id.awaitingSourceText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        // Debug-only live-spike trigger for the Garmin DI OAuth2 auth client
        // (task tefal2garmin-garmin-api-import-j5e.1): hidden long-press on
        // the status lines. The implementation lives in the debug source set
        // (release links a no-op — the GARMINSPIKE_* BuildConfig fields
        // exist only in debug). Credentials come from BuildConfig fields fed
        // by Gradle properties:
        //   ./gradlew assembleDebug -PGARMINSPIKE_EMAIL=... \
        //     -PGARMINSPIKE_PASSWORD=... [-PGARMINSPIKE_MFA_CODE=...]
        // Watch with: adb logcat -s MainActivity GarminAuth GarminHttp
        if (BuildConfig.DEBUG) {
            // Attached to the awaiting-source text as well because statusText
            // is INVISIBLE until an upload runs and invisible views receive
            // no touch events — the spike must be reachable on a fresh launch.
            awaitingSourceText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
            statusText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
        }

        addGarminConnectionViews()
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
            topToBottom = statusText.id
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
     * Maps the ViewModel upload status to status strings. No outcome ever
     * writes a file or opens a browser — failed records stay retriable for
     * the next sync.
     */
    private fun renderUploadStatus(state: MainUiState) {
        when (state.uploadStatus) {
            null -> {
                progressBar.visibility = View.INVISIBLE
                statusText.visibility = View.INVISIBLE
            }
            GarminUploadStatus.UPLOADING -> {
                progressBar.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.upload_in_progress)
            }
            GarminUploadStatus.SUCCESS -> showStatus(
                getString(R.string.upload_success, state.uploadedCount)
            )
            GarminUploadStatus.NOT_NEEDED -> showStatus(getString(R.string.upload_not_needed))
            GarminUploadStatus.PARTIAL -> showStatus(
                buildString {
                    append(getString(R.string.upload_partial, state.uploadedCount, state.totalRecords))
                    state.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                        append("\n").append(it)
                    }
                }
            )
            GarminUploadStatus.RETRYABLE -> showStatus(
                buildString {
                    append(getString(R.string.upload_retryable))
                    state.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                        append("\n").append(it)
                    }
                }
            )
            GarminUploadStatus.LOGIN_REQUIRED -> showStatus(getString(R.string.login_required))
        }
    }

    private fun showStatus(text: String) {
        progressBar.visibility = View.INVISIBLE
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    /**
     * Debug-only live spike for the Garmin DI OAuth2 auth client — the real
     * implementation lives in the debug source set (needs the debug-only
     * GARMINSPIKE_* BuildConfig fields); release builds link a no-op.
     */
    private fun triggerGarminAuthSpike() {
        GarminAuthSpike.trigger(this)
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
}
