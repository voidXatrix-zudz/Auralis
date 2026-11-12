package com.auralis.app.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import com.auralis.app.ui.PlaylistItem
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.theme.GlassTokens

/** Glassmorphic playlist detail: frosted rows with reorder/remove, rename and delete. */
@Composable
fun GlassPlaylistDetailScreen(
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
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(playlistName) }
    var confirmingDelete by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        GlassHeader(playlistName, Modifier.padding(horizontal = 16.dp), "${items.size} songs")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassGhostButton("Play all", { onPlayIndex(0) }, enabled = items.isNotEmpty())
            GlassGhostButton("Rename", { name = playlistName; renaming = true })
            GlassGhostButton("Delete", { confirmingDelete = true })
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This playlist is empty. Add songs from a song's menu.", style = MaterialTheme.typography.bodyMedium, color = GlassTokens.SilverMuted)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> "${item.remote}:${item.entryId}" }) { index, item ->
                    GlassPanel(
                        Modifier.fillMaxWidth(),
                        RoundedCornerShape(GlassTokens.RadiusMd),
                        onClick = { onPlayIndex(index) },
                    ) {
                        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassArtwork(item.track, Modifier.size(52.dp), 16.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                                Text(
                                    item.track.title.ifBlank { item.track.displayName },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (item.track.id == currentId) GlassTokens.Violet else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    item.track.artist.ifBlank { "Unknown artist" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GlassIconAction(Icons.Rounded.ArrowUpward, "Move up", { onMove(item, -1) }, enabled = index > 0, tint = GlassTokens.Silver)
                            GlassIconAction(Icons.Rounded.ArrowDownward, "Move down", { onMove(item, 1) }, enabled = index < items.lastIndex, tint = GlassTokens.Silver)
                            GlassIconAction(Icons.Rounded.Close, "Remove from playlist", { onRemove(item) }, tint = GlassTokens.Silver)
                        }
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
        GlassDialog(
            title = "Delete playlist?",
            message = "“$playlistName” will be removed. Your songs stay in the library.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(); confirmingDelete = false },
            onDismiss = { confirmingDelete = false },
        )
    }
}
