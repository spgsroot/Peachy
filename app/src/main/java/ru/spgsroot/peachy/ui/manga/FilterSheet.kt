package ru.spgsroot.peachy.ui.manga

import ru.spgsroot.peachy.manga.TagNames
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.manga.CatalogQuery
import ru.spgsroot.peachy.manga.PagesFilter
import ru.spgsroot.peachy.manga.SortKey
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode
import ru.spgsroot.peachy.manga.customUploadedRange
import ru.spgsroot.peachy.manga.nextTagMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

/** A keystroke burst in the date or page fields settles before a request is sent. */
internal const val FILTER_SETTLE_MS = 250L

private val datePresets = listOf(
    "uploaded:>2h" to R.string.manga_date_2h,
    "uploaded:>24h" to R.string.manga_date_24h,
    "uploaded:>3d" to R.string.manga_date_3d,
    "uploaded:>7d" to R.string.manga_date_7d,
    "uploaded:>30d" to R.string.manga_date_30d,
    "uploaded:>90d" to R.string.manga_date_90d,
    "uploaded:>180d" to R.string.manga_date_180d,
    "uploaded:>1y" to R.string.manga_date_1y
)
private val languages = listOf(
    "english" to R.string.manga_language_english,
    "japanese" to R.string.manga_language_japanese,
    "chinese" to R.string.manga_language_chinese,
    "translated" to R.string.manga_language_translated
)
private val sorts = listOf(
    SortKey.Date to R.string.manga_sort_date,
    SortKey.Popular to R.string.manga_sort_popular,
    SortKey.PopularToday to R.string.manga_sort_today,
    SortKey.PopularWeek to R.string.manga_sort_week,
    SortKey.PopularMonth to R.string.manga_sort_month
)

/**
 * The editable filter sheet state. Every add, remove, cycle and reset goes through this draft, and the
 * debounced request is built from it, so all filter changes travel the same path.
 */
internal data class CatalogFilterDraft(
    val tags: List<TagFilter> = emptyList(),
    val sort: SortKey = SortKey.Date,
    val preset: String = "",
    val start: String = "",
    val end: String = "",
    val minPages: String = "",
    val maxPages: String = "",
    val exactPages: String = ""
) {
    val datesValid: Boolean get() = validDate(start) && validDate(end)
    val reversed: Boolean get() = datesValid && start.isNotBlank() && end.isNotBlank() && end < start

    /** The search text stays outside the sheet, so a changed field is never searched as stale text. */
    fun toQuery(text: String = ""): CatalogQuery = CatalogQuery(
        text = text,
        tags = tags,
        sort = sort,
        uploaded = if (preset.isNotEmpty()) preset else customUploadedRange(start, end),
        pages = PagesFilter(minPages.toIntOrNull(), maxPages.toIntOrNull(), exactPages.toIntOrNull())
    )

    /** Include, then exclude, then remove; every step is a new draft and therefore a new request. */
    fun cycleTag(type: String, name: String): CatalogFilterDraft {
        val mode = nextTagMode(tags.find { it.type == type && it.name == name }?.mode)
        return copy(
            tags = tags.filterNot { it.type == type && it.name == name } +
                if (mode == null) emptyList() else listOf(TagFilter(type, name, mode))
        )
    }

    fun addTag(type: String, name: String): CatalogFilterDraft =
        if (tags.any { it.type == type && it.name == name }) this
        else copy(tags = tags + TagFilter(type, name, TagMode.Include))

    /** The reset button clears every sheet-owned filter; the search text is not part of the sheet. */
    fun reset(): CatalogFilterDraft = CatalogFilterDraft()

    companion object {
        fun from(query: CatalogQuery): CatalogFilterDraft {
            val preset = query.uploaded.takeIf { value -> datePresets.any { it.first == value } }.orEmpty()
            return CatalogFilterDraft(
                tags = query.tags,
                sort = query.sort,
                preset = preset,
                start = if (preset.isEmpty()) {
                    query.uploaded.split(' ').find { it.startsWith("uploaded:>") }?.removePrefix("uploaded:>").orEmpty()
                } else {
                    ""
                },
                end = query.uploaded.split(' ').find { it.startsWith("uploaded:<") }?.removePrefix("uploaded:<").orEmpty(),
                minPages = query.pages.min?.toString().orEmpty(),
                maxPages = query.pages.max?.toString().orEmpty(),
                exactPages = query.pages.exact?.toString().orEmpty()
            )
        }
    }
}

/**
 * Submits a filter draft once it has settled: waits [FILTER_SETTLE_MS] so a keystroke burst sends one
 * request, and refuses invalid or reversed dates and a draft that is already shown. The caller's effect
 * restarts on every edit, so a cancelled wait never submits and only the newest draft wins.
 */
internal suspend fun settledFilterQuery(
    draft: CatalogQuery,
    datesValid: Boolean,
    reversed: Boolean,
    applied: CatalogQuery,
    onApplied: (CatalogQuery) -> Unit
): CatalogQuery? {
    if (!datesValid || reversed || draft == applied) return null
    delay(FILTER_SETTLE_MS)
    onApplied(draft)
    return draft
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    query: CatalogQuery,
    blockedIds: Set<Int>,
    blockedNames: Set<String>,
    onBlockId: (Int) -> Unit,
    onUnblockId: (Int) -> Unit,
    onBlockName: (String) -> Unit,
    onUnblockName: (String) -> Unit,
    onDismiss: () -> Unit,
    onQueryChange: (CatalogQuery) -> Unit
) {
    var draft by remember { mutableStateOf(CatalogFilterDraft.from(query)) }
    var tagType by remember { mutableStateOf("tag") }
    var tagName by remember { mutableStateOf("") }
    var blockedName by remember { mutableStateOf("") }
    var blockedId by remember { mutableStateOf("") }
    val draftQuery = draft.toQuery(query.text)
    // The query the screen already shows, so opening the panel never repeats a request.
    var applied by remember { mutableStateOf(draftQuery) }
    LaunchedEffect(draftQuery, draft.datesValid, draft.reversed) {
        // A half-typed or reversed range leaves the previous results alone until it is valid again.
        settledFilterQuery(draftQuery, draft.datesValid, draft.reversed, applied) { applied = it }
            ?.let(onQueryChange)
    }

    fun cycleTag(type: String, name: String) {
        draft = draft.cycleTag(type, name)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text(stringResource(R.string.manga_filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    FilterSection(R.string.manga_tags, hint = R.string.manga_tag_cycle) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tagTypes.forEach { (type, label) ->
                                FilterChip(selected = tagType == type, onClick = { tagType = type }, label = { Text(stringResource(label)) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = tagName, onValueChange = { tagName = it }, singleLine = true,
                                label = { Text(stringResource(R.string.manga_tag_name)) }, modifier = Modifier.weight(1f))
                            TextButton(enabled = tagName.isNotBlank(), onClick = {
                                draft = draft.addTag(tagType, tagName.trim())
                                tagName = ""
                            }) { Text(stringResource(R.string.manga_add_tag)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            draft.tags.forEach { tag ->
                                FilterChip(selected = tag.mode == TagMode.Include, onClick = { cycleTag(tag.type, tag.name) }, label = {
                                    Text(stringResource(if (tag.mode == TagMode.Include) R.string.manga_tag_included else R.string.manga_tag_excluded, "${tag.type}:${TagNames.display(tag.type, tag.name, tagLanguage())}"))
                                })
                            }
                        }
                    }
                }
                item {
                    FilterSection(R.string.manga_language) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            languages.forEach { (name, label) ->
                                val mode = draft.tags.find { it.type == "language" && it.name == name }?.mode
                                FilterChip(selected = mode != null, onClick = { cycleTag("language", name) }, label = {
                                    val language = stringResource(label)
                                    Text(when (mode) {
                                        TagMode.Include -> stringResource(R.string.manga_tag_included, language)
                                        TagMode.Exclude -> stringResource(R.string.manga_tag_excluded, language)
                                        null -> language
                                    })
                                })
                            }
                        }
                    }
                }
                item {
                    FilterSection(R.string.manga_sort) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sorts.forEach { (key, label) ->
                                FilterChip(selected = draft.sort == key, onClick = { draft = draft.copy(sort = key) }, label = { Text(stringResource(label)) })
                            }
                        }
                    }
                }
                item {
                    FilterSection(R.string.manga_uploaded) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            datePresets.forEach { (value, label) ->
                                FilterChip(selected = draft.preset == value, onClick = {
                                    draft = draft.copy(
                                        preset = if (draft.preset == value) "" else value,
                                        start = "",
                                        end = ""
                                    )
                                }, label = { Text(stringResource(label)) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = draft.start, onValueChange = { draft = draft.copy(start = it, preset = "") }, singleLine = true,
                                label = { Text(stringResource(R.string.manga_date_start), maxLines = 1) }, isError = !validDate(draft.start), modifier = Modifier.weight(1f))
                            OutlinedTextField(value = draft.end, onValueChange = { draft = draft.copy(end = it, preset = "") }, singleLine = true,
                                label = { Text(stringResource(R.string.manga_date_end), maxLines = 1) }, isError = !validDate(draft.end), modifier = Modifier.weight(1f))
                        }
                        if (!draft.datesValid) Text(stringResource(R.string.manga_date_invalid), color = MaterialTheme.colorScheme.error)
                        if (draft.reversed) Text(stringResource(R.string.manga_date_reversed), color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    FilterSection(R.string.manga_pages_filter) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageField(draft.minPages, { draft = draft.copy(minPages = it) }, R.string.manga_pages_min, Modifier.weight(1f))
                            PageField(draft.maxPages, { draft = draft.copy(maxPages = it) }, R.string.manga_pages_max, Modifier.weight(1f))
                            PageField(draft.exactPages, { draft = draft.copy(exactPages = it) }, R.string.manga_pages_exact, Modifier.weight(1f))
                        }
                    }
                }
                item {
                    HorizontalDivider()
                }
                item {
                    FilterSection(R.string.manga_blacklist, hint = R.string.manga_blacklist_hint) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = blockedName, onValueChange = { blockedName = it }, singleLine = true,
                                label = { Text(stringResource(R.string.manga_tag_name)) }, modifier = Modifier.weight(1f))
                            TextButton(enabled = blockedName.isNotBlank(), onClick = { onBlockName(blockedName.trim()); blockedName = "" }) {
                                Text(stringResource(R.string.manga_block_name))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PageField(blockedId, { blockedId = it }, R.string.manga_tag_id, Modifier.weight(1f))
                            TextButton(enabled = (blockedId.toIntOrNull() ?: 0) > 0, onClick = {
                                blockedId.toIntOrNull()?.let(onBlockId)
                                blockedId = ""
                            }) { Text(stringResource(R.string.manga_block_id)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            blockedNames.sorted().forEach { name ->
                                FilterChip(selected = true, onClick = { onUnblockName(name) }, label = { Text(stringResource(R.string.manga_unblock_name, name)) })
                            }
                            blockedIds.sorted().forEach { id ->
                                FilterChip(selected = true, onClick = { onUnblockId(id) }, label = { Text(stringResource(R.string.manga_unblock_id, id)) })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    draft = draft.reset()
                    tagType = "tag"
                    tagName = ""
                }) { Text(stringResource(R.string.manga_reset_filters)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
            }
        }
    }
}

@Composable
private fun FilterSection(title: Int, hint: Int? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun PageField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = { next -> if (next.all { it in '0'..'9' }) onChange(next) },
        label = { Text(stringResource(label), maxLines = 1) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

private fun validDate(value: String): Boolean {
    if (value.isEmpty()) return true
    return try {
        LocalDate.parse(value)
        true
    } catch (e: DateTimeParseException) {
        false
    }
}
