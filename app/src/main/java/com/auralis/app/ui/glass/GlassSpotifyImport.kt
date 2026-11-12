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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.db.SpotifyImportEntity
import com.auralis.app.data.db.SpotifyImportItemEntity
import com.auralis.app.ui.SpotifyCandidate
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.screens.candidateScore
import com.auralis.app.ui.theme.GlassTokens

/** Glassmorphic Spotify import screen: progress, summary and the review list. */
@Composable
fun GlassSpotifyImportScreen(
    importRow: SpotifyImportEntity?,
    reviewItems: List<SpotifyImportItemEntity>,
    candidatesOf: (SpotifyImportItemEntity) -> List<SpotifyCandidate>,
    onPick: (SpotifyImportItemEntity, SpotifyCandidate) -> Unit,
    onSkip: (SpotifyImportItemEntity) -> Unit,
    onRetry: (SpotifyImportItemEntity) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (importRow == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This import is no longer available.", style = MaterialTheme.typography.bodyMedium, color = GlassTokens.SilverMuted)
        }
        return
    }
    val running = importRow.state == "RUNNING"
    val total = importRow.total.coerceAtLeast(0)
    Column(modifier.fillMaxSize()) {
        GlassHeader(importRow.fileName.removeSuffix(".csv"), Modifier.padding(horizontal = 16.dp), importRow.fileName)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (running) {
                LinearProgressIndicator(
                    progress = { if (total > 0) importRow.processed.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassTokens.Violet,
                )
                Text("Matching ${importRow.processed} of $total", style = MaterialTheme.typography.bodySmall, color = GlassTokens.SilverMuted)
            } else {
                Text("${importRow.matched} matched · ${importRow.review} to review · ${importRow.failed} failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            importRow.playlistId?.let { id ->
                GlassGhostButton("Open playlist", { onOpenPlaylist(id) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (reviewItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (running) "Reviewing as songs match…" else "Nothing left to review.",
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
                items(reviewItems, key = { it.id }) { item ->
                    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusMd)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            Text(item.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val candidates = candidatesOf(item)
                            if (candidates.isEmpty()) {
                                Text("No candidates found.", style = MaterialTheme.typography.bodySmall, color = GlassTokens.SilverMuted, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                candidates.forEach { candidate ->
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        GlassArtwork(track = null, modifier = Modifier.size(40.dp), radius = 12.dp, artUri = candidate.thumbnailUrl)
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                            Text("${candidate.artist} · ${candidateScore(candidate.score)}", style = MaterialTheme.typography.labelSmall, color = GlassTokens.SilverMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        GlassGhostButton("Use", { onPick(item, candidate) })
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassGhostButton("Retry", { onRetry(item) })
                                GlassGhostButton("Skip", { onSkip(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}
