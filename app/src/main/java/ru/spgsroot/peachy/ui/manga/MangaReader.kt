package ru.spgsroot.peachy.ui.manga

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.Translate
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.PageTurnGate
import ru.spgsroot.peachy.manga.PageTurnHaptic
import ru.spgsroot.peachy.manga.PagedScrollRestore
import ru.spgsroot.peachy.manga.ReaderFit
import ru.spgsroot.peachy.manga.ReaderOrientation
import ru.spgsroot.peachy.manga.ReaderPosition
import ru.spgsroot.peachy.manga.ReaderSettings
import ru.spgsroot.peachy.manga.ReaderSettingsStore
import ru.spgsroot.peachy.manga.TapZone
import ru.spgsroot.peachy.manga.centerPage
import ru.spgsroot.peachy.manga.clampPosition
import ru.spgsroot.peachy.manga.offsetFraction
import ru.spgsroot.peachy.manga.offsetPixels
import ru.spgsroot.peachy.manga.pageOffsetFraction
import ru.spgsroot.peachy.manga.pagedOffsetFraction
import ru.spgsroot.peachy.manga.tapZone
import ru.spgsroot.peachy.manga.TranslateMode
import ru.spgsroot.peachy.translate.PageState
import ru.spgsroot.peachy.translate.TranslatedLine
import ru.spgsroot.peachy.translate.ProviderUrl
import ru.spgsroot.peachy.ui.dialog.ProviderDialog
import ru.spgsroot.peachy.ui.dialog.TranslateConsentDialog

/** Pages fetched ahead of the one on screen, so turning the page does not wait for the network. */
private const val PRELOAD_PAGES = 3

@Composable
fun MangaReaderScreen(
    galleryId: Int,
    startAt: Int? = null,
    navController: NavController,
    viewModel: MangaViewModel = hiltViewModel()
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val initialPosition by viewModel.initialPosition.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(galleryId, startAt) { viewModel.open(galleryId, startAt) }
    val current = detail?.takeIf { it.id == galleryId }
    if (current != null && initialPosition != null && current.pagePaths.isNotEmpty()) {
        key(galleryId) {
            NativeReader(current, initialPosition!!, navController, viewModel)
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
            }
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                current != null -> Text(stringResource(R.string.manga_empty))
                else -> CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NativeReader(
    gallery: GalleryDetail,
    initialPosition: ReaderPosition,
    navController: NavController,
    viewModel: MangaViewModel
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var settings by remember { mutableStateOf(ReaderSettingsStore.load(prefs)) }
    fun change(next: ReaderSettings) {
        settings = next
        ReaderSettingsStore.save(prefs, next)
    }
    val intensity by viewModel.intensity.collectAsStateWithLifecycle()
    val library by viewModel.libraryItems.collectAsStateWithLifecycle()
    val savedPages by viewModel.savedPages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var chrome by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val translation: TranslationViewModel = hiltViewModel()
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    val translationChoice = remember(gallery.id, settings, locales) { translation.choice(gallery.id, settings) }
    val translationState by translation.state.collectAsStateWithLifecycle()
    val currentTranslation = translationState?.forChoice(translationChoice)
    val pageStates = currentTranslation?.pages.orEmpty()
    val batch = currentTranslation?.batch
    val needsConsent = currentTranslation?.needsConsent == true
    val provider by translation.provider.collectAsStateWithLifecycle()
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var providerDialog by rememberSaveable { mutableStateOf(false) }
    var currentPage by rememberSaveable {
        mutableIntStateOf(clampPosition(initialPosition, gallery.pagePaths.size).page)
    }
    var currentFraction by rememberSaveable {
        mutableFloatStateOf(clampPosition(initialPosition, gallery.pagePaths.size).offset)
    }
    var hints by remember { mutableStateOf(!settings.hideHints) }
    val gate = remember { PageTurnGate() }
    val zoom = remember { ReaderZoom() }
    val latestGallery by rememberUpdatedState(gallery)
    val latestSettings by rememberUpdatedState(settings)
    val savedPosition = remember { mutableStateOf<ReaderPosition?>(null) }

    /** Writes the page and its in-page offset; repeated samples of the same spot do not hit the store. */
    fun persistPosition(page: Int, offset: Float) {
        val position = ReaderPosition(page, offsetFraction(offset))
        if (position == savedPosition.value) return
        savedPosition.value = position
        viewModel.saveReadHistory(latestGallery, position.page, position.offset)
    }
    val scope = rememberCoroutineScope()
    val thumbnails = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
    val total = gallery.pagePaths.size
    val vertical = settings.orientation == ReaderOrientation.Vertical
    LaunchedEffect(viewModel) {
        viewModel.setIntensity(prefs.getFloat("intensity", PageTurnHaptic.DEFAULT_INTENSITY))
    }
    // The gate ignores the page the reader opens on, and a mode change leaves the current page unsettled.
    LaunchedEffect(translationChoice, settings.translateMode) {
        translation.open(translationChoice, gallery.pagePaths)
        translation.onPageSettled(currentPage, settings)
    }
    LaunchedEffect(chrome, currentPage) {
        if (chrome) thumbnails.animateScrollToItem(currentPage)
    }
    LaunchedEffect(Unit) {
        if (hints) {
            delay(2_500)
            hints = false
        }
    }

    // Backgrounding or process death may skip Back, so the last spot is written while the reader is still alive.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                persistPosition(currentPage.coerceIn(0, (total - 1).coerceAtLeast(0)), currentFraction)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bubbleLines = remember(translationChoice, provider.baseUrl, provider.model) {
        mutableStateMapOf<Int, List<TranslatedLine>>()
    }

    // Mode-local states start at the current page when the layout changes.
    key(settings.continuous, settings.orientation) {
        val startFraction = offsetFraction(currentFraction)
        val pager = rememberPagerState(initialPage = currentPage, pageCount = { total })
        val strip = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
        val stripMode = settings.continuous
        val keepsOffset = stripMode && vertical
        // Paged fit-width pages keep their in-page spot for this layout session; the durable copy stays
        // the reader position. The page the reader opens on starts from the stored offset.
        val pagedFractions = remember { mutableMapOf(currentPage to startFraction) }
        // A strip item's first measure uses the placeholder aspect. Only a height measured after the real
        // aspect arrives can restore the stored share without a jump.
        val stripHeights = remember { mutableStateMapOf<Int, Int>() }
        // The offset is a share of the page height, so it is reapplied once the page has been measured.
        var restored by remember { mutableStateOf(!keepsOffset || startFraction <= 0f) }
        val visiblePage by remember {
            derivedStateOf {
                if (stripMode) strip.centeredPage().takeIf { it >= 0 } ?: currentPage
                else pager.currentPage
            }
        }
        val bubbleMode = settings.translateMode == TranslateMode.Bubble
        val bubbles = if (bubbleMode) key(visiblePage, translationChoice, provider.baseUrl, provider.model) {
            val pageScope = rememberCoroutineScope()
            val latestProvider by rememberUpdatedState(provider)
            remember {
                BubbleTapSession(pageScope, bubbleLines,
                    recognize = { translation.bubbleBlocks(translationChoice, it) },
                    translate = { page, block -> translation.translateBubble(translationChoice, page, block) },
                    needsConsent = { latestProvider.isConfigured && !latestProvider.consented })
            }
        } else null
        LaunchedEffect(bubbles) { bubbles?.prepare(visiblePage) }
        bubbles?.afterConsent?.let { approve ->
            TranslateConsentDialog(provider.baseUrl, { translation.grantProviderConsent(); approve() }, bubbles::dismiss)
        }
        var jumpPage by remember { mutableIntStateOf(-1) }
        var jumpAt by remember { mutableIntStateOf(0) }
        val jump: (Int) -> Unit = { target ->
            if (target in gallery.pagePaths.indices) {
                if (!stripMode) {
                    // An explicit page choice starts at the top, not at an earlier visit's spot.
                    pagedFractions[target] = 0f
                    jumpPage = target
                    jumpAt++
                }
                if (stripMode) {
                    // An explicit page change wins over a restore that is still waiting for layout.
                    restored = true
                    scope.launch { strip.animateScrollToItem(target) }
                } else {
                    scope.launch { pager.animateScrollToPage(target) }
                }
            }
        }
        LaunchedEffect(strip, keepsOffset, restored) {
            if (!keepsOffset || restored) return@LaunchedEffect
            val targetPage = currentPage
            // The first strip measure uses the placeholder aspect. Wait for the height reported after the
            // real aspect arrives, and abandon the restore if the reader moves or jumps meanwhile.
            val result = snapshotFlow {
                val centered = strip.centeredPage()
                val moved = strip.isScrollInProgress || (centered >= 0 && centered != targetPage)
                val height = stripHeights[targetPage]
                when {
                    restored || moved -> true to null
                    centered == targetPage && height != null -> false to height
                    else -> null
                }
            }.filterNotNull().first()
            if (!result.first) {
                result.second?.let { height ->
                    val offset = offsetPixels(startFraction, height)
                    strip.scrollToItem(targetPage, offset)
                    val fraction = pageOffsetFraction(-offset, height)
                    currentPage = targetPage
                    currentFraction = fraction
                    persistPosition(targetPage, fraction)
                }
            }
            restored = true
        }
        LaunchedEffect(pager, strip) {
            if (stripMode) {
                snapshotFlow {
                    val page = strip.centeredPage()
                    val offset = if (keepsOffset && page >= 0) strip.offsetFractionOf(page) else 0f
                    Triple(page, !strip.isScrollInProgress, offset)
                }.collect { (page, idle, offset) ->
                    if (page >= 0) {
                        currentPage = page
                        // Until the stored offset is applied, samples describe the top of the page, not the user.
                        if (restored) currentFraction = offset
                        if (gate.onPage(page, idle)) {
                            viewModel.onPageTurned()
                            translation.onPageSettled(page, latestSettings)
                        }
                        if (restored && idle) persistPosition(page, offset)
                    }
                }
            } else {
                snapshotFlow { pager.settledPage }.collect { page ->
                    currentPage = page
                    val offset = pagedFractions[page] ?: 0f
                    currentFraction = offset
                    if (gate.onPage(page, idle = true)) {
                        viewModel.onPageTurned()
                        translation.onPageSettled(page, latestSettings)
                    }
                    persistPosition(page, offset)
                }
            }
        }
        DisposableEffect(pager, strip) {
            onDispose {
                // Read layout now, not the last idle sample: Back can interrupt a fling.
                if (restored) {
                    if (keepsOffset) {
                        val page = strip.centeredPage().takeIf { it >= 0 } ?: currentPage
                        persistPosition(page, strip.offsetFractionOf(page))
                    } else if (stripMode) {
                        val page = strip.centeredPage().takeIf { it >= 0 } ?: currentPage
                        persistPosition(page, 0f)
                    } else {
                        // A paged page's in-page offset is the reader's spot, not the top of the page.
                        val page = pager.currentPage
                        persistPosition(page, pagedFractions[page] ?: 0f)
                    }
                }
            }
        }
        LaunchedEffect(visiblePage, settings.fit) { zoom.reset() }
        LaunchedEffect(visiblePage) {
            val loader = context.imageLoader
            for (ahead in 1..PRELOAD_PAGES) {
                val path = gallery.pagePaths.getOrNull(visiblePage + ahead) ?: break
                loader.enqueue(ImageRequest.Builder(context).data(viewModel.pageModel(gallery.id, path)).build())
            }
        }
        // A tall strip has no useful top and bottom zones, so it keeps left and right.
        val tapOnY = vertical && !stripMode
        val onTap: (Float, Float) -> Unit = { position, extent ->
            val zone = if (settings.tapToSwitch) tapZone(position, extent, settings.handSwap && !tapOnY) else TapZone.Chrome
            when (zone) {
                TapZone.Back -> jump(visiblePage - 1)
                TapZone.Chrome -> chrome = !chrome
                TapZone.Forward -> jump(visiblePage + 1)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val viewportHeight = maxHeight
            val scrollable = !zoom.zoomed
            val page: @Composable (Int, Boolean, Modifier) -> Unit = { index, inStrip, modifier ->
                val wholePageLines = (pageStates[index] as? PageState.Done)?.translation?.lines.orEmpty()
                val partial = if (bubbleMode) bubbleLines[index].orEmpty() else emptyList()
                val overlayLines = remember(settings.overlay, wholePageLines, partial) {
                    when {
                        !settings.overlay -> emptyList()
                        partial.isEmpty() -> wholePageLines
                        wholePageLines.isEmpty() -> partial
                        else -> wholePageLines + partial.filterNot { line -> wholePageLines.any { it.box == line.box } }
                    }
                }
                ReaderPage(
                    model = remember(gallery.id, gallery.pagePaths[index]) {
                        viewModel.pageModel(gallery.id, gallery.pagePaths[index])
                    },
                    page = index,
                    active = index == visiblePage,
                    zoom = zoom,
                    fillWidth = settings.fit == ReaderFit.Width,
                    strip = inStrip,
                    tapOnY = tapOnY,
                    modifier = modifier,
                    viewportHeight = viewportHeight,
                    startOffset = pagedFractions[index] ?: 0f,
                    resetAt = if (jumpPage == index) jumpAt else 0,
                    onResetConsumed = { if (jumpPage == index) jumpPage = -1 },
                    onScroll = { offset, idle ->
                        pagedFractions[index] = offset
                        if (index == currentPage) {
                            currentFraction = offset
                            if (idle) persistPosition(index, offset)
                        }
                    },
                    onStripMeasured = { height -> stripHeights[index] = height },
                    lines = overlayLines,
                    onBubbleTap = if (bubbles == null) null else { at, miss ->
                        panelOpen = false
                        bubbles.tap(index, at, wholePageLines, { settings.overlay }, miss)
                    },
                    selectedLine = if (settings.overlay) bubbles?.selection?.takeIf { it.first == index }?.second else null,
                    onSelectionOmitted = { line -> bubbles?.showOmitted(index, line) },
                    onTap = onTap
                )
            }
            when {
                stripMode && vertical -> LazyColumn(
                    state = strip,
                    userScrollEnabled = scrollable,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(total, key = { it }) { index ->
                        page(index, true, Modifier.fillMaxWidth().heightIn(min = viewportHeight))
                    }
                }
                stripMode -> LazyRow(
                    state = strip,
                    userScrollEnabled = scrollable,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(total, key = { it }) { index ->
                        page(index, false, Modifier.fillParentMaxSize())
                    }
                }
                vertical -> VerticalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = scrollable,
                    beyondViewportPageCount = 1
                ) { index -> page(index, false, Modifier.fillMaxSize()) }
                else -> HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = scrollable,
                    beyondViewportPageCount = 1
                ) { index -> page(index, false, Modifier.fillMaxSize()) }
            }
            if (hints && settings.tapToSwitch && !chrome) {
                TapHints(vertical = tapOnY, swapped = settings.handSwap && !tapOnY)
            }
            if (settings.inspect) {
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZoomButton(Icons.Default.ZoomIn, R.string.reader_zoom_in) { zoom.step(1) }
                    ZoomButton(Icons.Default.ZoomOut, R.string.reader_zoom_out) { zoom.step(-1) }
                }
            }
            if (chrome) {
                val pageSaved = savedPages.any { it.galleryId == gallery.id && it.page == visiblePage }
                TopAppBar(
                    title = {
                        Column {
                            Text(gallery.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.manga_page, visiblePage + 1, total),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            bubbles?.dismiss()
                            panelOpen = true
                            if (settings.translateMode == TranslateMode.Whole) translation.startWhole(settings)
                            else translation.translateNow(visiblePage, settings)
                        }) {
                            Icon(Icons.Default.Translate, stringResource(R.string.translate_button))
                        }
                        IconButton(onClick = { viewModel.toggleSavedPage(visiblePage) }) {
                            Icon(
                                if (pageSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                stringResource(if (pageSaved) R.string.reader_unsave_page else R.string.reader_save_page)
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (library.any { it.id == gallery.id && it.favorite }) Icons.Default.Favorite
                                else Icons.Default.FavoriteBorder,
                                stringResource(R.string.manga_favorites)
                            )
                        }
                        IconButton(onClick = { sheet = true }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.manga_reader_settings))
                        }
                    }
                )
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                val bubblePanel = bubbles?.panel
                if (panelOpen || bubblePanel != null) {
                    TranslationPanel(
                        state = bubblePanel ?: pageStates[visiblePage] ?: PageState.Idle,
                        bubble = bubblePanel != null,
                        batch = if (bubblePanel == null) batch else null,
                        onAgain = if (bubblePanel != null) null else ({
                            // Only a finished translation is paid for again; a missing or failed one is just tried.
                            translation.translateNow(visiblePage, settings, force = pageStates[visiblePage] is PageState.Done)
                        }),
                        onProvider = { providerDialog = true },
                        onCancelBatch = translation::cancelWhole,
                        onHide = { bubbles?.dismiss(); panelOpen = false },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (chrome) Modifier else Modifier.navigationBarsPadding())
                    )
                }
                if (chrome) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ) {
                        Column(Modifier.navigationBarsPadding()) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                            progress?.let { Text(it, modifier = Modifier.padding(8.dp)) }
                            LazyRow(
                                state = thumbnails,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(total, key = { it }) { index ->
                                    val selected = index == visiblePage
                                    val shape = MaterialTheme.shapes.small
                                    AsyncImage(
                                        model = remember(gallery.id, gallery.pagePaths[index]) {
                                            viewModel.pageModel(gallery.id, gallery.pagePaths[index])
                                        },
                                        contentDescription = stringResource(R.string.manga_jump_page, index + 1),
                                        modifier = Modifier
                                            .size(64.dp, 88.dp)
                                            .clip(shape)
                                            .alpha(if (selected) 1f else 0.55f)
                                            .border(
                                                if (selected) 2.dp else 0.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape
                                            )
                                            .clickable { jump(index) },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet) {
        Dialog(onDismissRequest = { sheet = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.manga_reader_settings),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { sheet = false }) {
                            Icon(Icons.Default.Close, stringResource(R.string.btn_close))
                        }
                    }
                    HorizontalDivider()
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingRow(
                            Icons.Default.SwapVert, R.string.reader_orientation,
                            onClick = {
                                change(settings.copy(orientation = if (vertical) ReaderOrientation.Horizontal else ReaderOrientation.Vertical))
                            }
                        ) {
                            Text(
                                stringResource(if (vertical) R.string.reader_vertical else R.string.reader_horizontal),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        SettingRow(
                            Icons.Default.FitScreen, R.string.manga_reader_fit,
                            onClick = {
                                change(settings.copy(fit = if (settings.fit == ReaderFit.Contain) ReaderFit.Width else ReaderFit.Contain))
                            }
                        ) {
                            Text(
                                stringResource(if (settings.fit == ReaderFit.Contain) R.string.manga_fit_contain else R.string.manga_fit_width),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        SwitchRow(Icons.Default.TouchApp, R.string.reader_tap_to_switch, settings.tapToSwitch) {
                            change(settings.copy(tapToSwitch = it))
                        }
                        SwitchRow(Icons.Default.SwapHoriz, R.string.reader_hand_swap, settings.handSwap) {
                            change(settings.copy(handSwap = it))
                        }
                        SwitchRow(Icons.Default.Search, R.string.reader_inspect, settings.inspect) {
                            change(settings.copy(inspect = it))
                        }
                        SwitchRow(Icons.Default.ViewAgenda, R.string.reader_continuous, settings.continuous) {
                            change(settings.copy(continuous = it))
                        }
                        SwitchRow(Icons.Default.VisibilityOff, R.string.reader_hide_hints, settings.hideHints) {
                            change(settings.copy(hideHints = it))
                        }
                        SettingRow(
                            Icons.Default.Translate, R.string.translate_mode,
                            onClick = {
                                val next = TranslateMode.entries[(settings.translateMode.ordinal + 1) % TranslateMode.entries.size]
                                val before = settings.translateMode
                                change(settings.copy(translateMode = next))
                                if (next == TranslateMode.Whole) {
                                    panelOpen = true
                                    translation.startWhole(settings)
                                } else if (before == TranslateMode.Whole) {
                                    translation.cancelWhole()
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    when (settings.translateMode) {
                                        TranslateMode.Manual -> R.string.translate_mode_manual
                                        TranslateMode.Bubble -> R.string.translate_mode_bubble
                                        TranslateMode.OnTurn -> R.string.translate_mode_on_turn
                                        TranslateMode.Whole -> R.string.translate_mode_whole
                                    }
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TranslationSettingRows(settings, ::change)
                        SettingRow(
                            Icons.Default.Settings, R.string.settings_translation_row,
                            onClick = { providerDialog = true }
                        ) {
                            Text(ProviderUrl.host(provider.baseUrl).orEmpty(), color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                        Text(stringResource(R.string.manga_haptic), modifier = Modifier.padding(top = 12.dp))
                        Slider(
                            value = intensity,
                            onValueChange = {
                                viewModel.setIntensity(it)
                                prefs.edit().putFloat("intensity", it).apply()
                            },
                            valueRange = 0.1f..1f
                        )
                        TextButton(onClick = viewModel::saveCurrent) { Text(stringResource(R.string.manga_save)) }
                    }
                }
            }
        }
    }
    if (needsConsent) {
        TranslateConsentDialog(provider.baseUrl, translation::acceptConsent, translation::declineConsent)
    }
    if (providerDialog) {
        ProviderDialog(
            current = provider,
            onSave = { url, model, key -> translation.saveProvider { it.edited(url, model, key) } },
            onTest = translation::testProvider,
            engine = settings.engine,
            onModels = translation::listModels,
            onMeasure = translation::measureModel,
            onSpeeds = translation::modelSpeeds,
            onDismiss = { providerDialog = false }
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null)
        Text(stringResource(label), Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, label, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ZoomButton(icon: ImageVector, description: Int, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
        IconButton(onClick = onClick) {
            Icon(icon, stringResource(description), tint = Color.White)
        }
    }
}

/** Faint labels over the tap zones; shown for a moment when the reader opens. */
@Composable
private fun TapHints(vertical: Boolean, swapped: Boolean) {
    val first = if (swapped) R.string.reader_hint_next else R.string.reader_hint_back
    val last = if (swapped) R.string.reader_hint_back else R.string.reader_hint_next
    @Composable
    fun label(text: Int, modifier: Modifier) {
        Box(modifier.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Text(stringResource(text), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (vertical) {
        Column(Modifier.fillMaxSize()) {
            label(first, Modifier.weight(1f).fillMaxWidth())
            label(R.string.reader_hint_menu, Modifier.weight(1f).fillMaxWidth().alpha(0.5f))
            label(last, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            label(first, Modifier.weight(1f).fillMaxSize())
            label(R.string.reader_hint_menu, Modifier.weight(1f).fillMaxSize().alpha(0.5f))
            label(last, Modifier.weight(1f).fillMaxSize())
        }
    }
}

private fun LazyListState.centeredPage(): Int {
    val layout = layoutInfo
    val visible = layout.visibleItemsInfo
    val nearest = centerPage(
        visible.map { it.offset + it.size / 2 },
        (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    )
    return visible.getOrNull(nearest)?.index ?: -1
}

/** How far [page] has been scrolled past the top of the viewport, as a share of its height. */
private fun LazyListState.offsetFractionOf(page: Int): Float {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == page } ?: return 0f
    return pageOffsetFraction(item.offset, item.size)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderPage(
    model: Any?,
    page: Int,
    active: Boolean,
    zoom: ReaderZoom,
    fillWidth: Boolean,
    strip: Boolean,
    tapOnY: Boolean,
    modifier: Modifier,
    viewportHeight: androidx.compose.ui.unit.Dp,
    startOffset: Float,
    resetAt: Int,
    onResetConsumed: () -> Unit,
    onScroll: (Float, Boolean) -> Unit,
    onStripMeasured: (Int) -> Unit,
    lines: List<TranslatedLine>,
    onBubbleTap: ((Offset, () -> Unit) -> Unit)?,
    selectedLine: TranslatedLine? = null,
    onSelectionOmitted: (TranslatedLine) -> Unit = {},
    onTap: (Float, Float) -> Unit
) {
    var aspect by remember(model) { mutableFloatStateOf(0.7f) }
    var aspectKnown by remember(model) { mutableStateOf(false) }
    var aspectReady by remember(model) { mutableStateOf(false) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    val paged = fillWidth && !strip
    val scrollState = rememberScrollState()
    val restore = remember(paged) { PagedScrollRestore(startOffset) }
    var pageHeight by remember { mutableIntStateOf(0) }
    val latestScroll by rememberUpdatedState(onScroll)
    val latestPageHeight by rememberUpdatedState(pageHeight)
    val latestStripMeasured by rememberUpdatedState(onStripMeasured)
    val latestTap by rememberUpdatedState(onTap)
    val latestBubbleTap by rememberUpdatedState(onBubbleTap)
    var frameCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var imageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        if (active) zoom.transform(zoomChange, panChange)
    }
    LaunchedEffect(active, frame) {
        if (active) {
            zoom.width = frame.width.toFloat()
            zoom.height = frame.height.toFloat()
        }
    }
    // The strip restore needs the item height laid out from the real image aspect. The first measure uses
    // the 0.7 placeholder, so report only after a successful load supplies that aspect. A failed load
    // leaves the restore pending: the stored share must not be replaced by a placeholder sample.
    LaunchedEffect(strip, fillWidth, aspectReady, aspect, pageHeight, frame.width, frame.height) {
        if (!strip || pageHeight <= 0 || frame.width <= 0) return@LaunchedEffect
        if (fillWidth) {
            if (!aspectReady) return@LaunchedEffect
            if (abs(pageHeight - frame.width / aspect) > 1f) return@LaunchedEffect
        }
        latestStripMeasured(frame.height)
    }
    // A paged page scrolls inside itself while its image is taller than the window. Its zero start is not
    // the reader's spot until the stored share has been applied on the measured page.
    LaunchedEffect(paged, aspectKnown, aspect, pageHeight, frame.width, frame.height) {
        if (!paged || !aspectKnown || pageHeight <= 0 || frame.width <= 0) return@LaunchedEffect
        // The first measure uses the placeholder aspect; only the size laid out for the real image counts.
        if (abs(pageHeight - frame.width / aspect) > 1f) return@LaunchedEffect
        if (restore.pixels(pageHeight) == null) return@LaunchedEffect
        if (pageHeight > frame.height) {
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            // A jump reset may have consumed the restore while this waited for layout.
            val pending = restore.pixels(pageHeight) ?: return@LaunchedEffect
            scrollState.scrollTo(pending)
            restore.applied()
            latestScroll(pagedOffsetFraction(scrollState.value, pageHeight), true)
        } else {
            // The whole page fits: there is no in-page spot to move to, so a stored share stays for a
            // layout where the page is taller than the window.
            restore.applied()
        }
    }
    LaunchedEffect(resetAt, paged) {
        if (resetAt <= 0 || !paged) return@LaunchedEffect
        // The reader chose this page on purpose, so an older in-session spot must not win.
        restore.applied()
        scrollState.scrollTo(0)
        latestScroll(0f, true)
        onResetConsumed()
    }
    LaunchedEffect(paged, active, pageHeight) {
        if (!paged || !active || pageHeight <= 0) return@LaunchedEffect
        snapshotFlow {
            Triple(scrollState.value, !scrollState.isScrollInProgress, scrollState.maxValue)
        }.collect { (value, idle, max) ->
            // Samples before the restore describe the top of an unmoved page, not where the reader was.
            if (restore.restored && max > 0) latestScroll(pagedOffsetFraction(value, pageHeight), idle)
        }
    }
    DisposableEffect(paged, active) {
        onDispose {
            // Leaving the page, including Back, keeps the spot even when a gesture never went idle.
            if (paged && active && restore.restored && latestPageHeight > 0 && scrollState.maxValue > 0) {
                latestScroll(pagedOffsetFraction(scrollState.value, latestPageHeight), true)
            }
        }
    }
    Box(
        modifier.clipToBounds()
            .onSizeChanged { frame = it }
            .then(if (onBubbleTap != null) Modifier.onGloballyPositioned { frameCoordinates = it } else Modifier)
            .pointerInput(tapOnY, active) {
                detectTapGestures(
                    onTap = { tap ->
                        val position = if (tapOnY) tap.y else tap.x
                        val extent = (if (tapOnY) size.height else size.width).toFloat()
                        val handler = latestBubbleTap
                        val image = imageCoordinates
                        val source = frameCoordinates
                        val at = if (handler != null && aspectReady && image?.isAttached == true && source?.isAttached == true) {
                            // Compose inverts the actual scroll, placement and graphics-layer transform.
                            val local = image.localPositionOf(source, tap)
                            val rect = fittedPage(image.size.width.toFloat(), image.size.height.toFloat(), aspect)
                            if (rect.contains(local)) Offset((local.x - rect.left) / rect.width, (local.y - rect.top) / rect.height) else null
                        } else null
                        if (handler != null && at != null) handler(at) { latestTap(position, extent) }
                        else latestTap(position, extent)
                    },
                    onDoubleTap = { if (active) zoom.doubleTap(it) }
                )
            }
            .transformable(state = transform, canPan = { zoom.zoomed }, enabled = active),
        contentAlignment = Alignment.Center
    ) {
        val imageModifier = if (fillWidth) Modifier.fillMaxWidth().aspectRatio(aspect)
        else Modifier.fillMaxWidth().height(viewportHeight)
        val container = if (paged) {
            Modifier.fillMaxSize().verticalScroll(scrollState)
        } else Modifier.fillMaxWidth()
        Box(container, contentAlignment = Alignment.Center) {
            // The image and the overlay share one box, so zoom and pan move the plates with the page.
            Box(
                imageModifier.onSizeChanged { pageHeight = it.height }.graphicsLayer {
                    if (active) {
                        scaleX = zoom.zoom
                        scaleY = zoom.zoom
                        translationX = zoom.pan.x
                        translationY = zoom.pan.y
                    }
                }
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = stringResource(R.string.manga_jump_page, page + 1),
                    modifier = Modifier.fillMaxSize()
                        .then(if (onBubbleTap != null) Modifier.onGloballyPositioned { imageCoordinates = it } else Modifier),
                    contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
                    onSuccess = {
                        val drawable = it.result.drawable
                        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                            aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                            aspectReady = true
                        }
                        aspectKnown = true
                    },
                    onError = { aspectKnown = true }
                )
                key(model, page) {
                    TranslationOverlay(lines, aspect, selectedLine = selectedLine, onSelectionOmitted = onSelectionOmitted)
                }
            }
        }
    }
}

