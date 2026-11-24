package ru.spgsroot.peachy.nsfw

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.data.repository.SettingsRepository
import ru.spgsroot.peachy.device.buttplug.ButtplugConnectionManager
import ru.spgsroot.peachy.device.buttplug.CommandSender
import ru.spgsroot.peachy.playback.service.WebViewAudioCaptureService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows the stored switch. Turning it off stops device commands and page-audio capture
 * at once. Saved manga, notes and device preferences are left where they are.
 */
@Singleton
class NsfwEnforcer @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val gate: NsfwGate,
    private val commandSender: CommandSender,
    private val connectionManager: ButtplugConnectionManager,
    @ApplicationContext private val context: Context
) {
    @Volatile private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.getSettings().collect { settings ->
                if (settings == null) {
                    gate.publish(null)
                } else {
                    gate.publish(settings.nsfwEnabled)
                    if (!settings.nsfwEnabled) suppress()
                }
            }
        }
    }

    private fun suppress() {
        commandSender.stop()
        connectionManager.disconnect()
        context.stopService(WebViewAudioCaptureService.stopIntent(context))
    }
}
