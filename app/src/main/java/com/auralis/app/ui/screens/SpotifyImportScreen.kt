package com.auralis.app.ui.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.SpotifyImportEntity
import com.auralis.app.data.db.SpotifyImportItemEntity
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.SpotifyCandidate
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.glass.GlassSpotifyImportScreen
import com.auralis.app.ui.theme.LocalThemeStyle

/** Progress, summary and the "Needs review" list for one Spotify CSV import. */
@Composable
fun SpotifyImportScreen(
    importRow: SpotifyImportEntity?,
    reviewItems: List<SpotifyImportItemEntity>,
    candidatesOf: (SpotifyImportItemEntity) -> List<SpotifyCandidate>,
    onPick: (SpotifyImportItemEntity, SpotifyCandidate) -> Unit,
    onSkip: (SpotifyImportItemEntity) -> Unit,
    onRetry: (SpotifyImportItemEntity) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassSpotifyImportScreen(importRow, reviewItems, candidatesOf, onPick, onSkip, onRetry, onOpenPlaylist, modifier)
        return
    }
    if (importRow == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This import is no longer available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val running = importRow.state == "RUNNING"
    val total = importRow.total.coerceAtLeast(0)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Importing “${importRow.fileName}”", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running) {
                LinearProgressIndicator(
                    progress = { if (total > 0) importRow.processed.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Matching ${importRow.processed} of $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${importRow.matched} matched · ${importRow.review} to review · ${importRow.failed} failed", style = MaterialTheme.typography.bodyMedium)
            }
            importRow.playlistId?.let { id ->
                TextButton(onClick = { onOpenPlaylist(id) }) { Text("Open playlist") }
            }
        }
        if (reviewItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (running) "Reviewing as songs match…" else "Nothing left to review.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(reviewItems, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val candidates = candidatesOf(item)
                        if (candidates.isEmpty()) {
                            Text("No candidates found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            candidates.forEach { candidate ->
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ArtworkImage(track = null, artUri = candidate.thumbnailUrl, modifier = Modifier.size(40.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${candidate.artist} · ${candidateScore(candidate.score)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = { onPick(item, candidate) }) { Text("Use") }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRetry(item) }) { Text("Retry") }
                            TextButton(onClick = { onSkip(item) }) { Text("Skip") }
                        }
                    }
                }
            }
        }
    }
}

internal fun candidateScore(score: Double): String = "${(score * 100).toInt()}%"
