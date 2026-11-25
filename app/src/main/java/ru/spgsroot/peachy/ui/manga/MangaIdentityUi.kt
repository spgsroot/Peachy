package ru.spgsroot.peachy.ui.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.manga.EditionMetadata
import ru.spgsroot.peachy.manga.EditionReadStatus
import ru.spgsroot.peachy.manga.MangaIdentityMatcher
import ru.spgsroot.peachy.manga.MangaIdentityProjector
import ru.spgsroot.peachy.manga.MangaIdentitySnapshot
import ru.spgsroot.peachy.manga.MatchDecision
import ru.spgsroot.peachy.manga.ProjectedGroup
import ru.spgsroot.peachy.manga.TagNames
import ru.spgsroot.peachy.manga.UnitKind
import ru.spgsroot.peachy.manga.WorkChapterProgress
import ru.spgsroot.peachy.manga.WorkUnitSummary

@Composable
internal fun ReadStatusPill(
    status: EditionReadStatus,
    legacyLastPage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val label = when {
        status is EditionReadStatus.ReadOwn -> stringResource(R.string.manga_identity_read_own)
        status is EditionReadStatus.ReadInOtherTranslation -> stringResource(R.string.manga_identity_read_inherited)
        legacyLastPage -> stringResource(R.string.manga_identity_legacy_last_page)
        else -> return
    }
    val containerColor = when {
        status is EditionReadStatus.ReadOwn -> MaterialTheme.colorScheme.primaryContainer
        status is EditionReadStatus.ReadInOtherTranslation -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        status is EditionReadStatus.ReadOwn -> MaterialTheme.colorScheme.onPrimaryContainer
        status is EditionReadStatus.ReadInOtherTranslation -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun WorkProgressLine(
    progress: WorkChapterProgress,
    modifier: Modifier = Modifier
) {
    if (progress.knownChapters <= 0) return
    Text(
        text = stringResource(
            R.string.manga_identity_known_chapters,
            progress.readChapters,
            progress.knownChapters
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
internal fun UnitLabelText(label: String, kind: UnitKind): String = when (kind) {
    UnitKind.Chapter -> stringResource(R.string.manga_identity_unit_prefix_chapter, label)
    UnitKind.Collection -> stringResource(R.string.manga_identity_unit_prefix_collection, label)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ProjectedGroupFooter(
    group: ProjectedGroup<T>,
    idOf: (T) -> Int,
    titleOf: (T) -> String,
    languageOf: (T) -> String?,
    snapshot: MangaIdentitySnapshot,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenEdition: (Int) -> Unit,
    onConfirmSame: (Int, Int) -> Unit,
    onReject: (Int, Int) -> Unit,
    onRemoveEdition: ((Int) -> Unit)? = null,
    legacyLastPageOf: (Int) -> Boolean = { false }
) {
    val primaryId = idOf(group.primary)
    val primaryStatus = MangaIdentityProjector.readStatus(primaryId, snapshot)
    val hasExtraVersions = group.members.size > 1
    val hasUnitBadge = group.isConfirmed && group.unitLabel != null && group.unitKind != null

    if (primaryStatus !is EditionReadStatus.Unread || legacyLastPageOf(primaryId) || hasUnitBadge || hasExtraVersions) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ReadStatusPill(primaryStatus, legacyLastPage = legacyLastPageOf(primaryId))
                if (hasUnitBadge) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = UnitLabelText(group.unitLabel!!, group.unitKind!!),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            group.workProgress?.takeIf { it.knownChapters > 0 }?.let { progress ->
                WorkProgressLine(progress)
            }
            if (hasExtraVersions) {
                Surface(
                    onClick = onToggleExpand,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.manga_identity_versions_count, group.members.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        Text(
                            text = if (group.isConfirmed) {
                                stringResource(R.string.manga_identity_confirmed_badge)
                            } else {
                                stringResource(R.string.manga_identity_autogroup_badge)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (expanded) R.string.manga_identity_versions_collapse
                                else R.string.manga_identity_versions_expand
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded && hasExtraVersions) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        group.members.filter { idOf(it) != primaryId }.forEach { member ->
                            val memberId = idOf(member)
                            val memberStatus = MangaIdentityProjector.readStatus(memberId, snapshot)
                            val langDisplay = languageOf(member)
                                ?.let { TagNames.display("language", it, tagLanguage()) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenEdition(memberId) }
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = listOfNotNull(langDisplay, titleOf(member)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ReadStatusPill(memberStatus, legacyLastPage = legacyLastPageOf(memberId))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!group.isConfirmed) {
                                        TextButton(
                                            onClick = { onConfirmSame(primaryId, memberId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.manga_identity_action_confirm_same),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        TextButton(
                                            onClick = { onReject(primaryId, memberId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.manga_identity_action_reject),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    if (onRemoveEdition != null) {
                                        TextButton(
                                            onClick = { onRemoveEdition(memberId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.manga_remove),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GalleryIdentitySection(
    galleryId: Int,
    liveMetadata: EditionMetadata,
    snapshot: MangaIdentitySnapshot,
    historyByGallery: Map<Int, ReadHistoryEntity>,
    onOpenGallery: (Int) -> Unit,
    onSetReadMark: (Boolean) -> Unit,
    onConfirmSameChapter: (Int) -> Unit,
    onLinkAsTranslationOfUnit: (workId: String, unitId: String) -> Unit,
    onLinkAsAnotherChapter: (
        workId: String?,
        anchorGalleryId: Int?,
        workTitle: String?,
        unitLabel: String,
        sortOrder: Double,
        kind: UnitKind
    ) -> Unit,
    onUnlink: () -> Unit,
    onRejectSuggestion: (Int) -> Unit
) {
    val effectiveSnapshot = remember(snapshot, liveMetadata) {
        if (galleryId in snapshot.editions) snapshot
        else snapshot.copy(editions = snapshot.editions + (galleryId to liveMetadata))
    }
    val status = MangaIdentityProjector.readStatus(galleryId, effectiveSnapshot)
    val ownRead = status is EditionReadStatus.ReadOwn
    val ownHistory = historyByGallery[galleryId]
    val legacyLastPage = ownHistory != null &&
        MangaIdentityProjector.isLegacyLastPageOnly(ownHistory.page, ownHistory.pageCount, status)
    val link = effectiveSnapshot.links[galleryId]
    val currentWork = link?.let { effectiveSnapshot.works[it.workId] }
    val currentUnit = link?.let { effectiveSnapshot.units[it.unitId] }
    val progress = link?.let { MangaIdentityProjector.workProgress(it.workId, effectiveSnapshot) }
    val confirmedSiblings = remember(galleryId, effectiveSnapshot) {
        MangaIdentityProjector.confirmedTranslations(galleryId, effectiveSnapshot)
    }
    val workUnits = remember(link?.workId, effectiveSnapshot) {
        link?.workId?.let { MangaIdentityProjector.workUnits(it, effectiveSnapshot) }.orEmpty()
    }
    val suggestedPeers = remember(galleryId, effectiveSnapshot) {
        MangaIdentityProjector.suggestedPeers(galleryId, effectiveSnapshot)
    }

    var showSameChapterDialog by remember { mutableStateOf(false) }
    var chapterDialogAnchorId by remember { mutableStateOf<Int?>(null) }
    var showAnotherChapterDialog by remember { mutableStateOf(false) }

    if (showSameChapterDialog) {
        LinkTranslationDialog(
            galleryId = galleryId,
            snapshot = effectiveSnapshot,
            onDismiss = { showSameChapterDialog = false },
            onPickUnit = { workId, unitId ->
                showSameChapterDialog = false
                onLinkAsTranslationOfUnit(workId, unitId)
            },
            onPickGallery = { targetId ->
                showSameChapterDialog = false
                onConfirmSameChapter(targetId)
            }
        )
    }

    if (showAnotherChapterDialog) {
        LinkChapterDialog(
            galleryId = galleryId,
            liveMetadata = liveMetadata,
            snapshot = effectiveSnapshot,
            initialWorkId = link?.workId,
            initialAnchorGalleryId = chapterDialogAnchorId,
            onDismiss = {
                showAnotherChapterDialog = false
                chapterDialogAnchorId = null
            },
            onSave = { workId, anchorId, workTitle, label, order, kind ->
                showAnotherChapterDialog = false
                chapterDialogAnchorId = null
                onLinkAsAnotherChapter(workId, anchorId, workTitle, label, order, kind)
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.manga_identity_section_title),
                style = MaterialTheme.typography.titleMedium
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReadStatusPill(status = status, legacyLastPage = legacyLastPage)
                OutlinedButton(
                    onClick = { onSetReadMark(!ownRead) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(
                            if (ownRead) R.string.manga_identity_unmark_read
                            else R.string.manga_identity_mark_read
                        )
                    )
                }
            }

            if (currentWork != null && currentUnit != null) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = currentWork.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = UnitLabelText(currentUnit.label, currentUnit.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    progress?.let { WorkProgressLine(it) }

                    if (confirmedSiblings.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.manga_identity_confirmed_translations),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        confirmedSiblings.forEach { siblingId ->
                            val siblingMeta = effectiveSnapshot.editions[siblingId]
                            val siblingStatus = MangaIdentityProjector.readStatus(siblingId, effectiveSnapshot)
                            val siblingHistory = historyByGallery[siblingId]
                            EditionRowItem(
                                galleryId = siblingId,
                                meta = siblingMeta,
                                status = siblingStatus,
                                history = siblingHistory,
                                onClick = { onOpenGallery(siblingId) }
                            )
                        }
                    }

                    val chapters = workUnits.filter { it.unit.kind == UnitKind.Chapter }
                    val collections = workUnits.filter { it.unit.kind == UnitKind.Collection }
                    if (chapters.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.manga_identity_work_chapters),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        chapters.forEach { summary ->
                            WorkUnitRow(
                                summary = summary,
                                isCurrent = summary.unit.unitId == currentUnit.unitId,
                                onOpenGallery = onOpenGallery
                            )
                        }
                    }
                    if (collections.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.manga_identity_work_collections),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        collections.forEach { summary ->
                            WorkUnitRow(
                                summary = summary,
                                isCurrent = summary.unit.unitId == currentUnit.unitId,
                                onOpenGallery = onOpenGallery
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onUnlink,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.manga_identity_action_unlink))
                    }
                }
            }

            HorizontalDivider()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showSameChapterDialog = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.manga_identity_action_same_chapter))
                }
                OutlinedButton(
                    onClick = {
                        chapterDialogAnchorId = null
                        showAnotherChapterDialog = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.manga_identity_action_another_chapter))
                }
            }

            if (suggestedPeers.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.manga_identity_suggested_peers),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                suggestedPeers.forEach { peer ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val lang = peer.metadata.language?.let { TagNames.display("language", it, tagLanguage()) }
                            val subtitle = listOfNotNull(
                                lang,
                                peer.metadata.numPages.takeIf { it > 0 }?.let { stringResource(R.string.manga_page_count, it) },
                                if (peer.decision == MatchDecision.Preview) {
                                    stringResource(R.string.manga_identity_autogroup_badge)
                                } else null
                            ).joinToString(" · ")
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenGallery(peer.metadata.galleryId) }
                            ) {
                                Text(
                                    text = peer.metadata.displayTitle.ifBlank { "#${peer.metadata.galleryId}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onConfirmSameChapter(peer.metadata.galleryId) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.manga_identity_action_confirm_same))
                                }
                                OutlinedButton(
                                    onClick = {
                                        chapterDialogAnchorId = peer.metadata.galleryId
                                        showAnotherChapterDialog = true
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.manga_identity_action_confirm_chapter))
                                }
                                TextButton(
                                    onClick = { onRejectSuggestion(peer.metadata.galleryId) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.manga_identity_action_reject))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditionRowItem(
    galleryId: Int,
    meta: EditionMetadata?,
    status: EditionReadStatus,
    history: ReadHistoryEntity?,
    onClick: () -> Unit
) {
    val lang = meta?.language?.let { TagNames.display("language", it, tagLanguage()) }
    val pageText = history?.takeIf { it.page >= 0 && it.pageCount > 0 }?.let {
        stringResource(R.string.manga_identity_history_page, it.page + 1, it.pageCount)
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = listOfNotNull(lang, meta?.displayTitle?.takeIf { it.isNotBlank() } ?: "#$galleryId")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReadStatusPill(status)
                if (pageText != null) {
                    Text(
                        text = pageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkUnitRow(
    summary: WorkUnitSummary,
    isCurrent: Boolean,
    onOpenGallery: (Int) -> Unit
) {
    val targetId = summary.galleryIds.firstOrNull()
    Surface(
        onClick = { if (targetId != null && !isCurrent) onOpenGallery(targetId) },
        enabled = targetId != null && !isCurrent,
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = UnitLabelText(summary.unit.label, summary.unit.kind),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary.isRead) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.manga_identity_read_own),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LinkTranslationDialog(
    galleryId: Int,
    snapshot: MangaIdentitySnapshot,
    onDismiss: () -> Unit,
    onPickUnit: (workId: String, unitId: String) -> Unit,
    onPickGallery: (Int) -> Unit
) {
    val existingUnits = remember(snapshot) {
        snapshot.units.values
            .sortedWith(compareBy({ snapshot.works[it.workId]?.title.orEmpty() }, { it.sortOrder }))
    }
    val candidateGalleries = remember(snapshot, galleryId) {
        snapshot.editions.values
            .filter { it.galleryId != galleryId }
            .sortedByDescending { it.updatedAt }
            .take(25)
    }
    var selectedUnitId by remember { mutableStateOf<String?>(null) }
    var selectedGalleryId by remember { mutableStateOf<Int?>(null) }
    var manualGalleryId by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manga_identity_action_same_chapter)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (existingUnits.isNotEmpty()) {
                    Text(
                        stringResource(R.string.manga_identity_pick_unit),
                        style = MaterialTheme.typography.labelLarge
                    )
                    existingUnits.forEach { unit ->
                        val workTitle = snapshot.works[unit.workId]?.title.orEmpty()
                        val selected = selectedUnitId == unit.unitId
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedUnitId = unit.unitId
                                selectedGalleryId = null
                                manualGalleryId = ""
                                invalid = false
                            },
                            label = {
                                Text(
                                    "$workTitle · ${UnitLabelText(unit.label, unit.kind)}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
                if (candidateGalleries.isNotEmpty()) {
                    Text(
                        stringResource(R.string.manga_identity_pick_gallery),
                        style = MaterialTheme.typography.labelLarge
                    )
                    candidateGalleries.forEach { candidate ->
                        val selected = selectedGalleryId == candidate.galleryId
                        val lang = candidate.language?.let { TagNames.display("language", it, tagLanguage()) }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedGalleryId = candidate.galleryId
                                selectedUnitId = null
                                manualGalleryId = candidate.galleryId.toString()
                                invalid = false
                            },
                            label = {
                                Text(
                                    listOfNotNull(lang, candidate.displayTitle.ifBlank { "#${candidate.galleryId}" })
                                        .joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                } else if (existingUnits.isEmpty()) {
                    Text(
                        stringResource(R.string.manga_identity_no_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = manualGalleryId,
                    onValueChange = {
                        manualGalleryId = it.filter(Char::isDigit)
                        selectedUnitId = null
                        selectedGalleryId = manualGalleryId.toIntOrNull()
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.manga_identity_gallery_id_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (invalid) {
                    Text(
                        stringResource(R.string.manga_identity_invalid_input),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val unit = selectedUnitId?.let { snapshot.units[it] }
                val targetId = selectedGalleryId ?: manualGalleryId.toIntOrNull()
                when {
                    unit != null -> onPickUnit(unit.workId, unit.unitId)
                    targetId != null && targetId > 0 && targetId != galleryId -> onPickGallery(targetId)
                    else -> invalid = true
                }
            }) {
                Text(stringResource(R.string.manga_identity_save_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.manga_identity_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChapterDialog(
    galleryId: Int,
    liveMetadata: EditionMetadata,
    snapshot: MangaIdentitySnapshot,
    initialWorkId: String?,
    initialAnchorGalleryId: Int?,
    onDismiss: () -> Unit,
    onSave: (
        workId: String?,
        anchorGalleryId: Int?,
        workTitle: String?,
        unitLabel: String,
        sortOrder: Double,
        kind: UnitKind
    ) -> Unit
) {
    val works = remember(snapshot) { snapshot.works.values.sortedByDescending { it.updatedAt } }
    val candidates = remember(snapshot, galleryId) {
        snapshot.editions.values
            .filter { it.galleryId != galleryId }
            .sortedByDescending { it.updatedAt }
            .take(25)
    }
    val initialAnchorWorkId = initialAnchorGalleryId?.let { snapshot.links[it]?.workId }
    val suggested = remember(liveMetadata) {
        MangaIdentityMatcher.suggestChapterLabelAndOrder(liveMetadata, fallbackLabel = "2", fallbackOrder = 2.0)
    }
    var selectedWorkId by remember {
        mutableStateOf(initialAnchorWorkId ?: initialWorkId ?: works.firstOrNull()?.workId)
    }
    var anchorGalleryIdText by remember {
        mutableStateOf(initialAnchorGalleryId?.toString().orEmpty())
    }
    var workTitleText by remember {
        mutableStateOf(
            initialAnchorGalleryId?.let { snapshot.editions[it]?.displayTitle }
                ?: liveMetadata.displayTitle
        )
    }
    var unitLabel by remember { mutableStateOf(suggested.first) }
    var sortOrderText by remember {
        val raw = suggested.second
        mutableStateOf(if (raw % 1.0 == 0.0) raw.toInt().toString() else raw.toString())
    }
    var kind by remember { mutableStateOf(UnitKind.Chapter) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manga_identity_action_another_chapter)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.manga_identity_pick_work), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    works.forEach { work ->
                        FilterChip(
                            selected = selectedWorkId == work.workId,
                            onClick = {
                                selectedWorkId = work.workId
                                invalid = false
                            },
                            label = { Text(work.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    FilterChip(
                        selected = selectedWorkId == null,
                        onClick = {
                            selectedWorkId = null
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.manga_identity_new_work_with_gallery)) }
                    )
                }

                if (selectedWorkId == null) {
                    if (candidates.isNotEmpty()) {
                        Text(
                            stringResource(R.string.manga_identity_pick_gallery),
                            style = MaterialTheme.typography.labelLarge
                        )
                        candidates.forEach { candidate ->
                            FilterChip(
                                selected = anchorGalleryIdText == candidate.galleryId.toString(),
                                onClick = {
                                    anchorGalleryIdText = candidate.galleryId.toString()
                                    if (workTitleText.isBlank()) workTitleText = candidate.displayTitle
                                    invalid = false
                                },
                                label = {
                                    Text(
                                        candidate.displayTitle.ifBlank { "#${candidate.galleryId}" },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = anchorGalleryIdText,
                        onValueChange = {
                            anchorGalleryIdText = it.filter(Char::isDigit)
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.manga_identity_gallery_id_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = workTitleText,
                        onValueChange = {
                            workTitleText = it
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.manga_identity_work_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == UnitKind.Chapter,
                        onClick = { kind = UnitKind.Chapter },
                        label = { Text(stringResource(R.string.manga_identity_kind_chapter)) }
                    )
                    FilterChip(
                        selected = kind == UnitKind.Collection,
                        onClick = { kind = UnitKind.Collection },
                        label = { Text(stringResource(R.string.manga_identity_kind_collection)) }
                    )
                }

                OutlinedTextField(
                    value = unitLabel,
                    onValueChange = {
                        unitLabel = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.manga_identity_chapter_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sortOrderText,
                    onValueChange = {
                        sortOrderText = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.manga_identity_sort_order_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (invalid) {
                    Text(
                        stringResource(R.string.manga_identity_invalid_input),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedOrder = sortOrderText.trim().replace(',', '.').toDoubleOrNull()
                val cleanLabel = unitLabel.trim()
                val anchorId = anchorGalleryIdText.toIntOrNull()
                val validTarget = selectedWorkId != null || (anchorId != null && anchorId > 0 && anchorId != galleryId)
                if (!validTarget || cleanLabel.isEmpty() || parsedOrder == null || !parsedOrder.isFinite()) {
                    invalid = true
                } else {
                    onSave(
                        selectedWorkId,
                        if (selectedWorkId == null) anchorId else null,
                        workTitleText.trim().takeIf { it.isNotEmpty() },
                        cleanLabel,
                        parsedOrder,
                        kind
                    )
                }
            }) {
                Text(stringResource(R.string.manga_identity_save_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.manga_identity_cancel)) }
        }
    )
}
