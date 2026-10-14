package com.example.tefal2garmin

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.tefal2garmin.garmin.GarminAuthClient
import com.example.tefal2garmin.garmin.GarminTokens
import com.example.tefal2garmin.garmin.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only live spike for the Garmin DI OAuth2 auth client (task
 * tefal2garmin-garmin-api-import-j5e.1): runs one DI login (and, when a code
 * is supplied via -PGARMINSPIKE_MFA_CODE, the MFA resume) with credentials
 * from the debug-only GARMINSPIKE_* BuildConfig fields.
 *
 * Output is logcat-only; secret values (password, tokens, tickets) are never
 * logged — only lengths and client ids. Watch with:
 *   adb logcat -s MainActivity GarminAuth GarminHttp
 */
object GarminAuthSpike {

    private const val TAG = "MainActivity"

    fun trigger(activity: MainActivity) {
        val email = BuildConfig.GARMINSPIKE_EMAIL
        val password = BuildConfig.GARMINSPIKE_PASSWORD
        if (email.isBlank() || password.isBlank()) {
            Log.w(TAG, "Garmin spike: credentials absent; rebuild with " +
                "-PGARMINSPIKE_EMAIL=... -PGARMINSPIKE_PASSWORD=...")
            return
        }
        Log.i(TAG, "Garmin spike: starting DI OAuth2 login")
        val client = GarminAuthClient()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (val result = client.login(email, password)) {
                    is LoginResult.NeedsMfa -> {
                        Log.i(TAG, "Garmin spike: MFA required " +
                            "(method=${result.clientState.mfaMethod})")
                        val mfaCode = BuildConfig.GARMINSPIKE_MFA_CODE
                        if (mfaCode.isBlank()) {
                            Log.i(TAG, "Garmin spike: no -PGARMINSPIKE_MFA_CODE supplied; " +
                                "stopping at MFA")
                        } else {
                            logSpikeTokens(
                                "after MFA resume",
                                client.resumeLogin(result.clientState, mfaCode)
                            )
                        }
                    }
                    is LoginResult.Success -> logSpikeTokens("login", result.tokens)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Garmin spike failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun logSpikeTokens(stage: String, tokens: GarminTokens) {
        // Lengths and ids only — token values must never reach logcat.
        Log.i(TAG, "Garmin spike OK ($stage): di_client_id=${tokens.diClientId}, " +
            "di_token.length=${tokens.diToken.length}, " +
            "di_refresh_token.length=${tokens.diRefreshToken.length}")
    }
}
