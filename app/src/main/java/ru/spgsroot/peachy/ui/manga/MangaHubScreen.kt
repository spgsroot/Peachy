package ru.spgsroot.peachy.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.HeaderSearch
import ru.spgsroot.peachy.manga.SearchDestination
import ru.spgsroot.peachy.manga.searchDestination
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.GalleryTag
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode
import ru.spgsroot.peachy.manga.TagRef

/** Hub sections that live inside the drawer; the tag browser and random gallery open other screens. */
internal enum class HubDestination(val label: Int, val icon: ImageVector) {
    Catalog(R.string.manga_catalog, Icons.Filled.Search),
    ForYou(R.string.manga_for_you, Icons.Filled.Star),
    Downloads(R.string.manga_drawer_downloads, Icons.Filled.Download),
    Favorites(R.string.manga_favorites, Icons.Filled.Favorite),
    SavedPages(R.string.manga_saved_pages, Icons.Filled.Bookmark),
    History(R.string.manga_history, Icons.Filled.History)
}

private fun hubOf(raw: String?): HubDestination =
    HubDestination.entries.firstOrNull { it.name == raw } ?: HubDestination.Catalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaHubScreen(
    navController: NavController,
    entry: NavBackStackEntry,
    initialHub: String? = null,
    onHub: (String) -> Unit = {},
    viewModel: MangaViewModel = hiltViewModel()
) {
    val initialFilters = remember(entry) { decodeFilters(entry.savedStateHandle.get<List<String>>(TAG_FILTERS_KEY).orEmpty()) }
    var destination by rememberSaveable {
        mutableStateOf(if (initialFilters.isEmpty()) hubOf(initialHub) else HubDestination.Catalog)
    }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tabState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }

    val randomFailed by viewModel.randomFailed.collectAsStateWithLifecycle()
    val randomError = stringResource(R.string.manga_random_error)
    LaunchedEffect(randomFailed) { if (randomFailed) snackbar.showSnackbar(randomError) }
    LaunchedEffect(destination) { onHub(destination.name) }

    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    var queryText by rememberSaveable(catalog.query.text) { mutableStateOf(catalog.query.text) }
    val tagLibrary by viewModel.tagLibrary.collectAsStateWithLifecycle()
    val favoriteTags = remember(tagLibrary) { tagLibrary.favorites.toSet() }

    LaunchedEffect(destination, entry) {
        if (destination == HubDestination.Catalog && !viewModel.catalog.value.searched) {
            viewModel.search(CatalogQuery(tags = initialFilters))
        }
    }

    fun searchTag(tag: GalleryTag) {
        navController.searchCatalog(listOf(TagFilter(tag.type, tag.name, TagMode.Include)))
    }

    fun go(action: () -> Unit) {
        scope.launch {
            // Snap rather than animate: an animation cut short by navigation leaves the drawer saved as open.
            drawerState.snapTo(DrawerValue.Closed)
            action()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MangaHubDrawerContent(
                destination = destination,
                onDestination = { item -> go { destination = item } },
                onTags = { go { navController.navigate("mangatags/tag") } },
                onCharacters = { go { navController.navigate("mangatags/character") } },
                onRandom = { go { viewModel.openRandom { navController.navigate("manga/$it") } } }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        if (destination == HubDestination.Catalog) {
                            HeaderSearch(queryText, { queryText = it }, stringResource(R.string.manga_search_hint)) {
                                when (val target = searchDestination(queryText)) {
                                    is SearchDestination.OpenGallery -> navController.navigate("manga/${target.id}")
                                    SearchDestination.RunSearch -> viewModel.search(catalog.query.copy(text = queryText))
                                }
                            }
                        } else Text(stringResource(destination.label))
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, stringResource(R.string.manga_menu_open))
                        }
                    },
                    actions = {
                        if (destination == HubDestination.Catalog) {
                            IconButton(onClick = { showFilters = true }) {
                                BadgedBox(
                                    badge = {
                                        if (catalog.query.tags.isNotEmpty()) {
                                            Badge { Text(catalog.query.tags.size.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.FilterList, stringResource(R.string.manga_filters))
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                tabState.SaveableStateProvider(destination.name) {
                    when (destination) {
                        HubDestination.Catalog -> CatalogScreen(
                            viewModel,
                            onOpen = { navController.navigate("manga/$it") },
                            showFilters = showFilters,
                            onFiltersDismiss = { showFilters = false },
                            text = queryText,
                            onTextChange = { queryText = it }
                        )
                        HubDestination.Downloads -> MangaLibraryScreen(navController, viewModel, favoritesOnly = false)
                        HubDestination.Favorites -> MangaLibraryScreen(navController, viewModel, favoritesOnly = true)
                        HubDestination.SavedPages -> SavedPagesScreen(navController, viewModel, snackbar)
                        HubDestination.History -> HistoryScreen(
                            viewModel,
                            onOpen = { navController.navigate("manga/$it") },
                            onTag = ::searchTag,
                            onTagLong = { viewModel.toggleFavoriteTag(TagRef(it.type, it.name)) },
                            favoriteTags = favoriteTags
                        )
                        HubDestination.ForYou -> ForYouScreen(
                            onOpen = { navController.navigate("manga/$it") },
                            onTag = ::searchTag,
                            onTagLong = { viewModel.toggleFavoriteTag(TagRef(it.type, it.name)) },
                            favoriteTags = favoriteTags
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MangaHubDrawerContent(
    destination: HubDestination,
    onDestination: (HubDestination) -> Unit,
    onTags: () -> Unit,
    onCharacters: () -> Unit,
    onRandom: () -> Unit
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.manga_hub),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
            )
            HubDestination.entries.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(stringResource(item.label)) },
                    icon = { Icon(item.icon, null) },
                    selected = destination == item,
                    onClick = { onDestination(item) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.manga_tags)) },
                icon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                selected = false,
                onClick = onTags,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.manga_drawer_characters)) },
                icon = { Icon(Icons.Filled.Person, null) },
                selected = false,
                onClick = onCharacters,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.manga_drawer_random)) },
                icon = { Icon(Icons.Filled.Shuffle, null) },
                selected = false,
                onClick = onRandom,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    viewModel: MangaViewModel,
    onOpen: (Int) -> Unit,
    onTag: (GalleryTag) -> Unit,
    onTagLong: (GalleryTag) -> Unit,
    favoriteTags: Set<TagRef>
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()
    val identitySnapshot by viewModel.identitySnapshot.collectAsStateWithLifecycle()
    if (history.isEmpty()) {
        Text(stringResource(R.string.manga_history_empty), modifier = Modifier.padding(16.dp))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(history, key = { it.galleryId }) { entry ->
            val memory = remembered[entry.galleryId]
            val status = ru.spgsroot.peachy.manga.MangaIdentityProjector.readStatus(entry.galleryId, identitySnapshot)
            val legacyLastPage = ru.spgsroot.peachy.manga.MangaIdentityProjector.isLegacyLastPageOnly(
                entry.page,
                entry.pageCount,
                status
            )
            val link = identitySnapshot.links[entry.galleryId]
            val unit = link?.let { identitySnapshot.units[it.unitId] }
            val workProgress = link?.let {
                ru.spgsroot.peachy.manga.MangaIdentityProjector.workProgress(it.workId, identitySnapshot)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GalleryGridCard(
                    title = entry.title,
                    thumbnailPath = entry.thumbnailPath,
                    pages = entry.pageCount,
                    tags = memory?.tags.orEmpty(),
                    favorites = memory?.numFavorites,
                    uploadedAt = memory?.uploadedAt,
                    onOpen = { onOpen(entry.galleryId) },
                    onTag = onTag,
                    onTagLong = onTagLong,
                    favoriteTags = favoriteTags
                )
                val progressLabel = when {
                    entry.page >= 0 && entry.pageCount > 0 -> stringResource(
                        R.string.manga_identity_history_page,
                        entry.page + 1,
                        entry.pageCount
                    )
                    entry.page == -1 && entry.pageCount == 0 -> stringResource(R.string.manga_identity_history_marked)
                    else -> null
                }
                if (progressLabel != null) {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReadStatusPill(status = status, legacyLastPage = legacyLastPage)
                if (unit != null) {
                    Text(
                        text = UnitLabelText(unit.label, unit.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                workProgress?.takeIf { it.knownChapters > 0 }?.let { WorkProgressLine(it) }
            }
        }
    }
}
