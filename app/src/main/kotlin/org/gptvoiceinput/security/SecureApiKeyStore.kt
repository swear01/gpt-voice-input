package org.gptvoiceinput.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key storage: ciphertext + IV in SharedPreferences, key material held by
 * the Android Keystore (non-exportable) through [AndroidKeyStoreEncryptor].
 *
 * The plaintext key is only ever in memory during save/load; it is never
 * written to prefs, JSON, logs, or crash reports.
 */
class SecureApiKeyStore(
    context: Context,
    private val encryptor: SecretEncryptor = AndroidKeyStoreEncryptor(),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when a key has been saved; never exposes the key itself. */
    fun hasKey(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        val blob = encryptor.encrypt(apiKey)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(blob.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(blob.ciphertext, Base64.NO_WRAP))
            .apply()
    }

    /** Returns the plaintext key or null when none is stored / decryptable. */
    fun load(): String? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            encryptor.decrypt(
                Base64.decode(ivB64, Base64.NO_WRAP),
                Base64.decode(ctB64, Base64.NO_WRAP),
            )
        } catch (e: Exception) {
            // Corrupted storage or Keystore key rotated away: treat as absent.
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).apply()
    }

    companion object {
        private const val PREFS_NAME = "secure_api_key"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
    }
}

/** Encrypted payload: random IV + ciphertext. */
data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

interface SecretEncryptor {
    fun encrypt(plaintext: String): EncryptedBlob

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): String
}

/**
 * AES-256-GCM with a non-exportable key inside the Android Keystore.
 * Only usable on device / emulator (Robolectric has no AndroidKeyStore
 * provider); the store logic itself is tested with a fake encryptor.
 */
class AndroidKeyStoreEncryptor : SecretEncryptor {

    override fun encrypt(plaintext: String): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedBlob(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
        )
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gpt_voice_input_api_key"
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
