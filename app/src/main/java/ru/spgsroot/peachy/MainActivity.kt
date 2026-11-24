package ru.spgsroot.peachy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.data.session.AppSessionStore
import ru.spgsroot.peachy.data.storage.DuplicateVideoException
import ru.spgsroot.peachy.locale.LocaleManager
import ru.spgsroot.peachy.nsfw.NsfwGate
import ru.spgsroot.peachy.nsfw.NsfwRoutes
import ru.spgsroot.peachy.security.AppLockGuard
import ru.spgsroot.peachy.security.applyScreenshotPolicy
import ru.spgsroot.peachy.security.AuthManager
import ru.spgsroot.peachy.security.AuthSession
import ru.spgsroot.peachy.documents.ui.DocumentRouteScreen
import ru.spgsroot.peachy.documents.ui.DocumentsScreen
import ru.spgsroot.peachy.ui.auth.AuthScreen
import ru.spgsroot.peachy.ui.dialog.DuplicateVideoHost
import ru.spgsroot.peachy.ui.gallery.GalleryScreen
import ru.spgsroot.peachy.ui.gallery.GalleryViewModel
import ru.spgsroot.peachy.ui.manga.GalleryDetailScreen
import ru.spgsroot.peachy.ui.manga.MangaHubScreen
import ru.spgsroot.peachy.ui.manga.MangaReaderScreen
import ru.spgsroot.peachy.ui.manga.TagBrowserScreen
import ru.spgsroot.peachy.ui.manga.searchCatalog
import ru.spgsroot.peachy.ui.onboarding.OnboardingScreen
import ru.spgsroot.peachy.ui.player.PlayerScreen
import ru.spgsroot.peachy.ui.player.PlayerViewModel
import ru.spgsroot.peachy.ui.settings.UpdateDialogHost
import ru.spgsroot.peachy.ui.settings.UpdateViewModel
import ru.spgsroot.peachy.ui.share.ShareImportViewModel
import ru.spgsroot.peachy.ui.theme.PeachyTheme
import ru.spgsroot.peachy.ui.webview.WebViewActivity
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var authSession: AuthSession

    @Inject
    lateinit var appLockGuard: AppLockGuard

    @Inject
    lateinit var appSession: AppSessionStore

    @Inject
    lateinit var nsfwGate: NsfwGate

    private val shareImportViewModel: ShareImportViewModel by viewModels()
    private var recreated = false
    @Volatile private var splashHeld = true

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getCurrentLocale()
        val locale = LocaleManager.getLocale(languageCode)
        val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            newBase.createConfigurationContext(
                newBase.resources.configuration.apply {
                    setLocales(android.os.LocaleList(locale))
                }
            )
        } else {
            newBase
        }
        super.attachBaseContext(context)
        LocaleManager.initialize(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().apply {
            setKeepOnScreenCondition { splashHeld }
            popBrandOnExit()
        }
        super.onCreate(savedInstanceState)
        recreated = savedInstanceState != null
        window.applyScreenshotPolicy(this)
        if (savedInstanceState == null) shareImportViewModel.enqueue(intent)
        // The app is dark in every system theme: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            PeachyTheme {
                AppNavigation(authManager, authSession, shareImportViewModel, appSession, nsfwGate) { splashHeld = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareImportViewModel.enqueue(intent)
    }

    override fun onResume() {
        super.onResume()
        appLockGuard.rootResumed(recreated)
        recreated = false
    }

    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        val picker = requestCode >= 0 && intent.action?.let(PICKER_ACTIONS::contains) == true
        if (picker) appLockGuard.pickerLaunched(requestCode)
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (failure: Throwable) {
            if (picker) appLockGuard.pickerResult(requestCode)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode >= 0) appLockGuard.pickerResult(requestCode)
    }

    private companion object {
        /**
         * Pickers whose result belongs to the screen that opened them: waiting for one is not
         * leaving the app. Includes the platform, AndroidX fallback and GMS photo pickers.
         */
        val PICKER_ACTIONS = setOf(
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            Intent.ACTION_CREATE_DOCUMENT,
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_PICK,
            "android.provider.action.PICK_IMAGES",
            "androidx.activity.result.contract.action.PICK_IMAGES",
            "com.google.android.gms.provider.action.PICK_IMAGES"
        )
    }
}

@Composable
fun AppNavigation(
    authManager: AuthManager,
    authSession: AuthSession,
    shareImports: ShareImportViewModel,
    appSession: AppSessionStore,
    nsfwGate: NsfwGate,
    onReveal: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val unlocked by authSession.unlocked.collectAsState()
    val passwordSetFlow = remember(authManager) { authManager.isPasswordSetFlow() }
    val onboardingFlow = remember(authManager) { authManager.isOnboardingCompletedFlow() }
    val passwordSetState by passwordSetFlow.collectAsState(initial = null)
    val onboardingCompletedState by onboardingFlow.collectAsState(initial = null)
    // Local copies: delegated properties cannot be smart cast, and both are read once per frame.
    val onboardingCompleted = onboardingCompletedState
    val passwordSet = passwordSetState
    val locked = passwordSet == true && !unlocked

    LaunchedEffect(passwordSet) {
        passwordSet?.let(authSession::setPasswordConfigured)
    }

    // A link handed over by another app keeps downloading in the background; say how each ended,
    // even while the password screen is up.
    LaunchedEffect(Unit) {
        shareImports.linkResults.collect { result ->
            val error = result.exceptionOrNull()
            when {
                error == null -> context.getString(R.string.download_success)
                error is DuplicateVideoException -> null // the duplicate dialog explains it
                else -> context.getString(R.string.error_download_failed)
            }?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }

    when {
        // The stored session is still being read; the splash screen covers this moment.
        onboardingCompleted == null || passwordSet == null -> Unit
        !onboardingCompleted -> {
            SideEffect(onReveal)
            OnboardingScreen(
                onComplete = {
                    scope.launch { authManager.skipOnboarding() }
                },
                onPasswordSet = { password ->
                    scope.launch {
                        authManager.setPassword(password)
                        authSession.setPasswordConfigured(true)
                        authSession.unlock()
                    }
                }
            )
        }
        locked -> {
            SideEffect(onReveal)
            AuthGate(authManager, authSession)
        }
        else -> UnlockedApp(shareImports, appSession, nsfwGate, onReveal)
    }
}

/**
 * The password screen is the whole app while the session is locked: no destination is composed
 * underneath it, so nothing protected is drawn for even one frame on the way back.
 */
@Composable
private fun AuthGate(authManager: AuthManager, authSession: AuthSession) {
    var status by remember { mutableStateOf<String?>(null) }
    var inputEnabled by remember { mutableStateOf(true) }
    var resetRequired by remember { mutableStateOf(false) }
    val lockedTemplate = stringResource(R.string.auth_locked_seconds)
    val resetText = stringResource(R.string.auth_reset_required)
    LaunchedEffect(Unit) {
        resetRequired = authManager.isResetRequired()
        while (true) {
            val remaining = authManager.lockRemainingMs()
            val seconds = ((remaining + 999) / 1000).toInt()
            inputEnabled = remaining == 0L
            status = when {
                resetRequired -> resetText
                remaining > 0L -> lockedTemplate.format(seconds)
                else -> null
            }
            delay(500)
        }
    }
    AuthScreen(
        onVerifyPassword = { password ->
            if (resetRequired) {
                authManager.setPassword(password)
                authSession.setPasswordConfigured(true)
                resetRequired = false
                authSession.unlock()
                true
            } else {
                val accepted = authManager.verifyPassword(password)
                resetRequired = authManager.isResetRequired()
                if (accepted) authSession.unlock()
                accepted
            }
        },
        onAuthenticated = {},
        statusMessage = status,
        inputEnabled = inputEnabled
    )
}

@Composable
private fun UnlockedApp(
    shareImports: ShareImportViewModel,
    appSession: AppSessionStore,
    nsfwGate: NsfwGate,
    onReveal: () -> Unit
) {
    // Composed only while the session is unlocked, so restoration can never stand in for the
    // password screen. Locking disposes the controller and every destination; what the user was
    // looking at is kept in AppSessionStore and navigated back to on the next unlock.
    val navController = rememberNavController()
    val context = LocalContext.current
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val canImport = route != null
    // Read once per unlocked session. A share or link waiting to be imported is a deliberate
    // destination and outranks the previous screen, so nothing is restored over it.
    val restored = remember {
        if (shareImports.hasPending.value) {
            AppSessionStore.Snapshot(route = null, browserOpen = false)
        } else {
            appSession.read()
        }
    }
    val nsfwEnabled by nsfwGate.enabled.collectAsStateWithLifecycle()
    // An adult route stays covered until the switch is known. Unknown is not "on".
    var restoredApplied by remember { mutableStateOf(false) }
    // The screen under a restored browser is often Player. Compose it only after this activity
    // has stopped, so its SurfaceView cannot flash through the cover while the browser starts.
    var hideForBrowser by remember { mutableStateOf(restored.browserOpen && !appSession.browserVisible) }
    var browserHandoff by remember { mutableStateOf(false) }

    LaunchedEffect(nsfwEnabled, hideForBrowser) {
        if (hideForBrowser) {
            if (!appSession.browserVisible) {
                context.startActivity(Intent(context, WebViewActivity::class.java))
                browserHandoff = true
                onReveal()
            } else {
                hideForBrowser = false
            }
            return@LaunchedEffect
        }
        val waitingOnGate = restored.route != null && NsfwRoutes.isNsfw(restored.route) && nsfwEnabled == null
        if (waitingOnGate) return@LaunchedEffect
        if (!restoredApplied) {
            val chain = restoreLaunchChain(restored.route, nsfwEnabled) ?: return@LaunchedEffect
            val target = chain.last()
            if (navController.currentDestination?.route != routePatternOf(target)) {
                // Player stays under the restored screen, but it is not composed first: its SurfaceView
                // would show through any cover. boot is popped in the same frame as the real destination.
                chain.forEachIndexed { index, step ->
                    navController.navigate(step) {
                        if (index == 0) popUpTo("boot") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            restoredApplied = true
        }
    }

    LaunchedEffect(nsfwEnabled, route) {
        if (nsfwEnabled == false && NsfwRoutes.isNsfw(route)) {
            val popped = navController.popBackStack("player", inclusive = false)
            if (!popped) {
                navController.navigate("player") {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // Every destination change is remembered, not only the final one at exit: a killed process
    // never gets onDestroy, and the next unlock must land on the same screen. An adult route is
    // not written while the switch is off, so the next cold start cannot open it.
    LaunchedEffect(entry, restoredApplied, nsfwEnabled) {
        if (!restoredApplied) return@LaunchedEffect
        val current = entry ?: return@LaunchedEffect
        val concrete = concreteRouteOf(current) ?: return@LaunchedEffect
        if (NsfwRoutes.isNsfw(concrete) && nsfwEnabled != true) return@LaunchedEffect
        appSession.saveRoute(concrete)
    }
    val updates: UpdateViewModel = hiltViewModel()
    val updateOptions by updates.options.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && browserHandoff) hideForBrowser = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasPendingShare by shareImports.hasPending.collectAsState()
    var updateAccess by remember { mutableStateOf(false) }

    LaunchedEffect(canImport, updateOptions.autoCheckEnabled, lifecycleOwner) {
        updateAccess = canImport
        if (updateAccess) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updates.checkAutomatically()
                awaitCancellation()
            }
        }
    }
    if (updateAccess) {
        UpdateDialogHost(updates)
        DuplicateVideoHost()
    }

    // Import only inside the unlocked app, not in the player destination. An onNewIntent share
    // therefore works while gallery/manga is already open, and photos never enter PlayerViewModel.
    LaunchedEffect(hasPendingShare, canImport) {
        if (hasPendingShare && canImport) {
            shareImports.importPending()
        }
    }
    LaunchedEffect(canImport) {
        if (!canImport) return@LaunchedEffect
        shareImports.results.collect { result ->
            val onlyLinks = result.started > 0 && result.imported + result.failed + result.duplicates == 0
            Toast.makeText(
                context,
                when {
                    onlyLinks -> context.getString(R.string.share_download_started, result.started)
                    result.duplicates > 0 -> context.getString(R.string.share_import_result_duplicates, result.imported, result.failed, result.duplicates)
                    else -> context.getString(R.string.share_import_result, result.imported, result.failed)
                },
                Toast.LENGTH_LONG
            ).show()
            val destination = when {
                result.includesPhotos -> "gallery"
                result.includesVideos -> "player"
                else -> null
            }
            if (destination != null && navController.currentDestination?.route != destination) {
                navController.navigate(destination) { launchSingleTop = true }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "boot",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable("boot") {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
            composable("player") {
                val playerViewModel: PlayerViewModel = hiltViewModel()
                PlayerScreen(navController = navController, viewModel = playerViewModel)
            }
            composable("gallery") {
                val galleryViewModel: GalleryViewModel = hiltViewModel()
                GalleryScreen(navController = navController, viewModel = galleryViewModel)
            }
            composable("manga") { entry ->
                if (nsfwEnabled == true) MangaHubScreen(
                    navController = navController,
                    entry = entry,
                    initialHub = appSession.read().mangaHub,
                    onHub = appSession::saveMangaHub
                )
            }
            composable(
                "manga/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType })
            ) { entry ->
                if (nsfwEnabled == true) GalleryDetailScreen(
                    galleryId = entry.arguments?.getInt("id") ?: return@composable,
                    navController = navController
                )
            }
            composable(
                "manga/{id}/read?page={page}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("page") {
                        type = NavType.IntType
                        defaultValue = -1
                    }
                )
            ) { entry ->
                if (nsfwEnabled == true) MangaReaderScreen(
                    galleryId = entry.arguments?.getInt("id") ?: return@composable,
                    startAt = entry.arguments?.getInt("page")?.takeIf { it >= 0 },
                    navController = navController
                )
            }
            composable("documents") {
                DocumentsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { id -> navController.navigate("documents/$id") }
                )
            }
            composable(
                "documents/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) {
                DocumentRouteScreen(onClose = { navController.popBackStack() })
            }
            composable(
                "mangatags/{type}",
                arguments = listOf(navArgument("type") { type = NavType.StringType })
            ) { entry ->
                if (nsfwEnabled == true) TagBrowserScreen(
                    onSearch = navController::searchCatalog,
                    onBack = { navController.popBackStack() },
                    initialType = entry.arguments?.getString("type") ?: "tag"
                )
            }
        }
        // The cover protects startup and gated routes, not later navigation away from the saved screen.
        val covered = hideForBrowser || !restoredApplied || route == null || route == "boot" ||
            (NsfwRoutes.isNsfw(route) && nsfwEnabled != true)
        if (covered) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else {
            SideEffect { onReveal() }
        }
    }
}

/** The concrete destination of [entry], with only the arguments needed to navigate to it again. */
private fun concreteRouteOf(entry: NavBackStackEntry): String? {
    val pattern = entry.destination.route ?: return null
    val args = entry.arguments
    return when (pattern) {
        "manga/{id}" -> args?.getInt("id")?.takeIf { it > 0 }?.let { "manga/$it" }
        // The page argument is deliberately not stored: the reader resumes its own durable progress,
        // and a page number remembered here would be stale after the user read further.
        "manga/{id}/read?page={page}" -> args?.getInt("id")?.takeIf { it > 0 }?.let { "manga/$it/read" }
        "mangatags/{type}" -> args?.getString("type")?.takeIf { it.isNotBlank() }?.let { "mangatags/$it" }
        "documents/{id}" -> args?.getString("id")?.takeIf { AppSessionStore.sanitize("documents/$it") != null }
            ?.let { "documents/$it" }
        // A start page or any pattern without arguments can be used as it is.
        else -> pattern.takeIf { '{' !in it }
    }
}

/** The route pattern that [route] navigates to, compared with the graph's current destination. */
private fun routePatternOf(route: String): String = when {
    route.startsWith("manga/") && route.endsWith("/read") -> "manga/{id}/read?page={page}"
    route.startsWith("manga/") -> "manga/{id}"
    route.startsWith("mangatags/") -> "mangatags/{type}"
    route.startsWith("documents/") -> "documents/{id}"
    else -> route
}

/**
 * The screens to navigate through when restoring [target]. The player stays at the bottom of the
 * stack and the catalog hub sits above it, so Back walks the normal hierarchy rather than ending the
 * app from a reader that was restored directly.
 */
private fun restoredChain(target: String): List<String> = when {
    target.startsWith("manga/") -> {
        val id = target.removePrefix("manga/").substringBefore('/')
        if (target.endsWith("/read")) listOf("manga", "manga/$id", target) else listOf("manga", target)
    }
    target.startsWith("mangatags/") -> listOf("manga", target)
    target.startsWith("documents/") -> listOf("documents", target)
    else -> listOf(target)
}

/**
 * Screens for a cold start. Null while an adult route is still unknown, so nothing adult and no
 * Player placeholder is opened. Player is only the real fallback.
 */
internal fun restoreLaunchChain(route: String?, nsfwEnabled: Boolean?): List<String>? {
    if (route != null && NsfwRoutes.isNsfw(route) && nsfwEnabled == null) return null
    val allowed = route?.takeUnless { NsfwRoutes.isNsfw(it) && nsfwEnabled != true } ?: "player"
    val above = restoredChain(allowed).filter { it != "player" }
    return if (above.isEmpty()) listOf("player") else listOf("player") + above
}
