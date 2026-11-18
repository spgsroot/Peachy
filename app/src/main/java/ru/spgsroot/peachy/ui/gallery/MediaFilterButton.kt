package ru.spgsroot.peachy.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R

/**
 * The filter icon opens a dialog whose valid edits reach [onChange] as they are made, so there is no
 * Apply step and closing the dialog keeps everything already applied.
 */
@Composable
fun MediaFilterButton(current: MediaFilter, onChange: (MediaFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.FilterList, stringResource(R.string.media_filters),
            tint = if (current == MediaFilter()) LocalContentColor.current else MaterialTheme.colorScheme.primary)
    }
    if (open) MediaFilterDialog(current, onDismiss = { open = false }, onChange = onChange)
}

/** Shared by the gallery app bar and the player's actions overflow, which hosts the button. */
@Composable
internal fun MediaFilterDialog(current: MediaFilter, onDismiss: () -> Unit, onChange: (MediaFilter) -> Unit) {
    var sort by remember { mutableStateOf(current.sort) }
    var minText by remember { mutableStateOf(current.minBytes?.let(::mibText).orEmpty()) }
    var maxText by remember { mutableStateOf(current.maxBytes?.let(::mibText).orEmpty()) }
    // Live check for the error line; the same rule decides whether an edit is applied at all.
    val applicable = filterFromText(sort, minText, maxText, current.type)

    /** Applies the current fields when they form a usable filter; an unusable range edits nothing. */
    fun applyCurrentFields() {
        filterFromText(sort, minText, maxText, current.type)?.let(onChange)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_filters)) },
        text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in MediaSort.entries) {
                    TextButton(
                        onClick = { sort = option; applyCurrentFields() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = sort == option, onClick = null)
                        Text(stringResource(option.label()), Modifier.weight(1f))
                    }
                }
                OutlinedTextField(minText, { minText = it; applyCurrentFields() }, label = { Text(stringResource(R.string.media_min_size)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxText, { maxText = it; applyCurrentFields() }, label = { Text(stringResource(R.string.media_max_size)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (applicable == null) Text(stringResource(R.string.media_size_range_error), color = MaterialTheme.colorScheme.error)
            }
        },
        // Nothing was staged, so closing is not a cancel and the list keeps the live filter.
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        },
        dismissButton = {
            TextButton(onClick = {
                sort = MediaSort.Newest
                minText = ""
                maxText = ""
                onChange(MediaFilter(type = current.type))
            }) { Text(stringResource(R.string.behavior_media_filter_reset)) }
        }
    )
}

private const val MIB = 1_048_576.0

private fun mibText(bytes: Long): String = (bytes.toDouble() / MIB).toString()

/**
 * The filter for the typed bounds, or null while they cannot be applied: a partly typed or negative
 * size and a maximum below the minimum never reach the list. A blank field leaves that end open.
 */
internal fun filterFromText(sort: MediaSort, minText: String, maxText: String, type: GalleryMediaType?): MediaFilter? {
    val minBytes = if (minText.isBlank()) null else parseMib(minText) ?: return null
    val maxBytes = if (maxText.isBlank()) null else parseMib(maxText) ?: return null
    if (minBytes != null && maxBytes != null && maxBytes < minBytes) return null
    return MediaFilter(sort = sort, minBytes = minBytes, maxBytes = maxBytes, type = type)
}

private fun parseMib(text: String): Long? =
    text.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() / MIB }
        ?.let { (it * MIB).toLong() }

private fun MediaSort.label(): Int = when (this) {
    MediaSort.Newest -> R.string.media_sort_newest
    MediaSort.NameAscending -> R.string.media_sort_name_asc
    MediaSort.NameDescending -> R.string.media_sort_name_desc
    MediaSort.SizeAscending -> R.string.media_sort_size_asc
    MediaSort.SizeDescending -> R.string.media_sort_size_desc
}
