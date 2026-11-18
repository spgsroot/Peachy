package ru.spgsroot.peachy.documents.office.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacyBlock
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacySlide

/**
 * The read-only `.ppt` surface: real extracted text only, in file order.
 *
 * It deliberately has no renderer, no preview tiles, no images, no export and no editing tools.
 * Hidden slides stay in the sequence with a label, tables are shown row by row in cell order and
 * can scroll horizontally instead of truncating, notes keep their own heading, and every piece of
 * text is native Compose text inside a [SelectionContainer] so it can be selected and read.
 */
@Composable
internal fun OfficeLegacyText(
    state: OfficeLegacyTextState,
    onBack: () -> Unit,
) {
    when (state) {
        OfficeLegacyTextState.Loading -> LegacyLoading()
        OfficeLegacyTextState.Empty -> LegacyNotice(
            title = stringResource(R.string.office_legacy_empty_title),
            body = stringResource(R.string.office_legacy_empty_body),
            onBack = onBack,
        )

        OfficeLegacyTextState.Failed -> LegacyNotice(
            title = stringResource(R.string.office_legacy_failed_title),
            body = stringResource(R.string.office_legacy_failed_body),
            onBack = onBack,
        )

        is OfficeLegacyTextState.Ready -> LegacySlides(state.slides)
    }
}

@Composable
private fun LegacyLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.office_legacy_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegacyNotice(title: String, body: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LEGACY_READING_PADDING, vertical = 32.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.documents_back))
        }
    }
}

@Composable
private fun LegacySlides(slides: List<OfficeLegacySlide>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(OFFICE_LEGACY_SLIDES_TAG),
        contentPadding = PaddingValues(
            start = LEGACY_READING_PADDING,
            end = LEGACY_READING_PADDING,
            top = 24.dp,
            bottom = 32.dp,
        ),
    ) {
        item(key = "scope") {
            // The scope statement is selectable text too, and stays out of every slide's selection.
            SelectionContainer {
                Column {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = stringResource(R.string.office_legacy_scope),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.office_legacy_scope_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(slides, key = { index, _ -> index }) { index, slide ->
            Column {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))
                // Each slide keeps its own selection range instead of one document-wide one.
                SelectionContainer { LegacySlide(index = index, slide = slide) }
            }
        }
    }
}

@Composable
private fun LegacySlide(index: Int, slide: OfficeLegacySlide) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.office_legacy_slide, index + 1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            if (slide.hidden) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = stringResource(R.string.office_legacy_hidden),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (slide.title.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = slide.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }

        slide.blocks.forEach { block ->
            when (block) {
                is OfficeLegacyBlock.Paragraph -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is OfficeLegacyBlock.Table -> {
                    if (block.rows.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.office_legacy_table),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.height(6.dp))
                        LegacyTable(rows = block.rows)
                    }
                }
            }
        }

        val notes = slide.notes.filter { it.isNotBlank() }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.office_legacy_notes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Row-major cell order. Cells have a shared fixed width so columns line up; the table scrolls
 * horizontally instead of clipping, and every cell wraps its full text. A table large enough to
 * dominate the frame composes its rows lazily inside a capped viewport; a small one stays eager so
 * its whole selection range is live at once.
 */
@Composable
private fun LegacyTable(rows: List<List<String>>) {
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        if (rows.size <= EAGER_TABLE_ROWS && rows.sumOf { it.size } <= EAGER_TABLE_CELLS) {
            Column {
                rows.forEach { cells -> LegacyTableRow(cells) }
            }
        } else {
            LazyColumn(Modifier.heightIn(max = LAZY_TABLE_VIEWPORT)) {
                items(rows.size) { index -> LegacyTableRow(rows[index]) }
            }
        }
    }
}

@Composable
private fun LegacyTableRow(cells: List<String>) {
    Row {
        cells.forEach { cell ->
            Text(
                // A written non-breaking space keeps an empty cell's row height; it adds no text
                // and no substitute content.
                text = cell.ifBlank { "\u00A0" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true,
                modifier = Modifier
                    .width(LEGACY_TABLE_CELL_WIDTH)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A single shared reading measure: 24dp gutters on every slide and notice. */
private val LEGACY_READING_PADDING = 24.dp

/** Test handle for the lazily composed slide list. */
internal const val OFFICE_LEGACY_SLIDES_TAG = "office_legacy_slides"

private val LEGACY_TABLE_CELL_WIDTH = 150.dp

/** Above either bound a table composes lazily, so 16k cells cannot all inflate at once. */
private const val EAGER_TABLE_ROWS = 40

private const val EAGER_TABLE_CELLS = 600

private val LAZY_TABLE_VIEWPORT = 420.dp
