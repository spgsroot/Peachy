package ru.spgsroot.peachy.data.update

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.updatesDataStore by preferencesDataStore("updates")
private val autoCheckKey = booleanPreferencesKey("auto_check_enabled")
private val lastAttemptKey = longPreferencesKey("last_check_attempt_ms")
private val downloadIdKey = longPreferencesKey("download_id")
private val downloadReleaseKey = stringPreferencesKey("download_release")

data class UpdatePreferencesState(
    val autoCheckEnabled: Boolean = false,
    val lastCheckAttemptMs: Long = 0,
    val downloadId: Long? = null,
    val downloadRelease: AppRelease? = null
)

/** Decode pending metadata as a pair: corrupt/incomplete JSON must not crash startup or resume an orphan ID. */
internal fun updatePreferencesState(preferences: Preferences): UpdatePreferencesState {
    val release = try {
        preferences[downloadReleaseKey]?.let { Json.decodeFromString<AppRelease>(it) }
    } catch (_: IllegalArgumentException) {
        null
    }
    val id = preferences[downloadIdKey]?.takeIf { it > 0 && release != null }
    return UpdatePreferencesState(
        autoCheckEnabled = preferences[autoCheckKey] ?: false,
        lastCheckAttemptMs = preferences[lastAttemptKey] ?: 0,
        downloadId = id,
        downloadRelease = release.takeIf { id != null }
    )
}

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val settings: Flow<UpdatePreferencesState> = context.updatesDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::updatePreferencesState)

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        context.updatesDataStore.edit { it[autoCheckKey] = enabled }
    }

    suspend fun recordCheckAttempt(nowMillis: Long) {
        context.updatesDataStore.edit { it[lastAttemptKey] = nowMillis }
    }

    suspend fun saveDownload(id: Long, release: AppRelease) {
        context.updatesDataStore.edit {
            it[downloadIdKey] = id
            it[downloadReleaseKey] = Json.encodeToString(release)
        }
    }

    suspend fun clearDownload() {
        context.updatesDataStore.edit {
            it.remove(downloadIdKey)
            it.remove(downloadReleaseKey)
        }
    }
}
