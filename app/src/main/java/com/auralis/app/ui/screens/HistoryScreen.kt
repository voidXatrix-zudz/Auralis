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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.YouTubeRecentEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.GlassHistoryScreen
import com.auralis.app.ui.theme.LocalThemeStyle

/** Play history of streamed songs: last played time and play count, with a clear-all button. */
@Composable
fun HistoryScreen(
    entries: List<YouTubeRecentEntity>,
    currentId: String?,
    onPlay: (YouTubeRecentEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassHistoryScreen(entries, currentId, onPlay, onClear, modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("Clear history") }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Songs you play for 30 seconds or more show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.videoId }) { entry ->
                    val track = entry.toDomain()
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(entry) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(track, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                            Text(
                                entry.title.ifBlank { "Unknown title" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                color = if ("yt:${entry.videoId}" == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                entry.artist.ifBlank { "Unknown artist" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${timeAgo(entry.playedAt)} · ${entry.playCount}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
