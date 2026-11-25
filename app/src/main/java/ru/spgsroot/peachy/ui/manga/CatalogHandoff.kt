package ru.spgsroot.peachy.ui.manga

import androidx.navigation.NavController
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode

const val MANGA_HUB_ROUTE = "manga"
const val TAG_FILTERS_KEY = "tagFilters"

/** Give each filtered catalog its own entry, so Back preserves both the source and earlier searches. */
fun NavController.searchCatalog(filters: List<TagFilter>) {
    navigate(MANGA_HUB_ROUTE)
    checkNotNull(currentBackStackEntry).savedStateHandle[TAG_FILTERS_KEY] = ArrayList(filters.map(::encodeFilter))
}

internal fun encodeFilter(filter: TagFilter): String = "${filter.mode.name}|${filter.type}|${filter.name}"

internal fun decodeFilters(raw: List<String>): List<TagFilter> = raw.mapNotNull { item ->
    val parts = item.split('|', limit = 3)
    val mode = TagMode.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return@mapNotNull null
    if (parts.size < 3 || parts[1].isEmpty() || parts[2].isEmpty()) null else TagFilter(parts[1], parts[2], mode)
}
