package com.auralis.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.youtube.YtPlaylist
import com.auralis.app.data.youtube.normalizePlaylistBrowseId
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.glass.GlassMaterialAlertDialog

/**
 * One selectable playlist in the "Add to playlist" dialog. [localPlaylistId] is set for a playlist
 * the app owns in Room; [accountPlaylist] is set for a YouTube Music playlist that has no local
 * mirror. Exactly one of the two is non-null.
 */
data class PlaylistPickRow(
    val key: String,
    val name: String,
    val origin: String,
    val localPlaylistId: Long?,
    val accountPlaylist: YtPlaylist?,
)

/**
 * Builds the picker list: local playlists first, then the signed-in account's playlists. A local
 * playlist that is already mirrored to the account (`remoteId`) is shown once, as the local row,
 * because adding through it writes Room and the account in one go. Account rows are de-duplicated
 * by their normalised browse id so a playlist can never appear twice.
 */
fun playlistPickRows(
    localPlaylists: List<PlaylistEntity>,
    accountPlaylists: List<YtPlaylist>,
): List<PlaylistPickRow> {
    val mirroredIds = localPlaylists
        .mapNotNull { it.remoteId?.takeIf(String::isNotBlank) }
        .map(::normalizePlaylistBrowseId)
        .toSet()

    val localRows = localPlaylists
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { playlist ->
            PlaylistPickRow(
                key = "local-${playlist.id}",
                name = playlist.name,
                origin = if (playlist.remoteId.isNullOrBlank()) {
                    "On this device"
                } else {
                    "Synced to YouTube Music"
                },
                localPlaylistId = playlist.id,
                accountPlaylist = null,
            )
        }

    val accountRows = accountPlaylists
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        .filter { normalizePlaylistBrowseId(it.id) !in mirroredIds }
        .distinctBy { normalizePlaylistBrowseId(it.id) }
        .map { playlist ->
            PlaylistPickRow(
                key = "yt-${playlist.id}",
                name = playlist.title,
                origin = if (playlist.trackCount > 0) {
                    "YouTube Music · ${playlist.trackCount} songs"
                } else {
                    "YouTube Music"
                },
                localPlaylistId = null,
                accountPlaylist = playlist,
            )
        }

    return localRows + accountRows
}

/**
 * "Add to playlist" dialog for the currently playing song. Local playlists accept any song; a
 * YouTube Music account playlist can only accept a streamed song, so those rows are disabled for a
 * local file and the dialog says why. Picking a row adds the song and closes the dialog, which
 * makes a double tap impossible.
 */
@Composable
fun PlaylistPickerDialog(
    track: Track,
    localPlaylists: List<PlaylistEntity>,
    accountPlaylists: List<YtPlaylist>,
    onPickLocal: (Long) -> Unit,
    onPickAccount: (YtPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(localPlaylists, accountPlaylists) {
        playlistPickRows(localPlaylists, accountPlaylists)
    }
    val hasAccountRows = rows.any { it.accountPlaylist != null }

    GlassMaterialAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (rows.isEmpty()) {
                    Text(
                        text = "No playlists yet. Create one in the Library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        rows.forEach { row ->
                            val accountOnly = row.localPlaylistId == null
                            PlaylistPickRowItem(
                                row = row,
                                enabled = !accountOnly || track.isRemote,
                                onClick = {
                                    when {
                                        row.localPlaylistId != null -> onPickLocal(row.localPlaylistId)
                                        row.accountPlaylist != null -> onPickAccount(row.accountPlaylist)
                                    }
                                    onDismiss()
                                },
                            )
                        }
                    }
                    if (!track.isRemote && hasAccountRows) {
                        Text(
                            text = "Account playlists accept YouTube songs only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun PlaylistPickRowItem(
    row: PlaylistPickRow,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.accountPlaylist != null) Icons.Rounded.LibraryMusic else Icons.Rounded.QueueMusic,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Text(
                text = row.origin,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
            )
        }
        if (!enabled) {
            Text(
                text = "YouTube only",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}