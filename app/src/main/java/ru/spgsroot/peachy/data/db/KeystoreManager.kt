package ru.spgsroot.peachy.data.db

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object KeystoreManager {
    private const val PREFS_NAME = "db_encryption_key"
    // Preserve the preference name and key used by existing installations.
    private const val KEY_ENTRY = "db_key_fallback"
    private const val KEY_BYTES = 32

    @Synchronized
    fun getOrCreateKey(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        prefs.getString(KEY_ENTRY, null)?.let { stored ->
            return Base64.decode(stored, Base64.DEFAULT).also {
                check(it.size == KEY_BYTES) { "Invalid database encryption key" }
            }
        }

        check(!context.getDatabasePath(AppDatabase.DB_NAME).exists()) {
            "Database encryption key is missing"
        }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // The key must be durable before Room can create the database.
        check(prefs.edit().putString(KEY_ENTRY, Base64.encodeToString(key, Base64.NO_WRAP)).commit()) {
            "Could not persist the database encryption key"
        }
        return key
    }
}
