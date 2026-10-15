package com.zamotkin.extrahealthgarmin.garmin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence seam for [GarminTokenStore]. [SharedPreferencesTokenPersistence]
 * is the production adapter; tests inject an in-memory fake (Robolectric
 * cannot run on this repo's JDK 26 toolchain — see app/build.gradle).
 */
interface TokenPersistence {
    fun read(): String?
    fun write(value: String)
    fun remove()
}

/** Stores the encrypted token blob in SharedPreferences under [TOKEN_KEY]. */
class SharedPreferencesTokenPersistence(context: Context) : TokenPersistence {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(TOKEN_KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(TOKEN_KEY, value).commit()
    }

    override fun remove() {
        prefs.edit().remove(TOKEN_KEY).commit()
    }

    companion object {
        private const val PREFS_NAME = "garmin_auth"
        private const val TOKEN_KEY = "garmin_di_tokens"
    }
}

/**
 * Encrypt-then-persist storage for Garmin DI OAuth2 tokens.
 *
 * Fail-safe by contract: any crypto or format failure degrades to "no tokens"
 * plus a Log.w — the app then simply requires a fresh login. It never throws
 * and never crashes on corrupted state. Only DI tokens are stored here;
 * email/password are never persisted anywhere.
 */
class GarminTokenStore(
    private val persistence: TokenPersistence,
    private val crypto: TokenCrypto
) {

    /** Encrypts and persists [tokens]. Crypto failures are logged and swallowed. */
    fun save(tokens: GarminTokens) {
        try {
            val json = JSONObject()
                .put(JSON_KEY_DI_TOKEN, tokens.diToken)
                .put(JSON_KEY_DI_REFRESH_TOKEN, tokens.diRefreshToken)
                .put(JSON_KEY_DI_CLIENT_ID, tokens.diClientId)
            persistence.write(crypto.encrypt(json.toString()))
        } catch (e: TokenCryptoException) {
            Log.w(TAG, "DI tokens not persisted (crypto failure): ${e.message}")
        }
    }

    /**
     * Returns the stored tokens, or null when nothing is stored, the blob is
     * corrupted, or the crypto layer is unavailable. Never throws.
     */
    fun load(): GarminTokens? {
        val blob = persistence.read() ?: return null
        return try {
            val json = JSONObject(crypto.decrypt(blob))
            GarminTokens(
                diToken = json.getString(JSON_KEY_DI_TOKEN),
                diRefreshToken = json.optString(JSON_KEY_DI_REFRESH_TOKEN),
                diClientId = json.optString(JSON_KEY_DI_CLIENT_ID)
            )
        } catch (e: TokenCryptoException) {
            Log.w(TAG, "DI tokens unreadable (crypto failure); treating as absent: ${e.message}")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "DI token blob corrupted; treating as absent: ${e.message}")
            null
        } catch (e: Exception) {
            // Defensive last resort: no crash paths from stored data, ever.
            Log.w(TAG, "DI token blob unreadable; treating as absent: ${e.message}")
            null
        }
    }

    fun clear() {
        persistence.remove()
    }

    companion object {
        private const val TAG = "GarminTokenStore"
        private const val JSON_KEY_DI_TOKEN = "di_token"
        private const val JSON_KEY_DI_REFRESH_TOKEN = "di_refresh_token"
        private const val JSON_KEY_DI_CLIENT_ID = "di_client_id"
    }
}
