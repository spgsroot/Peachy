package ru.spgsroot.peachy.ui.manga

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.PageTurnGate
import ru.spgsroot.peachy.manga.PageTurnHaptic
import ru.spgsroot.peachy.manga.TapZone
import ru.spgsroot.peachy.manga.centerPage
import ru.spgsroot.peachy.manga.startPage
import ru.spgsroot.peachy.manga.tapZone
import ru.spgsroot.peachy.manga.EditionMetadata
import ru.spgsroot.peachy.manga.LibraryGallery
import ru.spgsroot.peachy.manga.MangaIdentityProjector
import ru.spgsroot.peachy.manga.NhentaiCdn

@Composable
fun MangaLibraryScreen(
    navController: NavController,
    viewModel: MangaViewModel,
    favoritesOnly: Boolean
) {
    val library by viewModel.libraryItems.collectAsStateWithLifecycle()
    val identitySnapshot by viewModel.identitySnapshot.collectAsStateWithLifecycle()
    val remembered by viewModel.remembered.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyById = remember(history) { history.associateBy { it.galleryId } }
    val appLanguage = tagLanguage()
    val preferredLanguages = remember(appLanguage) {
        MangaIdentityProjector.preferredLanguagesFor(emptyList(), appLanguage)
    }
    val shown = remember(library, favoritesOnly) {
        if (favoritesOnly) library.filter { it.favorite } else library
    }
    val projectedGroups = remember(shown, remembered, preferredLanguages, identitySnapshot) {
        MangaIdentityProjector.projectList(
            items = shown,
            idOf = { it.id },
            metadataOf = { item ->
                val mem = remembered[item.id]
                EditionMetadata(
                    galleryId = item.id,
                    displayTitle = mem?.title?.ifBlank { item.title } ?: item.title,
                    japaneseTitle = mem?.japaneseTitle,
                    thumbnailPath = item.thumbnailPath,
                    numPages = item.pagePaths.size.takeIf { it > 0 } ?: (mem?.numPages ?: 0),
                    artistIds = mem?.tags?.filter { it.type == "artist" && it.id > 0 }?.map { it.id }?.distinct().orEmpty(),
                    language = mem?.tags?.firstOrNull { it.type == "language" && it.name != "translated" }?.name,
                    tagsComplete = mem != null && mem.tags.isNotEmpty(),
                    updatedAt = mem?.seenAt ?: 0L
                )
            },
            preferredLanguages = preferredLanguages,
            snapshot = identitySnapshot
        )
    }
    var expandedGroups by rememberSaveable(favoritesOnly) { mutableStateOf(listOf<String>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (projectedGroups.isEmpty()) {
            item { Text(stringResource(R.string.manga_none), style = MaterialTheme.typography.bodyMedium) }
        }
        items(projectedGroups, key = { it.key }) { group ->
            val item = group.primary
            val expanded = group.key in expandedGroups
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryCard(
                    item = item,
                    onOpen = { navController.navigate("manga/${item.id}") },
                    onRemove = { viewModel.remove(item.id) }
                )
                ProjectedGroupFooter(
                    group = group,
                    idOf = { it.id },
                    titleOf = { it.title },
                    languageOf = { member ->
                        remembered[member.id]?.tags?.firstOrNull { it.type == "language" && it.name != "translated" }?.name
                            ?: identitySnapshot.editions[member.id]?.language
                    },
                    snapshot = identitySnapshot,
                    expanded = expanded,
                    onToggleExpand = {
                        expandedGroups = if (expanded) expandedGroups - group.key else expandedGroups + group.key
                    },
                    onOpenEdition = { id -> navController.navigate("manga/$id") },
                    onConfirmSame = viewModel::confirmSameChapter,
                    onReject = viewModel::rejectSuggestion,
                    onRemoveEdition = viewModel::remove,
                    legacyLastPageOf = { id ->
                        val row = historyById[id]
                        row != null && MangaIdentityProjector.isLegacyLastPageOnly(
                            row.page,
                            row.pageCount,
                            MangaIdentityProjector.readStatus(id, identitySnapshot)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: LibraryGallery,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(item.thumbnailPath)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.favorite) {
                        Icon(Icons.Default.Favorite, stringResource(R.string.manga_favorites))
                    }
                }
                Text("${item.pagePaths.size}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRemove) { Text(stringResource(R.string.manga_remove)) }
            }
        }
    }
}

@Composable
private fun Cover(path: String) {
    AsyncImage(
        model = NhentaiCdn.thumb(path),
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Crop
    )
}
