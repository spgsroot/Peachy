package ru.spgsroot.peachy.ui.webview

import android.content.Context
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.browser.BrowserUrls
import ru.spgsroot.peachy.browser.FoundVideo
import ru.spgsroot.peachy.browser.MediaKind
import ru.spgsroot.peachy.data.browser.BrowserStore
import ru.spgsroot.peachy.data.browser.FilterCatalog
import ru.spgsroot.peachy.data.storage.DuplicateVideoException
import ru.spgsroot.peachy.ui.dialog.DuplicateVideoHost
import ru.spgsroot.peachy.ui.dialog.NoteDialog

private enum class Sheet { TABS, BOOKMARKS, HISTORY, VIDEOS, FILTERS }

/**
 * First guess for the address row: an OutlinedTextField (56 dp) plus its 4 dp vertical padding. The
 * real height is measured during layout, so a larger font scale is not clipped.
 */
private val AddressBarHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    viewModel: BrowserViewModel,
    captureOn: Boolean,
    captureAvailable: Boolean,
    onCaptureChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val activity = LocalActivity.current
    val tab = controller.active
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val allowed by viewModel.allowedHosts.collectAsStateWithLifecycle()
    val lifetime by viewModel.blocked.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val refreshFailed by viewModel.refreshFailed.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearSiteData by remember { mutableStateOf(false) }
    var clearingSiteData by remember { mutableStateOf(false) }
    var addressFocused by remember(tab.id) { mutableStateOf(false) }

    // The toolbar is never taken away while the address field is being edited, and a tab switch
    // starts with it visible (the controller resets the flag). With assistive technology running a
    // clipped-away toolbar would be unreachable, so the chrome stays put while it is enabled.
    val context = LocalContext.current
    val accessibility = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var accessibilityOn by remember { mutableStateOf(accessibility?.isEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
            accessibilityOn = enabled
        }
        accessibility?.addAccessibilityStateChangeListener(listener)
        onDispose { accessibility?.removeAccessibilityStateChangeListener(listener) }
    }
    val chromeVisible = !tab.chromeHidden || addressFocused || accessibilityOn
    // The row's natural height is measured unconstrained below, so a larger font scale grows the
    // slot instead of clipping the field; 64 dp is only the first guess before layout.
    var naturalChrome by remember { mutableStateOf(AddressBarHeight) }
    val density = LocalDensity.current
    // tween honours the system "Remove animations" setting through Compose's MotionDurationScale,
    // so with animations disabled the chrome simply snaps instead of sliding.
    val chromeHeight by animateDpAsState(
        targetValue = if (chromeVisible) naturalChrome else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "browserChromeHeight"
    )
    val chromeAlpha by animateFloatAsState(
        targetValue = if (chromeVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "browserChromeAlpha"
    )

    val pageKey = BrowserStore.pageKey(tab.url)
    val bookmarked = pageKey != null && bookmarks.any { it.url == pageKey }
    val blockerOff = BrowserStore.isBlockerOff(allowed, tab.host)
    val pageNotes by viewModel.pageNotes.collectAsStateWithLifecycle()
    val hasNote = pageKey != null && pageNotes.containsKey(pageKey)
    var editingNote by remember { mutableStateOf(false) }
    if (editingNote && pageKey != null) {
        NoteDialog(
            title = stringResource(R.string.note_title_page),
            initial = pageNotes[pageKey].orEmpty(),
            onSave = { text ->
                viewModel.savePageNote(pageKey, text)
                editingNote = false
            },
            onDismiss = { editingNote = false }
        )
    }

    val msgSaved = stringResource(R.string.browser_video_saved)
    val msgFailed = stringResource(R.string.browser_video_failed)
    val msgDuplicate = stringResource(R.string.browser_video_duplicate)
    val msgStream = stringResource(R.string.browser_video_stream)
    val msgUpdating = stringResource(R.string.browser_filters_updating)
    val msgUpdateFailed = stringResource(R.string.browser_filters_failed)
    val msgUpdated = stringResource(R.string.browser_filters_updated)

    fun say(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    val fullscreen = controller.fullscreenView
    LaunchedEffect(activity, fullscreen != null) {
        val window = activity?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen != null) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    BackHandler {
        when {
            // Transient surfaces first: Back closes what is on top instead of leaving the page.
            fullscreen != null -> controller.hideFullscreen()
            controller.pendingNavigation != null -> controller.dismissPendingNavigation()
            controller.pendingWindow != null -> controller.dismissPendingWindow()
            menuOpen -> menuOpen = false
            sheet != null -> sheet = null
            editingNote -> editingNote = false
            // Then the page's own history: the live WebView first, then the addresses restored with
            // the tab, so Back keeps leading to the previous page after a process restart too.
            tab.canGoBack -> controller.goBack(tab)
            // At the root, return to the tab we came from without closing either tab.
            else -> if (!controller.returnToPreviousTab()) onClose()
        }
    }

    if (fullscreen != null) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { fullscreen })
        }
        return
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // The address row slides up out of its own clipped slot; the slot itself shrinks, so the
            // page gains the space instead of the toolbar overlapping it. WebView content is never
            // reloaded by this, only laid out again. Its measured height comes from an unconstrained
            // pass, so the row is never squeezed by the shrinking slot and larger font scales keep
            // the whole field.
            Box(Modifier.fillMaxWidth().height(chromeHeight).clipToBounds()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                        .onSizeChanged { naturalChrome = with(density) { it.height.toDp() } }
                        .graphicsLayer { alpha = chromeAlpha }
                ) {
                    AddressBar(
                        tab = tab,
                        controller = controller,
                        onFocusChange = { focused ->
                            addressFocused = focused
                            if (focused) controller.showChrome(tab)
                        }
                    )
                }
            }
            if (tab.loading) {
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val view = tab.webView
                if (view == null) {
                    StartPage(
                        bookmarks = bookmarks.map { it.title to it.url },
                        recent = history.take(8).map { it.title to it.url },
                        onOpen = { controller.navigate(tab, it) }
                    )
                } else {
                    key(tab.id) {
                        WebHost(view) { scrollY, delta -> controller.onScroll(tab, scrollY, delta) }
                    }
                }
            }
            HorizontalDivider()
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .barHistorySwipe(tab.id, rtl, { if (tab.canGoBack) controller.goBack(tab) }, { if (tab.canGoForward) controller.goForward(tab) })
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { controller.goBack(tab) }, enabled = tab.canGoBack) {
                    Icon(Icons.Filled.ArrowBack, stringResource(R.string.browser_back))
                }
                IconButton(onClick = { controller.goForward(tab) }, enabled = tab.canGoForward) {
                    Icon(Icons.Filled.ArrowForward, stringResource(R.string.browser_forward))
                }
                IconButton(onClick = {
                    controller.refreshVideos(tab)
                    sheet = Sheet.VIDEOS
                }) {
                    BadgedBox(badge = { if (tab.videos.isNotEmpty()) Badge { Text(tab.videos.size.toString()) } }) {
                        Icon(Icons.Filled.Movie, stringResource(R.string.browser_videos))
                    }
                }
                IconButton(onClick = {
                    var opened = false
                    fun openTabs() {
                        if (!opened) {
                            opened = true
                            sheet = Sheet.TABS
                        }
                    }
                    controller.captureActiveThumbnail { openTabs() }
                    // The preview is a bonus; the overview must open even if no frame arrives.
                    scope.launch {
                        delay(400)
                        openTabs()
                    }
                }) {
                    Box(
                        Modifier.size(24.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(controller.tabs.size.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.browser_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_new_tab)) },
                            onClick = { menuOpen = false; controller.newTab() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_home)) },
                            enabled = tab.webView != null,
                            onClick = { menuOpen = false; controller.showStart(tab) }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null)
                            },
                            text = {
                                Text(stringResource(if (bookmarked) R.string.browser_remove_bookmark else R.string.browser_add_bookmark))
                            },
                            enabled = pageKey != null,
                            onClick = {
                                menuOpen = false
                                viewModel.toggleBookmark(tab.url, tab.title, bookmarked)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_bookmarks)) },
                            onClick = { menuOpen = false; sheet = Sheet.BOOKMARKS }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.History, null) },
                            text = { Text(stringResource(R.string.browser_history)) },
                            onClick = { menuOpen = false; sheet = Sheet.HISTORY }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(if (hasNote) Icons.Filled.StickyNote2 else Icons.Filled.EditNote, null) },
                            text = { Text(stringResource(R.string.browser_note_page)) },
                            enabled = pageKey != null,
                            onClick = { menuOpen = false; editingNote = true }
                        )
                        HorizontalDivider()
                        val host = tab.host
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.browser_block_ads))
                                    if (tab.webView != null && !blockerOff) {
                                        Text(
                                            stringResource(R.string.browser_blocked_count, tab.blocked),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(stringResource(R.string.browser_shield_total, lifetime), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            trailingIcon = { Switch(checked = !blockerOff, onCheckedChange = null, enabled = host != null) },
                            enabled = host != null,
                            onClick = {
                                if (host != null) viewModel.setBlockerEnabled(host, enabled = blockerOff) { controller.reload(tab) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_update_filters)) },
                            onClick = {
                                menuOpen = false
                                say(msgUpdating)
                                viewModel.updateFilters { result ->
                                    result.fold(
                                        onSuccess = { say(msgUpdated.format(it)) },
                                        onFailure = { say(msgUpdateFailed) }
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_shield_lists)) },
                            onClick = { menuOpen = false; sheet = Sheet.FILTERS }
                        )
                        if (captureAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_capture)) },
                                trailingIcon = { Switch(checked = captureOn, onCheckedChange = null) },
                                onClick = { onCaptureChange(!captureOn) }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_clear_site_data)) },
                            onClick = { menuOpen = false; confirmClearSiteData = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_close_browser)) },
                            onClick = { menuOpen = false; onClose() }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
    }

    if (sheet != null) {
        // Gesture navigation reports a zero navigation-bar inset but a non-zero mandatory gesture
        // strip, so the union is the real bottom surface in both modes.
        val systemBarBottom = WindowInsets.navigationBars
            .union(WindowInsets.mandatorySystemGestures)
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()
        // Measured on device (1080x2400, 420 dpi): insets are real here — 126 px three-button and
        // 84 px gesture, i.e. 48/32 dp — and this padding is what puts the expanded sheet's grid at
        // y=2274/2316. The tab picker also opens fully expanded: a partially expanded sheet is
        // anchored at half height, so a full-height grid body is clipped at the window edge and its
        // last row sits under the bars until a scroll expands the sheet.
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = sheet == Sheet.TABS
        )
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState,
            // The body padding is the single source of bottom clearance; zeroing the sheet's own
            // inset keeps it from stacking a second one.
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            Column(Modifier.padding(bottom = systemBarBottom)) {
                when (sheet) {
                    Sheet.TABS -> TabsSheet(
                        controller = controller,
                        onPick = { controller.select(it); sheet = null },
                        onNew = { controller.newTab(); sheet = null }
                    )
                    Sheet.BOOKMARKS -> LinkList(
                        empty = stringResource(R.string.browser_no_bookmarks),
                        rows = bookmarks.map { Triple(it.title, it.url, it.url) },
                        onOpen = { controller.navigate(tab, it); sheet = null },
                        onDelete = viewModel::removeBookmark
                    )
                    Sheet.HISTORY -> Column {
                        TextButton(
                            onClick = viewModel::clearHistory,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) { Text(stringResource(R.string.browser_clear_history)) }
                        LinkList(
                            empty = stringResource(R.string.browser_no_history),
                            rows = history.map { Triple(it.title, it.url, it.url) },
                            onOpen = { controller.navigate(tab, it); sheet = null },
                            onDelete = viewModel::removeVisit
                        )
                    }
                    Sheet.VIDEOS -> VideosSheet(
                        videos = tab.videos,
                        onSave = { video, done ->
                            viewModel.download(video, tab.host, controller.downloadHeaders(tab, video.url), tab.title.ifBlank { null }) { result ->
                                done()
                                say(
                                    result.fold(
                                        onSuccess = { msgSaved },
                                        onFailure = {
                                            when {
                                                it is DuplicateVideoException -> msgDuplicate
                                                video.kind == MediaKind.STREAM -> msgStream
                                                else -> msgFailed
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    )
                    Sheet.FILTERS -> {
                        var draft by remember(subscriptions) { mutableStateOf(subscriptions) }
                        var customRules by remember(profile) { mutableStateOf(profile.rules) }
                        var urlText by remember(profile) { mutableStateOf(profile.urls.joinToString("\n")) }
                        val custom = ru.spgsroot.peachy.data.browser.PrivateFilterProfile(customRules, urlText.lines().map(String::trim).filter(String::isNotEmpty).distinct())
                        Column {
                            if (refreshFailed) Text(msgUpdateFailed, Modifier.padding(12.dp))
                            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                                !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                                Text(stringResource(R.string.browser_shield_old_webview), Modifier.padding(12.dp))
                            }
                            Button(
                                enabled = draft != subscriptions || custom != profile,
                                onClick = {
                                    sheet = null; say(msgUpdating)
                                    viewModel.applyProfile(draft, custom) { result ->
                                        result.fold({ controller.reload(tab); say(msgUpdated.format(it)) }, { say(msgUpdateFailed) })
                                    }
                                }, modifier = Modifier.padding(horizontal = 12.dp)
                            ) { Text(stringResource(R.string.browser_shield_apply)) }
                            LazyColumn {
                                item {
                                    listOf(
                                        Triple(R.string.browser_shield_network, mode.network, mode.copy(network = !mode.network)),
                                        Triple(R.string.browser_shield_cosmetic, mode.cosmetic, mode.copy(cosmetic = !mode.cosmetic)),
                                        Triple(R.string.browser_shield_generic, mode.generic, mode.copy(generic = !mode.generic))
                                    ).forEach { (label, enabled, next) ->
                                        ListItem(headlineContent = { Text(stringResource(label)) }, trailingContent = { Switch(enabled, null) },
                                            modifier = Modifier.clickable { viewModel.setMode(next); controller.reload(tab) })
                                    }
                                    OutlinedTextField(customRules, { customRules = it }, modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        label = { Text(stringResource(R.string.browser_shield_rules)) }, maxLines = 4)
                                    OutlinedTextField(urlText, { urlText = it }, modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        label = { Text(stringResource(R.string.browser_shield_urls)) }, maxLines = 4)
                                }
                                items(FilterCatalog.lists, key = { it.id }) { item ->
                                    val editable = item.id !in setOf("easylist", "easyprivacy", "adult")
                                    ListItem(
                                        headlineContent = { Text(item.title) },
                                        trailingContent = { Switch(item.id in draft, null, enabled = editable) },
                                        modifier = Modifier.clickable(enabled = editable) {
                                            draft = if (item.id in draft) draft - item.id else draft + item.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }

    if (confirmClearSiteData) {
        val fullClear = remember {
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }
        AlertDialog(
            onDismissRequest = { if (!clearingSiteData) confirmClearSiteData = false },
            title = { Text(stringResource(R.string.browser_clear_site_data)) },
            text = {
                if (clearingSiteData) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(
                        if (fullClear) R.string.browser_clear_site_data_message
                        else R.string.browser_clear_site_data_legacy_message
                    ))
                }
            },
            confirmButton = {
                if (!clearingSiteData) {
                    Button(onClick = {
                        clearingSiteData = true
                        controller.clearSiteData {
                            confirmClearSiteData = false
                            clearingSiteData = false
                        }
                    }) { Text(stringResource(R.string.browser_clear_site_data_confirm)) }
                }
            },
            dismissButton = {
                if (!clearingSiteData) {
                    TextButton(onClick = { confirmClearSiteData = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        )
    }

    val pending = controller.pendingNavigation
    if (pending != null) {
        AlertDialog(
            onDismissRequest = controller::dismissPendingNavigation,
            title = { Text(stringResource(R.string.browser_navigation_title)) },
            text = { Text(stringResource(R.string.browser_navigation_message, BrowserUrls.displayHost(pending.url))) },
            confirmButton = {
                Button(onClick = controller::continuePendingNavigation) {
                    Text(stringResource(R.string.browser_navigation_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissPendingNavigation) {
                    Text(stringResource(R.string.browser_navigation_stay))
                }
            }
        )
    }

    val pendingWindow = controller.pendingWindow
    if (pendingWindow != null && pending == null) {
        AlertDialog(
            onDismissRequest = controller::dismissPendingWindow,
            title = { Text(stringResource(R.string.browser_popup_title)) },
            text = { Text(stringResource(R.string.browser_popup_message, BrowserUrls.displayHost(pendingWindow.url))) },
            confirmButton = {
                Button(onClick = controller::openPendingWindow) {
                    Text(stringResource(R.string.browser_popup_open))
                }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissPendingWindow) {
                    Text(stringResource(R.string.browser_navigation_stay))
                }
            }
        )
    }

    DuplicateVideoHost()
}

@Composable
private fun AddressBar(tab: BrowserTab, controller: BrowserController, onFocusChange: (Boolean) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember(tab.id, tab.url) { mutableStateOf(tab.url) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.browser_address_hint)) },
        leadingIcon = {
            if (tab.url.startsWith("https://")) Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
        },
        trailingIcon = {
            if (tab.webView != null) {
                if (tab.loading) {
                    IconButton(onClick = { controller.stop(tab) }) { Icon(Icons.Filled.Close, stringResource(R.string.browser_stop)) }
                } else {
                    IconButton(onClick = { controller.reload(tab) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.browser_reload)) }
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            focus.clearFocus()
            controller.navigate(tab, text)
        }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged { onFocusChange(it.isFocused) }
    )
}

@Composable
private fun WebHost(webView: android.webkit.WebView, onScroll: (scrollY: Int, delta: Int) -> Unit) {
    val scroll = rememberUpdatedState(onScroll)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { FrameLayout(it) },
        update = { frame ->
            if (frame.getChildAt(0) !== webView) {
                frame.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                frame.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    )
    // Attached here rather than inside the WebView factory: the page's own offset is what drives the
    // browser chrome, and the listener is dropped with the composition (tab switch or close). The
    // first event only samples the position: re-attaching an already scrolled tab must not count as
    // a downward gesture and slam the toolbar shut.
    DisposableEffect(webView) {
        var firstEvent = true
        webView.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (firstEvent) {
                firstEvent = false
                scroll.value(y, 0)
            } else {
                scroll.value(y, y - oldY)
            }
        }
        onDispose { webView.setOnScrollChangeListener(null) }
    }
}

/** Horizontal drag on the toolbar only: page scroll never sees it, and a tap still reaches the buttons. */
private fun Modifier.barHistorySwipe(key: Any, rtl: Boolean, onBack: () -> Unit, onForward: () -> Unit): Modifier =
    pointerInput(key, rtl) {
        val threshold = 48.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) break
                totalX += change.position.x - change.previousPosition.x
                if (!dragging && kotlin.math.abs(totalX) >= viewConfiguration.touchSlop) dragging = true
                if (dragging) change.consume()
            }
            if (!dragging || kotlin.math.abs(totalX) < threshold) return@awaitEachGesture
            if ((if (rtl) totalX < 0 else totalX > 0)) onForward() else onBack()
        }
    }

@Composable
private fun StartPage(bookmarks: List<Pair<String, String>>, recent: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle(stringResource(R.string.browser_bookmarks)) }
        if (bookmarks.isEmpty()) {
            item { Text(stringResource(R.string.browser_no_bookmarks), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(bookmarks, key = { "b" + it.second }) { (title, url) -> LinkRow(title, url) { onOpen(url) } }
        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.browser_history)) }
            items(recent, key = { "h" + it.second }) { (title, url) -> LinkRow(title, url) { onOpen(url) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
}

@Composable
private fun LinkRow(title: String, url: String, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun LinkList(
    empty: String,
    rows: List<Triple<String, String, String>>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (rows.isEmpty()) {
        Text(empty, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn {
        items(rows, key = { it.third }) { (title, url, key) ->
            LinkRow(
                title = title,
                url = url,
                trailing = {
                    IconButton(onClick = { onDelete(key) }) { Icon(Icons.Filled.Delete, stringResource(R.string.browser_delete)) }
                },
                onClick = { onOpen(url) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(controller: BrowserController, onPick: (BrowserTab) -> Unit, onNew: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.browser_tabs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        gridItems(controller.tabs.toList(), key = { it.id }) { item ->
            TabCard(
                tab = item,
                active = item.id == controller.activeId,
                thumbnail = controller.thumbnails[item.id],
                onPick = { onPick(item) },
                onClose = { controller.close(item) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.browser_new_tab))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabCard(
    tab: BrowserTab,
    active: Boolean,
    thumbnail: android.graphics.Bitmap?,
    onPick: () -> Unit,
    onClose: () -> Unit
) {
    val label = tab.title.ifBlank { BrowserUrls.displayHost(tab.url).ifBlank { stringResource(R.string.browser_untitled) } }
    val host = BrowserUrls.displayHost(tab.url)
    Card(
        onClick = onPick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.66f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            Icons.Filled.Public,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            host.ifBlank { stringResource(R.string.browser_untitled) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(R.string.browser_close_tab),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (host.isNotEmpty()) {
                    Text(
                        host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun VideosSheet(videos: List<FoundVideo>, onSave: (FoundVideo, () -> Unit) -> Unit) {
    var saving by remember { mutableStateOf(setOf<String>()) }
    if (videos.isEmpty()) {
        Text(stringResource(R.string.browser_no_videos), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn {
        items(videos, key = { it.url }) { video ->
            val name = video.url.substringBefore('#').substringBefore('?').substringAfterLast('/').ifBlank { video.url }
            ListItem(
                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        if (video.kind == MediaKind.STREAM) stringResource(R.string.browser_video_stream)
                        else video.url,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingContent = {
                    if (video.kind == MediaKind.FILE) {
                        val busy = video.url in saving
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                saving = saving + video.url
                                onSave(video) { saving = saving - video.url }
                            }
                        ) { Icon(Icons.Filled.SaveAlt, stringResource(if (busy) R.string.browser_video_saving else R.string.browser_video_save)) }
                    }
                }
            )
        }
    }
}
