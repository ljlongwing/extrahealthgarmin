package com.zamotkin.extrahealthgarmin.garmin

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * String-to-string encryption seam for [GarminTokenStore]. Splitting the
 * interface lets unit tests run on the JVM (the AndroidKeyStore does not
 * exist there) while production uses [KeystoreAesCrypto].
 */
interface TokenCrypto {

    /** Encrypts [plaintext]; returns a single opaque storable string. */
    @Throws(TokenCryptoException::class)
    fun encrypt(plaintext: String): String

    /** Reverses [encrypt]; throws [TokenCryptoException] on any failure. */
    @Throws(TokenCryptoException::class)
    fun decrypt(stored: String): String
}

/**
 * AES/GCM encryption backed by the AndroidKeyStore. The key never leaves
 * secure hardware; the persisted blob is `Base64(iv || ciphertext+tag)`.
 *
 * AndroidKeyStore AES keys require API 23+; on older devices every operation
 * throws [TokenCryptoException], which the token store fail-safes to "no
 * tokens stored" (login required) rather than crashing.
 */
class KeystoreAesCrypto : TokenCrypto {

    override fun encrypt(plaintext: String): String = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw TokenCryptoException(
                "AndroidKeyStore AES keys require API 23+; running on API ${Build.VERSION.SDK_INT}"
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    } catch (e: TokenCryptoException) {
        throw e
    } catch (e: Exception) {
        throw TokenCryptoException("Token encryption failed: ${e.message}", e)
    }

    override fun decrypt(stored: String): String = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw TokenCryptoException(
                "AndroidKeyStore AES keys require API 23+; running on API ${Build.VERSION.SDK_INT}"
            )
        }
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size <= GCM_IV_LENGTH_BYTES) {
            throw TokenCryptoException("Encrypted token blob too short (${blob.size} bytes)")
        }
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: TokenCryptoException) {
        throw e
    } catch (e: Exception) {
        throw TokenCryptoException("Token decryption failed: ${e.message}", e)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        Log.d(TAG, "Generating AndroidKeyStore AES key '$KEY_ALIAS'")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreAesCrypto"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "extrahealthgarmin_di_tokens"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
