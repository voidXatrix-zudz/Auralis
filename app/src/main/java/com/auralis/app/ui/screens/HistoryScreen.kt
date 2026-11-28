package com.auralis.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.Track
import com.auralis.app.data.youtube.needsMetadataFill
import com.auralis.app.ui.HistoryEntry
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.GlassHistoryScreen
import com.auralis.app.ui.glass.SettingsLiquidGlass
import com.auralis.app.ui.metadata.MetadataFillEffect
import com.auralis.app.ui.metadata.MetadataSkeleton
import com.auralis.app.ui.metadata.displayTitle
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * One merged history: local library songs and streamed YouTube songs, newest first. Only plays of
 * ten seconds or more are stored, and tapping a row queues the rest of the list.
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    currentId: String?,
    onPlay: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    settledMetadata: Set<String> = emptySet(),
    onMetadataVisible: (List<Track>) -> Unit = {},
    onMetadataHidden: (List<String>) -> Unit = {},
    playerBackground: PlayerBackground = PlayerBackground.NONE,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    // Retries follow the rows that are actually on this screen and stop when it is left or the app
    // is backgrounded, so no polling continues for a song the user can no longer see.
    val pendingMetadata = remember(entries) {
        entries.map { it.track }.filter { it.needsMetadataFill }
    }
    MetadataFillEffect(pendingMetadata, onMetadataVisible, onMetadataHidden)
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        // Same self-recorded liquid-glass layer Settings uses, so the rows blur/refract a real
        // backdrop (and the chosen player wallpaper) instead of a flat black canvas.
        SettingsLiquidGlass(playerBackground, modifier) {
            GlassHistoryScreen(entries, currentId, onPlay, onClear, settledMetadata, onBack = onBack)
        }
        return
    }
    var confirmingClear by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text("History", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { confirmingClear = true }, enabled = entries.isNotEmpty()) {
                Text("Clear history")
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Songs you listen to for 10 seconds or more appear here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { "${it.track.id}:${it.playedAt}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(entry) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(entry.track, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                            if (entry.track.needsMetadataFill && entry.track.id !in settledMetadata) {
                                MetadataSkeleton(widthFraction = 0.62f, height = 14.dp)
                                Spacer(Modifier.height(6.dp))
                                MetadataSkeleton(widthFraction = 0.42f, height = 11.dp, alpha = 0.12f)
                            } else {
                                Text(
                                    entry.track.displayTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (entry.track.id == currentId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    entry.track.artist.ifBlank { "Unknown artist" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            timeAgo(entry.playedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes every local and YouTube song from your History. Your songs and playlists stay.") },
            confirmButton = {
                TextButton(onClick = { confirmingClear = false; onClear() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
            },
        )
    }
}

/** Compact relative time, e.g. "just now", "5m ago", "3h ago", "2d ago". */
internal fun timeAgo(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - epochMs).coerceAtLeast(0L)
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1_440}d ago"
    }
}
