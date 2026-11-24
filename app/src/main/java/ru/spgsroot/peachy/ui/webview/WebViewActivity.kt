package ru.spgsroot.peachy.ui.webview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import android.view.KeyEvent
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.MainActivity
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.browser.BrowserSessionStore
import ru.spgsroot.peachy.data.browser.BrowserStore
import ru.spgsroot.peachy.data.session.AppSessionStore
import ru.spgsroot.peachy.domain.dsp.HapticInputRouter
import ru.spgsroot.peachy.domain.dsp.HapticSource
import ru.spgsroot.peachy.nsfw.NsfwGate
import ru.spgsroot.peachy.playback.service.WebViewAudioCaptureService
import ru.spgsroot.peachy.security.AuthManager
import ru.spgsroot.peachy.security.AuthSession
import ru.spgsroot.peachy.security.applyScreenshotPolicy
import ru.spgsroot.peachy.ui.theme.PeachyTheme

/**
 * The in-app browser. Page audio can drive the haptics: the capture is started on request
 * (the launching dialog or the menu switch) and always stops when the screen is left.
 *
 * The screen belongs to the unlocked session. After process death Android may restore this
 * activity alone, so the password gate is checked here before any WebView exists: a locked session
 * hands control back to the root screen, which owns authentication, and never draws a page.
 */
@AndroidEntryPoint
class WebViewActivity : ComponentActivity() {

    @Inject
    lateinit var hapticInputRouter: HapticInputRouter

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var authSession: AuthSession

    @Inject
    lateinit var appSession: AppSessionStore

    @Inject
    lateinit var nsfwGate: NsfwGate

    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var controller: BrowserController
    private lateinit var session: BrowserSessionStore
    private var browserReady = false
    private var handledLaunchUrl = false
    private var closedByUser = false
    private var returningToAuth = false
    private var captureStarted = false
    private var captureOn by mutableStateOf(false)
    /**
     * Predictive back finishes this activity unless an app callback sits above the system one.
     * WebView may register its own later; [claimHistoryBack] puts this back on top.
     */
    private var historyBack: Any? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestMediaProjectionConsent()
        } else {
            Toast.makeText(this, "RECORD_AUDIO permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        ensureRecordAudioPermission()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startAudioCaptureService(result.resultCode, data)
        } else {
            Toast.makeText(this, "Audio capture consent was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Page content stays out of adb: no remote inspection of this WebView.
        WebView.setWebContentsDebuggingEnabled(false)
        window.applyScreenshotPolicy(this)
        lifecycleScope.launch {
            nsfwGate.enabled.collect { enabled ->
                if (enabled == false) stopCapture()
            }
        }
        // The app is dark in every system theme: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        claimHistoryBack()
        session = BrowserSessionStore.forApp(this)
        handledLaunchUrl = savedInstanceState?.getBoolean(STATE_HANDLED_LAUNCH_URL) == true

        lifecycleScope.launch {
            // Nothing protected is composed until the session is known to be unlocked.
            if (!sessionUnlocked()) return@launch
            try {
                viewModel.prepareBlocker()
            } catch (_: java.io.IOException) {
                Toast.makeText(this@WebViewActivity, R.string.browser_shield_load_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (!sessionUnlocked() || isFinishing || isDestroyed) return@launch
            startBrowser(savedInstanceState?.getIntArray(STATE_PREVIOUS_TABS))
            // Only an explicit launch asks for capture; a recreated activity must not re-prompt for
            // the microphone and screen recording on its own.
            if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CAPTURE, false)) {
                lifecycleScope.launch {
                    val enabled = nsfwGate.enabled.first { it != null } == true
                    if (enabled && !isFinishing && !isDestroyed) beginAudioCaptureFlow()
                }
            }
        }
    }

    /** True when a password-protected session is unlocked; otherwise control returns to the root. */
    private suspend fun sessionUnlocked(): Boolean {
        val protected = authManager.isPasswordSetFlow().first()
        if (protected && !authSession.unlocked.value) {
            returnToAuth()
            return false
        }
        return true
    }

    /** Rebuilds the browser from the durable session and shows it; called once, after the gate. */
    private fun startBrowser(previousTabIds: IntArray?) {
        val restored = session.load()
        controller = BrowserController(
            activity = this,
            engine = viewModel.engine,
            onBlocked = viewModel::countBlock,
            blockerOff = { host -> !viewModel.mode.value.network || BrowserStore.isBlockerOff(viewModel.allowedNow(), host) },
            mode = { viewModel.mode.value },
            onVisit = viewModel::recordVisit,
            session = session,
            restored = restored
        )
        if (previousTabIds != null) controller.restorePreviousTabs(previousTabIds)
        // A link the app was asked to open is a deliberate destination: it gets its own tab instead
        // of replacing the restored one. A first launch still reuses the single blank tab.
        val url = if (handledLaunchUrl) "" else intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (url.isNotEmpty()) {
            val active = controller.active
            if (restored == null && active.url.isEmpty() && active.webView == null) {
                controller.navigate(active, url)
            } else {
                val before = controller.tabs.size
                val opened = controller.newTab(url)
                // The tab ceiling may refuse a new tab; the address still has to open somewhere.
                if (controller.tabs.size == before) controller.navigate(opened, url)
            }
        }
        handledLaunchUrl = true
        browserReady = true
        // The browser is the screen the session should come back to after the next unlock or kill.
        appSession.browserVisible = true
        appSession.setBrowserOpen(true)
        setContent {
            val nsfw by nsfwGate.enabled.collectAsStateWithLifecycle()
            PeachyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BrowserScreen(
                        controller = controller,
                        viewModel = viewModel,
                        captureOn = captureOn,
                        captureAvailable = nsfw == true,
                        onCaptureChange = { wanted -> if (wanted) beginAudioCaptureFlow() else stopCapture() },
                        onClose = {
                            // Closing the browser keeps the tab strip for the next open; only the
                            // "come back after unlock" marker is cleared.
                            closedByUser = true
                            controller.persist()
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HANDLED_LAUNCH_URL, handledLaunchUrl)
        if (browserReady) outState.putIntArray(STATE_PREVIOUS_TABS, controller.previousTabIds())
    }

    override fun onResume() {
        super.onResume()
        if (!browserReady) return
        appSession.setBrowserOpen(true)
        // Coming back to a browser that is still on screen after the session locked must not leave
        // page content visible without the password screen.
        lifecycleScope.launch { sessionUnlocked() }
    }

    override fun onPause() {
        if (browserReady) controller.persist()
        super.onPause()
        if (browserReady) viewModel.saveBlockCount()
    }

    override fun onStop() {
        stopCapture()
        if (browserReady) controller.persist()
        super.onStop()
    }

    override fun onDestroy() {
        stopCapture()
        // A deliberate close ends the "return to the browser" state; a lock finish does not, so the
        // browser comes back after the password is entered.
        if (closedByUser) appSession.setBrowserOpen(false)
        if (browserReady) controller.destroyAll()
        if (!isChangingConfigurations) appSession.browserVisible = false
        if (Build.VERSION.SDK_INT >= 33) {
            (historyBack as? OnBackInvokedCallback)?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        super.onDestroy()
    }

    /**
     * Leaves the browser without drawing anything: the root activity owns authentication, and the
     * durable session keeps the browser as the destination to come back to once unlocked.
     */
    private fun returnToAuth() {
        if (returningToAuth) return
        returningToAuth = true
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (browserReady && event.keyCode == KeyEvent.KEYCODE_FORWARD) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val tab = controller.active
                if (tab.canGoForward) controller.goForward(tab)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Re-registers after a WebView attaches, so its own callback cannot outrank history. */
    internal fun claimHistoryBack() {
        if (Build.VERSION.SDK_INT < 33 || isDestroyed) return
        val callback = historyBack as? OnBackInvokedCallback ?: HistoryBack().also { historyBack = it }
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )
    }

    private inner class HistoryBack : OnBackInvokedCallback {
        override fun onBackInvoked() {
            val insets = window.decorView.rootWindowInsets
            if (Build.VERSION.SDK_INT >= 30 && insets?.isVisible(WindowInsets.Type.ime()) == true) {
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                return
            }
            if (!browserReady) {
                finish()
                return
            }
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun stopCapture() {
        if (captureStarted) {
            runCatching {
                stopService(WebViewAudioCaptureService.stopIntent(applicationContext))
            }
        }
        hapticInputRouter.release(HapticSource.WEBVIEW)
        captureStarted = false
        captureOn = false
    }

    private fun beginAudioCaptureFlow() {
        if (!nsfwGate.allows()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "WebView audio capture requires Android 10+", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ensureRecordAudioPermission()
    }

    private fun ensureRecordAudioPermission() {
        if (!nsfwGate.allows()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestMediaProjectionConsent()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestMediaProjectionConsent() {
        if (!nsfwGate.allows()) return
        val mediaProjectionManager = applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startAudioCaptureService(resultCode: Int, data: Intent) {
        if (!nsfwGate.allows()) return
        captureStarted = true
        captureOn = true
        ContextCompat.startForegroundService(
            applicationContext,
            WebViewAudioCaptureService.captureIntent(
                context = applicationContext,
                resultCode = resultCode,
                data = data
            )
        )
    }

    companion object {
        private const val STATE_HANDLED_LAUNCH_URL = "handledLaunchUrl"
        private const val STATE_PREVIOUS_TABS = "previousTabs"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CAPTURE = "extra_capture"
    }
}
