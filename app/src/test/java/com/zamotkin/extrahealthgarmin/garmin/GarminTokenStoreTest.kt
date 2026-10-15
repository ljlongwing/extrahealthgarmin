package com.zamotkin.extrahealthgarmin.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GarminTokenStore tests. Robolectric cannot run on this repo's JDK 26
 * toolchain (see app/build.gradle), so the SharedPreferences adapter is
 * replaced by an in-memory [TokenPersistence] fake and the crypto by a
 * [TokenCrypto] fake — the store's logic (roundtrip, clear, fail-safe on
 * corruption/crypto failure) is fully covered either way.
 */
class GarminTokenStoreTest {

    private class FakeCrypto : TokenCrypto {
        var failing = false

        override fun encrypt(plaintext: String): String {
            if (failing) throw TokenCryptoException("keystore unavailable (test)")
            return "enc($plaintext)"
        }

        override fun decrypt(stored: String): String {
            if (failing) throw TokenCryptoException("keystore unavailable (test)")
            if (stored == CORRUPTED_BLOB) return "not-json-at-all{{{"
            return stored.removePrefix("enc(").removeSuffix(")")
        }
    }

    private class FakePersistence : TokenPersistence {
        var stored: String? = null
        override fun read(): String? = stored
        override fun write(value: String) {
            stored = value
        }
        override fun remove() {
            stored = null
        }
    }

    private val crypto = FakeCrypto()
    private val persistence = FakePersistence()
    private val store = GarminTokenStore(persistence, crypto)

    @Test
    fun `save_load_roundtrip`() {
        val tokens = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )

        store.save(tokens)

        // The persisted blob must have gone through the crypto layer...
        assertTrue("blob must be the encrypted form, was: ${persistence.stored}",
            persistence.stored!!.startsWith("enc("))
        assertFalse("blob must not be raw plaintext", persistence.stored == rawJson(tokens))

        // ...and come back out unchanged.
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(tokens, loaded)
    }

    @Test
    fun `clear_wipes_stored_tokens`() {
        store.save(GarminTokens("a", "b", "c"))
        assertNotNull(store.load())

        store.clear()

        assertNull("load after clear must return null", store.load())
        assertNull("persistence must be emptied", persistence.stored)
    }

    @Test
    fun `corrupted_blob_treated_as_absent`() {
        persistence.stored = CORRUPTED_BLOB

        val loaded = store.load()

        assertNull("corrupted blob must read as absent, was $loaded", loaded)
    }

    @Test
    fun `crypto_failure_treated_as_absent_and_save_fail_safe`() {
        // Loading through a broken keystore degrades to "no tokens"...
        persistence.stored = "enc(whatever)"
        crypto.failing = true
        assertNull("crypto failure must read as absent", store.load())

        // ...and saving through it never throws and never persists plaintext.
        persistence.stored = null
        store.save(GarminTokens("a", "b", "c"))
        assertNull("save during crypto failure must not persist anything",
            persistence.stored)
        try {
            store.load()
        } catch (e: Exception) {
            fail("load must never throw, got $e")
        }
    }

    private fun rawJson(tokens: GarminTokens): String =
        "{\"di_token\":\"${tokens.diToken}\"," +
            "\"di_refresh_token\":\"${tokens.diRefreshToken}\"," +
            "\"di_client_id\":\"${tokens.diClientId}\"}"

    companion object {
        private const val CORRUPTED_BLOB = "!!!not-base64-ciphertext!!!"
    }
}
