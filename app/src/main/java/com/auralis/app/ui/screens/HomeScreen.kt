package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassHomeScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.SectionTitle

@Composable
fun HomeScreen(
    sources: List<MusicSource>, tracks: List<Track>, favorites: Set<String>, currentId: String?,
    onAddMusic: () -> Unit, onScan: () -> Unit, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    playlists: List<com.auralis.app.data.db.PlaylistEntity>, onAddToPlaylist: (Long, Track) -> Unit,
    modifier: Modifier = Modifier, onQueue: (Track) -> Unit = {}, scanning: Boolean = false, onPlayNext: (Track) -> Unit = onQueue,
    onOpenSettings: () -> Unit = {}, settingsBadge: Boolean = false,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassHomeScreen(sources, tracks, favorites, currentId, onAddMusic, onScan, onPlay, onFavorite, playlists, onAddToPlaylist, modifier, onQueue, scanning, onPlayNext, onOpenSettings, settingsBadge)
        return
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Your music")
            TextButton(onClick = onScan, enabled = !scanning) {
                if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(if (scanning) "Scanning" else "Scan device", Modifier.padding(start = if (scanning) 8.dp else 0.dp))
            }
        }
        if (sources.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 100.dp).padding(horizontal = 16.dp)) { items(sources) { FolderRow(it.name) } }
        if (tracks.isEmpty()) EmptyLibraryHero(onAddMusic, onScan, Modifier.weight(1f), scanning, onOpenSettings, sources.isEmpty())
        else {
            Text("Songs · ${tracks.size}", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
            TrackRows(tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, Modifier.weight(1f), onQueue, onPlayNext)
        }
    }
}

@Composable
fun EmptyLibraryHero(onAddMusic: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier, scanning: Boolean = false, onOpenSettings: () -> Unit = {}, noFolder: Boolean = false) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Text(if (noFolder) "Choose a music folder to start" else "Your music, offline", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(if (noFolder) "AURALIS only scans the folders you choose." else "Scan songs on this device or add a folder.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (noFolder) {
            Button(onClick = onOpenSettings, shape = CircleShape) { Icon(Icons.Rounded.FolderOpen, null); Text("Choose a music folder", Modifier.padding(start = 8.dp)) }
        } else {
            Button(onClick = onScan, enabled = !scanning, shape = CircleShape) {
                if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(if (scanning) "Scanning..." else "Scan device", Modifier.padding(start = if (scanning) 8.dp else 0.dp))
            }
        }
        OutlinedButton(onClick = onAddMusic, enabled = !scanning, shape = CircleShape) { Icon(Icons.Rounded.FolderOpen, null); Text("Add folder", Modifier.padding(start = 8.dp)) }
    }
}

@Composable
fun FolderRow(name: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
        Text(name, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        trailing()
    }
}
