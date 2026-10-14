package com.example.tefal2garmin.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.tefal2garmin.data.FileRepository
import com.example.tefal2garmin.data.GarminUploadOrchestrator
import com.example.tefal2garmin.data.LoginStatus
import com.example.tefal2garmin.data.UploadOutcome
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

/**
 * Pins the MainViewModel upload state machine against the
 * GarminUploadOrchestrator contract (task tefal2garmin-garmin-api-import-j5e.4):
 * which outcome maps to which UI state, that Success/NotNeeded never surface a
 * residual CSV (no Downloads file / no browser), and the login dialog flow
 * (retry-after-login, MFA resume, resend, cancel-to-fallback, inline errors).
 *
 * No Robolectric (broken on the current JDK — see build.gradle testOptions):
 * the ViewModel runs against the real FileRepository pipeline built on the
 * fake CsvReader used by FileRepositoryTest, Dispatchers.Main is swapped for a
 * StandardTestDispatcher, and LiveData values are read directly under
 * InstantTaskExecutorRule.
 *
 * The orchestrator is a Mockito mock of the interface whose default Answer
 * records every call and returns the current [loginStatus]/[uploadOutcome]/
 * [storedToken] fields. Plain when()/verify() stubbing of suspend functions
 * needs a matcher for the hidden Continuation parameter (mockito-kotlin is
 * not a dependency of this project); the recording answer sidesteps that
 * entirely and doubles as the call log the assertions verify against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelUploadTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: MainViewModel

    // Orchestrator mock state: what each method returns next
    private var loginStatus: LoginStatus = LoginStatus.Success
    private var uploadOutcome: UploadOutcome = UploadOutcome.NotNeeded
    private var storedToken: Boolean = false

    /** When set, uploadNewFromCsv throws instead of returning [uploadOutcome]. */
    private var uploadException: Exception? = null

    /**
     * When set, fallbackResidual returns this; otherwise it answers with a
     * Fallback echoing the csv argument, [fallbackResidualCount] and the
     * reason argument (the "journal-filtered residual" stand-in).
     */
    private var fallbackOutcome: UploadOutcome.Fallback? = null
    private var fallbackResidualCount: Int = 2

    // Orchestrator call log: (email, password, mfaCode), csv payloads and
    // fallbackResidual (csv, reason) pairs
    private val loginCalls = mutableListOf<Triple<String, String, String?>>()
    private val uploadCalls = mutableListOf<String>()
    private val fallbackCalls = mutableListOf<Pair<String, String>>()

    // Full 15-column primary Goodvibes header, as exported by the Tefal app
    // (same fixture as FileRepositoryTest)
    private val primaryHeaders = "Время измерения,Вес(кг),ИМТ,Пульс(bpm),Процент жира(%),Масса тела без жира(кг),Подкожный жир(%),Висцеральный жир,Вода в организме(%),Скелетные мышцы(%),Мышечная масса(кг),Костная масса(кг),Белок(%),Величина основного обмена(kcal),Метаболический возраст"

    /** Builds one full 15-column data row; date/weight/bmi/fat are the mapped columns. */
    private fun goodvibesRow(date: String, weight: String, bmi: String, fat: String): String {
        return listOf(
            date, weight, bmi, "88.00", fat, "62.20", "19.40", "7.00", "56.40",
            "44.50", "59.10", "3.11", "17.80", "1713.00", "52.00"
        ).joinToString(",")
    }

    private val twoValidRows = listOf(
        goodvibesRow("\"окт. 25, 2025 09:51:52\"", "79.65", "24.60", "21.90"),
        goodvibesRow("\"окт. 21, 2025 08:20:53\"", "80.25", "24.80", "21.70")
    )

    private fun buildFileRepository(rows: List<String>): FileRepository {
        val resolver = object : ContentResolver(null) {}
        val lines = listOf(primaryHeaders) + rows
        val csvReader = object : CsvReader(resolver) {
            override fun readCsvFile(uri: Uri): List<String> = lines
        }
        return FileRepository(resolver, csvReader, CsvValidator(), ContextWrapper(null))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val orchestrator: GarminUploadOrchestrator =
            mock(GarminUploadOrchestrator::class.java) { invocation ->
                when (invocation.method.name) {
                    "login" -> {
                        loginCalls.add(
                            Triple(
                                invocation.getArgument(0),
                                invocation.getArgument(1),
                                invocation.getArgument(2)
                            )
                        )
                        loginStatus
                    }
                    "uploadNewFromCsv" -> {
                        uploadCalls.add(invocation.getArgument(0))
                        val exception = uploadException
                        if (exception != null) throw exception
                        uploadOutcome
                    }
                    "fallbackResidual" -> {
                        val residualCsvArg: String = invocation.getArgument(0)
                        val reasonArg: String = invocation.getArgument(1)
                        fallbackCalls.add(residualCsvArg to reasonArg)
                        fallbackOutcome ?: UploadOutcome.Fallback(
                            residualCsv = invocation.getArgument(0),
                            residualCount = fallbackResidualCount,
                            reason = invocation.getArgument(1)
                        )
                    }
                    "hasStoredToken" -> storedToken
                    else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        // A plain Application() — the inline mock maker cannot instrument
        // android.jar classes on this JDK; the constructor is a no-op in the
        // mockable jar and the ViewModel never calls methods on it.
        viewModel = MainViewModel(
            buildFileRepository(twoValidRows),
            orchestrator,
            Application()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Drives the shared intent through the real conversion pipeline and waits
     * until the upload state machine reaches [status].
     */
    private fun shareAndAwait(status: GarminUploadStatus): MainUiState {
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.uploadStatus == status }
        return viewModel.uiState.value!!
    }

    /**
     * Advances the test scheduler until [predicate] holds on the current state.
     * The FileRepository pipeline hops onto real Dispatchers.IO, so a plain
     * advanceUntilIdle can drain before the IO continuation is posted back —
     * hence the poll loop with a generous deadline instead.
     */
    private fun pumpUntil(timeoutMs: Long = 10_000, predicate: (MainUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (state != null && predicate(state)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for the upload state machine; last state: ${viewModel.uiState.value}")
    }

    @Test
    fun conversion_success_uploads_and_skips_download_file_on_success() {
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)

        val state = shareAndAwait(GarminUploadStatus.SUCCESS)

        assertEquals(
            "the converted Garmin CSV must be uploaded exactly once",
            1,
            uploadCalls.size
        )
        assertTrue(
            "the uploaded payload must be the Garmin CSV",
            uploadCalls[0].startsWith("Body\nDate,Weight,Fat,BMI")
        )
        assertEquals(2, state.uploadedCount)
        assertNull(
            "Success must NOT surface a residual CSV (no Downloads file, no browser)",
            state.residualCsv
        )
        assertFalse(state.showLoginDialog)
    }

    @Test
    fun not_needed_shows_nothing_new_without_fallback() {
        uploadOutcome = UploadOutcome.NotNeeded

        val state = shareAndAwait(GarminUploadStatus.NOT_NEEDED)

        assertNull(
            "NotNeeded must NOT fall back to the CSV/browser path",
            state.residualCsv
        )
        assertEquals(0, state.residualCount)
        assertEquals(0, state.uploadedCount)
        assertTrue(state.uploadMessages.isEmpty())
        assertFalse(state.showLoginDialog)
    }

    @Test
    fun partial_saves_residual_and_reports_counts() {
        val residual = "Body\nDate,Weight,Fat,BMI\n2025-10-21,80.25,21.70,24.80"
        uploadOutcome = UploadOutcome.Partial(
            uploadedCount = 1,
            residualCsv = residual,
            residualCount = 1,
            messages = listOf("row 2 rejected by Garmin")
        )

        val state = shareAndAwait(GarminUploadStatus.PARTIAL)

        assertEquals(
            "the residual CSV must be handed to the Downloads/browser path",
            residual,
            state.residualCsv
        )
        assertEquals(1, state.uploadedCount)
        assertEquals(1, state.residualCount)
        assertEquals(listOf("row 2 rejected by Garmin"), state.uploadMessages)
    }

    @Test
    fun fallback_saves_residual_and_opens_browser() {
        val residual = "Body\nDate,Weight,Fat,BMI\n2025-10-25,79.65,21.90,24.60\n2025-10-21,80.25,21.70,24.80"
        uploadOutcome = UploadOutcome.Fallback(
            residualCsv = residual,
            residualCount = 2,
            reason = "upload API unavailable"
        )

        val state = shareAndAwait(GarminUploadStatus.FALLBACK)

        assertEquals(
            "the full residual CSV must reach the save+open machinery",
            residual,
            state.residualCsv
        )
        assertEquals(2, state.residualCount)
        assertEquals(listOf("upload API unavailable"), state.uploadMessages)
    }

    @Test
    fun login_required_shows_dialog_then_upload_retries_after_login() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        assertEquals(
            GarminUploadStatus.LOGIN_REQUIRED,
            viewModel.uiState.value!!.uploadStatus
        )

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        val state = viewModel.uiState.value!!
        assertTrue("login success must flip the connected flag", state.garminConnected)
        assertFalse("login success must close the dialog", state.showLoginDialog)
        assertEquals(
            Triple("user@example.com", "hunter2", null),
            loginCalls.single()
        )
        assertEquals(
            "login success must retry the upload with the SAME csv payload",
            2,
            uploadCalls.size
        )
        assertEquals("retry payload must be identical", uploadCalls[0], uploadCalls[1])
    }

    @Test
    fun needs_mfa_flow_resumes_with_code() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.NeedsMfa
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.loginNeedsMfa }

        val mfaState = viewModel.uiState.value!!
        assertTrue("MFA prompt must stay inside the dialog", mfaState.showLoginDialog)

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        viewModel.loginToGarmin("user@example.com", "hunter2", "123456")
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        val state = viewModel.uiState.value!!
        assertFalse(state.showLoginDialog)
        assertFalse(state.loginNeedsMfa)
        assertNull(state.loginError)
        assertEquals(
            Triple("user@example.com", "hunter2", "123456"),
            loginCalls.last()
        )
        assertEquals("MFA resume must retry the upload", 2, uploadCalls.size)
    }

    @Test
    fun resend_code_calls_login_again() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.NeedsMfa
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.loginNeedsMfa }

        // Resend = re-run login with the same credentials and no code
        viewModel.loginToGarmin("user@example.com", "hunter2", null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "resend must re-call login with the same credentials and no code",
            listOf(
                Triple("user@example.com", "hunter2", null),
                Triple("user@example.com", "hunter2", null)
            ),
            loginCalls
        )
        val state = viewModel.uiState.value!!
        assertTrue("dialog must stay open across resend", state.showLoginDialog)
        assertTrue(state.loginNeedsMfa)
        // No upload retry may happen while still signed out
        assertEquals(1, uploadCalls.size)
    }

    @Test
    fun login_cancel_falls_back_to_csv() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        viewModel.onLoginCancelled()
        pumpUntil { it.uploadStatus == GarminUploadStatus.FALLBACK }

        val state = viewModel.uiState.value!!
        assertFalse(state.showLoginDialog)
        assertEquals(
            "cancel must route the whole conversion to the residual path",
            GarminUploadStatus.FALLBACK,
            state.uploadStatus
        )
        assertNotNull(state.residualCsv)
        assertEquals(
            "residual = everything the repository reports as not yet journaled",
            2,
            state.residualCount
        )
        assertEquals(
            "no retry may happen after cancel",
            1,
            uploadCalls.size
        )
        assertEquals(
            "cancel must go through the repository's journal-filtered fallback",
            listOf(uploadCalls[0] to "Login cancelled"),
            fallbackCalls
        )
        assertEquals(
            "the residual must be exactly the csv that was about to be uploaded",
            uploadCalls[0],
            state.residualCsv
        )
    }

    /**
     * Pins the login-cancel race (task .6 finding 2): cancelling while the
     * login coroutine is still suspended must kill its Job, so a late Success
     * can neither flip state nor run the upload retry.
     */
    @Test
    fun login_cancel_cancels_inflight_job_and_skips_upload() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        // Launch the login but do NOT advance the scheduler first: the login
        // coroutine is created-and-suspended — exactly the window a fast
        // dialog cancel hits.
        viewModel.loginToGarmin("user@example.com", "hunter2")
        viewModel.onLoginCancelled()
        testDispatcher.scheduler.advanceUntilIdle()
        pumpUntil { it.uploadStatus == GarminUploadStatus.FALLBACK }

        assertEquals(
            "cancel must kill the login job before it reaches the orchestrator",
            0,
            loginCalls.size
        )
        assertEquals(
            "no upload retry may happen after cancel",
            1,
            uploadCalls.size
        )
        val state = viewModel.uiState.value!!
        assertFalse("late Success must not flip the connected flag", state.garminConnected)
        assertFalse("late Success must not reopen the dialog", state.showLoginDialog)
        assertNull("late Success must not surface a login error", state.loginError)
        assertEquals(GarminUploadStatus.FALLBACK, state.uploadStatus)
        assertNotNull("the fallback residual must ship", state.residualCsv)
    }

    /**
     * The orchestrator throwing across uploadNewFromCsv (it never should, but
     * defensively) routes the conversion to the journal-filtered residual.
     */
    @Test
    fun orchestrator_exception_falls_back_to_residual() {
        uploadException = RuntimeException("upload pipeline exploded")

        val state = shareAndAwait(GarminUploadStatus.FALLBACK)

        assertEquals(1, uploadCalls.size)
        assertEquals(
            "the exception path must ask the repository for the residual",
            listOf(uploadCalls[0] to "upload pipeline exploded"),
            fallbackCalls
        )
        assertEquals(
            "residual is what the repository handed back",
            uploadCalls[0],
            state.residualCsv
        )
        assertEquals(2, state.residualCount)
        assertEquals(listOf("upload pipeline exploded"), state.uploadMessages)
    }

    /**
     * Audit gap: the uploaded payload must equal the FULL Garmin CSV for the
     * two-row Goodvibes fixture — exact string equality, not a startsWith.
     */
    @Test
    fun conversion_success_payload_exact_equality() {
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)

        shareAndAwait(GarminUploadStatus.SUCCESS)

        val expected = listOf(
            "Body",
            "Date,Weight,Fat,BMI",
            "2025-10-25,79.65,21.90,24.60",
            "2025-10-21,80.25,21.70,24.80"
        ).joinToString("\n")
        assertEquals(
            "the uploaded payload must be the EXACT two-row Garmin CSV",
            expected,
            uploadCalls.single()
        )
    }

    @Test
    fun login_failed_shows_error_stays_in_dialog() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.handleShareIntent(TestUri())
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.Failed(reason = "bad credentials")
        viewModel.loginToGarmin("user@example.com", "wrong-password")
        pumpUntil { it.loginError != null }

        val state = viewModel.uiState.value!!
        assertEquals("bad credentials", state.loginError)
        assertTrue("Failed must keep the dialog open", state.showLoginDialog)
        assertFalse(state.loginNeedsMfa)
        // A failed login must not trigger an upload retry
        assertEquals(1, uploadCalls.size)
    }

    @Test
    fun connect_button_visibility_follows_token_presence() {
        assertFalse(
            "no stored token -> Connect Garmin entry point visible",
            viewModel.uiState.value!!.garminConnected
        )

        storedToken = true
        val orchestrator: GarminUploadOrchestrator =
            mock(GarminUploadOrchestrator::class.java) { invocation ->
                when (invocation.method.name) {
                    "hasStoredToken" -> storedToken
                    else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        val connectedViewModel =
            MainViewModel(buildFileRepository(twoValidRows), orchestrator, Application())
        assertTrue(
            "stored token -> connected status instead of the button",
            connectedViewModel.uiState.value!!.garminConnected
        )
    }
}
