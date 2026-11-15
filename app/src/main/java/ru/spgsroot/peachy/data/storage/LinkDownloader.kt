package ru.spgsroot.peachy.data.storage

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Downloads links that other apps handed to Peachy. The work runs on the app's own scope rather
 * than on a screen, so it carries on when the user goes back to the app that sent the link; the
 * file lands in the library through [VideoImporter], which also catches duplicates.
 */
@Singleton
class LinkDownloader @Inject constructor(private val urlImporter: UrlImporter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finished = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 16)

    /** One entry per finished link, for a toast while the app is on screen. */
    val results: SharedFlow<Result<Unit>> = finished.asSharedFlow()

    fun start(url: String) {
        scope.launch {
            val result = try {
                urlImporter.importLink(url)
            } catch (e: Exception) {
                Result.failure(e)
            }
            finished.emit(result)
        }
    }
}
