package ru.spgsroot.peachy.ui.player

import android.text.format.Formatter
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Size
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.domain.model.Video

private val CardWidth = 168.dp
private val ThumbnailHeight = 94.dp

/** Thumbnails are decoded at twice the drawn size so they stay sharp on dense screens. */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun QueueCarousel(
    queue: List<Video>,
    currentId: Long?,
    onSelect: (Video) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbnailSize = remember(density) {
        with(density) { Size((CardWidth * 2).roundToPx(), (ThumbnailHeight * 2).roundToPx()) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(queue, currentId) {
        val index = queue.indexOfFirst { it.id == currentId }
        if (index >= 0) state.animateScrollToItem(index)
    }
    // Only the user's own drag keeps the bar alive, not the automatic scroll to the current video.
    val dragged by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        while (dragged) { onInteraction(); delay(1000) }
        onInteraction()
    }
    if (queue.isEmpty()) {
        Text(stringResource(R.string.gallery_search_empty), modifier.padding(12.dp), color = Color.White)
        return
    }
    LazyRow(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(queue, key = { it.id }) { video ->
            Card(
                onClick = { onInteraction(); onSelect(video) },
                modifier = Modifier.width(CardWidth).then(
                    if (video.id == currentId) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier
                ),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = .55f), contentColor = Color.White)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(video.thumbnailPath ?: video.filePath).size(thumbnailSize)
                        .crossfade(true)
                        .apply { if (video.thumbnailPath == null) { decoderFactory(VideoFrameDecoder.Factory()); videoFrameMillis(3000) } }
                        .build(),
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().height(ThumbnailHeight),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High
                )
                Text(video.title, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(
                    Formatter.formatShortFileSize(context, video.fileSize),
                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = .7f)
                )
            }
        }
    }
}
