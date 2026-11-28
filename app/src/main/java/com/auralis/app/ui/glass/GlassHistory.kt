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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.youtube.needsMetadataFill
import com.auralis.app.ui.HistoryEntry
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.metadata.MetadataSkeleton
import com.auralis.app.ui.metadata.displayTitle
import com.auralis.app.ui.motion.staggeredEntrance
import com.auralis.app.ui.screens.timeAgo
import com.auralis.app.ui.theme.GlassTokens

/**
 * Glassmorphic History screen: frosted rows matching the Library/Home styling.
 *
 * This screen owns its header and lets rows dissolve softly as they scroll under it.
 */
@Composable
fun GlassHistoryScreen(
    entries: List<HistoryEntry>,
    currentId: String?,
    onPlay: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    settledMetadata: Set<String>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    var confirmingClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fadePx = with(LocalDensity.current) { 20.dp.toPx() }
    var introDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_000); introDone = true }
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, blur = true)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassHeader("History", Modifier.weight(1f), "${entries.size} songs")
            GlassGhostButton("Clear history", { confirmingClear = true }, enabled = entries.isNotEmpty(), blur = true)
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(bottom = LocalDockBottomInset.current),
                contentAlignment = Alignment.Center,
            ) {
                GlassEmptyState(
                    title = "No history yet",
                    body = "Songs you listen to for 10 seconds or more appear here",
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fadeTopEdge(20.dp) {
                        if (listState.firstVisibleItemIndex > 0) 1f
                        else (listState.firstVisibleItemScrollOffset / fadePx)
                    },
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalDockBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { _, item -> "${item.track.id}:${item.playedAt}" }) { index, entry ->
                    GlassPanel(
                        Modifier.fillMaxWidth().animateItem().staggeredEntrance(index, enabled = !introDone),
                        RoundedCornerShape(GlassTokens.RadiusMd),
                        onClick = { onPlay(entry) },
                    ) {
                        Row(
                            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassArtwork(entry.track, Modifier.size(56.dp), 16.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
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
                                        color = if (entry.track.id == currentId) GlassTokens.Violet
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
    if (confirmingClear) {
        GlassMaterialAlertDialog(
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
