package ru.spgsroot.peachy.data.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for the small, app-owned browser session file. */
internal interface BrowserSessionCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with an Android Keystore key in the app and an injectable key in local tests. */
internal class AesGcmBrowserSessionCipher(private val key: SecretKey) : BrowserSessionCipher {
    override fun seal(plain: ByteArray): ByteArray {
        // Keystore rejects a caller IV (CALLER_NONCE_PROHIBITED) and generates its own.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        return ByteBuffer.allocate(HEADER_BYTES + iv.size + encrypted.size)
            .putInt(SEALED_MAGIC)
            .putInt(SEALED_VERSION)
            .put(iv)
            .put(encrypted)
            .array()
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER_BYTES + IV_BYTES + TAG_BYTES) throw IOException("Truncated browser session")
        val input = ByteBuffer.wrap(sealed)
        if (input.int != SEALED_MAGIC || input.int != SEALED_VERSION) throw IOException("Unknown browser session encryption")
        val iv = ByteArray(IV_BYTES).also(input::get)
        val encrypted = ByteArray(input.remaining()).also(input::get)
        return cipher(Cipher.DECRYPT_MODE, iv).doFinal(encrypted)
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        }

    companion object {
        private const val KEY_ALIAS = "peachy_browser_session"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 8
        internal const val SEALED_MAGIC = 0x50434853
        internal const val SEALED_VERSION = 1
        internal const val MAX_OVERHEAD = HEADER_BYTES + IV_BYTES + TAG_BYTES

        fun forApp(context: Context): AesGcmBrowserSessionCipher {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
            return AesGcmBrowserSessionCipher(key)
        }
    }
}
