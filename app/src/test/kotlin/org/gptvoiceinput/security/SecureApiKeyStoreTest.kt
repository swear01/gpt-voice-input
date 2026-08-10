package org.gptvoiceinput.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SecureApiKeyStore logic tests. The Android Keystore provider does not exist
 * under Robolectric, so the crypto seam is replaced with a fake encryptor
 * (still proving: save/load round trip, no plaintext in prefs, overwrite,
 * corrupt data -> absent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureApiKeyStoreTest {

    /** Reversible fake encryption: base64(rev(plaintext)) — never the raw value. */
    private class FakeEncryptor : SecretEncryptor {
        override fun encrypt(plaintext: String): EncryptedBlob {
            val bytes = plaintext.reversed().toByteArray(Charsets.UTF_8)
            return EncryptedBlob(iv = byteArrayOf(1, 2, 3), ciphertext = bytes)
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): String =
            String(ciphertext, Charsets.UTF_8).reversed()
    }

    private fun store() = SecureApiKeyStore(
        ApplicationProvider.getApplicationContext(),
        encryptor = FakeEncryptor(),
    )

    @Test
    fun `save then load returns the same value`() {
        val s = store()
        s.clear()
        assertFalse(s.hasKey())
        assertNull(s.load())

        s.save("sk-robolectric-test-123")
        assertTrue(s.hasKey())
        assertEquals("sk-robolectric-test-123", s.load())
        s.clear()
        assertFalse(s.hasKey())
    }

    @Test
    fun `key is not stored in plaintext in preferences`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store().save("sk-plaintext-check")
        val prefs = context.getSharedPreferences(
            "secure_api_key",
            android.content.Context.MODE_PRIVATE,
        )
        assertFalse(
            "plaintext must not live in prefs",
            prefs.all.values.any { it.toString().contains("sk-plaintext-check") },
        )
        store().clear()
    }

    @Test
    fun `replacing the key overwrites the previous one`() {
        val s = store()
        s.save("sk-old")
        s.save("sk-new")
        assertEquals("sk-new", s.load())
        s.clear()
    }

    @Test
    fun `corrupted ciphertext is treated as absent and cleared`() {
        val s = store()
        s.save("sk-valid")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("secure_api_key", android.content.Context.MODE_PRIVATE)
            .edit().putString("ciphertext", "!!not-base64!!").apply()
        assertNull(s.load())
        assertFalse(s.hasKey())
    }
}
