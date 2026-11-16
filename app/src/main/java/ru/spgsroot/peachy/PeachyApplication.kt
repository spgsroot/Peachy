package ru.spgsroot.peachy

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.data.repository.SettingsRepository
import ru.spgsroot.peachy.locale.LocaleManager
import ru.spgsroot.peachy.security.AppLockGuard
import ru.spgsroot.peachy.translate.OrtBubbleDetector
import javax.inject.Inject

@HiltAndroidApp
class PeachyApplication : Application() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var bubbleDetector: OrtBubbleDetector

    @Inject
    lateinit var appLockGuard: AppLockGuard

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Initialize locale manager with saved locale
        LocaleManager.initialize(base)
    }

    override fun onCreate() {
        super.onCreate()
        appLockGuard.install(this, MainActivity::class.java)
        initializeSettings()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) bubbleDetector.release()
    }

    private fun initializeSettings() {
        applicationScope.launch {
            settingsRepository.initializeDefaults()
            // Load saved language and apply it
            val settings = settingsRepository.getSettings().firstOrNull()
            val languageCode = settings?.languageCode ?: LocaleManager.getSavedLocale(this@PeachyApplication)
            LocaleManager.setLocale(this@PeachyApplication, languageCode)
        }
    }
}
