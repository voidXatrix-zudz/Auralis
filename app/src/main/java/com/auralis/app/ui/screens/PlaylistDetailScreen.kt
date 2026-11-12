package com.auralis.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.PlaylistItem
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.GlassPlaylistDetailScreen
import com.auralis.app.ui.theme.LocalThemeStyle

/** One local playlist: play, reorder, remove, rename and delete; mixes local and YouTube songs. */
@Composable
fun PlaylistDetailScreen(
    playlistName: String,
    items: List<PlaylistItem>,
    currentId: String?,
    onPlayIndex: (Int) -> Unit,
    onRemove: (PlaylistItem) -> Unit,
    onMove: (PlaylistItem, Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassPlaylistDetailScreen(playlistName, items, currentId, onPlayIndex, onRemove, onMove, onRename, onDelete, modifier)
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(playlistName) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(playlistName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${items.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPlayIndex(0) }, enabled = items.isNotEmpty()) { Text("Play all") }
            TextButton(onClick = { name = playlistName; renaming = true }) { Text("Rename") }
            TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This playlist is empty. Add songs from a song's menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(items, key = { _, item -> "${item.remote}:${item.entryId}" }) { index, item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlayIndex(index) }.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = if (item.track.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        ArtworkImage(item.track, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                            Text(item.track.title.ifBlank { item.track.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(item.track.artist.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onMove(item, -1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                        IconButton(onClick = { onMove(item, 1) }, enabled = index < items.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                        IconButton(onClick = { onRemove(item) }) { Icon(Icons.Rounded.Close, "Remove from playlist") }
                    }
                }
            }
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("“$playlistName” will be removed. Your songs stay in the library.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmingDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}
