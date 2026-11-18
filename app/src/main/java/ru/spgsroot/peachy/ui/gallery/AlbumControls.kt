package ru.spgsroot.peachy.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.db.AlbumEntity

@Composable
fun AlbumBrowser(albums: List<AlbumEntity>, items: List<GalleryItem>, onOpen: (Long) -> Unit,
    onCreate: (String) -> Unit, onRename: (Long, String) -> Unit, onDelete: (Long) -> Unit) {
    var edit by remember { mutableStateOf<AlbumEntity?>(null) }
    var create by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AlbumEntity?>(null) }
    val counts = remember(items) { items.groupingBy { it.albumId }.eachCount() }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { create = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.album_create))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            item {
                AlbumRow(
                    name = stringResource(R.string.album_unassigned),
                    count = counts[null] ?: 0,
                    onOpen = { onOpen(0) }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            items(albums, key = { it.id }) { album ->
                var menu by remember { mutableStateOf(false) }
                AlbumRow(
                    name = album.name,
                    count = counts[album.id] ?: 0,
                    onOpen = { onOpen(album.id) },
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = { menu = false; edit = album })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete)) }, onClick = { menu = false; deleting = album })
                            }
                        }
                    }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
    if (create || edit != null) AlbumNameDialog(edit?.name.orEmpty(), { create = false; edit = null }) { name ->
        val existing = edit
        if (existing == null) onCreate(name) else onRename(existing.id, name)
        create = false; edit = null
    }
    deleting?.let { album -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(album.name) }, text = { Text(stringResource(R.string.album_delete_explanation)) },
        confirmButton = { TextButton(onClick = { onDelete(album.id); deleting = null }) { Text(stringResource(R.string.menu_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.btn_cancel)) } }) }
}

@Composable
private fun AlbumRow(
    name: String,
    count: Int,
    onOpen: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$name · $count",
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

@Composable
fun AlbumPicker(albums: List<AlbumEntity>, onDismiss: () -> Unit, onMove: (Long?) -> Unit,
    onCreate: (String, (Long) -> Unit) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) AlbumNameDialog("", { creating = false }) { name ->
        onCreate(name) { id -> onMove(id); onDismiss() }; creating = false
    }
    else AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.album_move)) },
        text = { LazyColumn(Modifier.heightIn(max = 350.dp)) {
            item { TextButton(onClick = { onMove(null); onDismiss() }) { Text(stringResource(R.string.album_unassigned)) } }
            items(albums, key = { it.id }) { album -> TextButton(onClick = { onMove(album.id); onDismiss() }) { Text(album.name) } }
            item { TextButton(onClick = { creating = true }) { Text(stringResource(R.string.album_create)) } }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } })
}

@Composable
private fun AlbumNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.album_name)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.album_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } })
}
