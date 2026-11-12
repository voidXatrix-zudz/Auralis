package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassTrackRows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.Track
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.LocalDownloadActions

@Composable fun TrackRows(tracks:List<Track>,favorites:Set<String>,currentId:String?,onPlay:(Track)->Unit,onFavorite:(Track)->Unit,playlists:List<PlaylistEntity> = emptyList(),onAddToPlaylist:(Long,Track)->Unit = { _,_ -> },modifier:Modifier=Modifier,onQueue:(Track)->Unit = {},onPlayNext:(Track)->Unit = onQueue,resolvingId:String? = null,downloadedIds:Set<String> = emptySet(),onDownload:(Track)->Unit = {},onDeleteDownload:(Track)->Unit = {}) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassTrackRows(tracks, favorites, currentId, onPlay, onFavorite, playlists, onAddToPlaylist, modifier, onQueue, onPlayNext, resolvingId, downloadedIds, onDownload, onDeleteDownload)
        return
    }
    val dl = LocalDownloadActions.current
    if(tracks.isEmpty()) { Box(modifier.fillMaxWidth().padding(36.dp),contentAlignment=Alignment.Center){Text("No matching songs.",color=MaterialTheme.colorScheme.onSurfaceVariant)};return }
    LazyColumn(modifier=modifier,contentPadding=PaddingValues(bottom=24.dp)) { items(tracks,key={it.id}) { track -> Row(Modifier.fillMaxWidth().clickable{onPlay(track)}.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick={onPlay(track)}) {
            if (resolvingId == track.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.PlayArrow,contentDescription="Play ${track.title}",tint=if(track.id==currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ArtworkImage(track,Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start=12.dp, end=4.dp)) {
            Text(track.title.ifBlank { track.displayName },maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
            Text(track.artist.ifBlank { "Unknown artist" },maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if ((track.remoteId ?: track.id.removePrefix("yt:")) in dl.downloadedIds) {
            Text("Downloaded", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.primary, modifier=Modifier.padding(end=4.dp))
        }
        IconButton(onClick={onQueue(track)}) { Icon(Icons.Rounded.QueueMusic,contentDescription="Add ${track.title} to queue",tint=MaterialTheme.colorScheme.onSurfaceVariant) }
        var expanded by remember(track.id) { mutableStateOf(false) }
        IconButton(onClick={onFavorite(track)}) { Icon(if(track.id in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,contentDescription=if(track.id in favorites) "Remove favorite" else "Add favorite",tint=if(track.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            IconButton(onClick={expanded=true}) { Icon(Icons.Rounded.MoreVert,"More actions for ${track.title}") }
            DropdownMenu(expanded,{expanded=false}) {
                DropdownMenuItem(text={Text("Play next")},onClick={onPlayNext(track);expanded=false})
                DropdownMenuItem(text={Text(if(track.id in favorites) "Unlike" else "Like")},onClick={onFavorite(track);expanded=false})
                if (track.isRemote) {
                    val videoId = track.remoteId ?: track.id.removePrefix("yt:")
                    if (videoId in dl.downloadedIds) {
                        DropdownMenuItem(text={Text("Delete download")},onClick={dl.onDeleteDownload(track);expanded=false})
                    } else {
                        DropdownMenuItem(text={Text("Download")},onClick={dl.onDownload(track);expanded=false})
                    }
                }
                playlists.forEach { list -> DropdownMenuItem(text={Text("Add to ${list.name}")},onClick={onAddToPlaylist(list.id,track);expanded=false}) }
            }
        }
    } } }
}
