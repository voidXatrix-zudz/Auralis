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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.YouTubeRecentEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.screens.timeAgo
import com.auralis.app.ui.theme.GlassTokens

/** Glassmorphic History screen: frosted rows matching the Library/Home styling. */
@Composable
fun GlassHistoryScreen(
    entries: List<YouTubeRecentEntity>,
    currentId: String?,
    onPlay: (YouTubeRecentEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassHeader("History", Modifier.weight(1f), "${entries.size} songs")
            GlassGhostButton("Clear history", onClear, enabled = entries.isNotEmpty())
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Songs you play for 30 seconds or more show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.SilverMuted,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.videoId }) { entry ->
                    val track = entry.toDomain()
                    GlassPanel(
                        Modifier.fillMaxWidth(),
                        RoundedCornerShape(GlassTokens.RadiusMd),
                        onClick = { onPlay(entry) },
                    ) {
                        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassArtwork(track, Modifier.size(56.dp), 16.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                Text(
                                    entry.title.ifBlank { "Unknown title" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if ("yt:${entry.videoId}" == currentId) GlassTokens.Violet else MaterialTheme.colorScheme.onSurface,
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
                                Modifier.padding(end = 14.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlassTokens.SilverMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
