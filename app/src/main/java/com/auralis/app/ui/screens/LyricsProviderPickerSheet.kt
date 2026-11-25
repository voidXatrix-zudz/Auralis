package com.auralis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.auralis.app.data.lyrics.LyricsProviderInfo
import com.auralis.app.ui.glass.GlassSheet

enum class LyricsProviderRowState { FETCHING, FOUND, NOT_FOUND }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LyricsProviderPickerSheet(
    providers: List<LyricsProviderInfo>,
    currentProviderId: String?,
    rowStates: Map<String, LyricsProviderRowState>,
    onProviderSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    GlassSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Choose lyrics provider",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(providers, key = LyricsProviderInfo::id) { provider ->
                    val current = provider.id == currentProviderId
                    val state = if (current) null else rowStates[provider.id]
                    val subtitle = when {
                        current -> "Current provider"
                        state == LyricsProviderRowState.FETCHING -> "Fetching lyrics…"
                        state == LyricsProviderRowState.FOUND -> "Lyrics found · Tap to use"
                        state == LyricsProviderRowState.NOT_FOUND -> "Lyrics not found"
                        else -> "Tap to fetch"
                    }
                    val colors = MaterialTheme.colorScheme
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (current) colors.primary.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.42f))
                            .clickable(enabled = !current) { onProviderSelected(provider.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            current -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                            state == LyricsProviderRowState.FETCHING -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.primary,
                            )
                            state == LyricsProviderRowState.NOT_FOUND -> Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.error, modifier = Modifier.size(22.dp))
                            state == LyricsProviderRowState.FOUND -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                            else -> Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(provider.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
