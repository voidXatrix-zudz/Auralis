package com.auralis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.performance.LocalPerformanceProfile
import java.io.File

private val ArtworkKeyPattern = Regex("[a-zA-Z0-9_-]{1,80}")

/**
 * Always paints a themed placeholder first; corrupt or missing cache files simply leave it visible.
 *
 * The decode size is clamped to the on-screen size (and to the active profile's pixel ceiling), so a
 * full-screen backdrop does not decode a 4000px image at 44dp, and Low mode keeps bitmaps small.
 */
@Composable
fun ArtworkImage(track: Track, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current
    val image = remember(track.artKey) {
        track.artKey?.takeIf { it.matches(ArtworkKeyPattern) }
            ?.let { key -> File(context.filesDir, "artwork/$key.jpg").takeIf(File::isFile) }
    }
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        // Largest side the composable can actually occupy, before the profile ceiling.
        val displayPx = with(density) {
            val w = if (maxWidth.value.isFinite()) maxWidth.toPx() else 0f
            val h = if (maxHeight.value.isFinite()) maxHeight.toPx() else 0f
            maxOf(w, h)
        }
        val targetPx = displayPx.toInt().coerceIn(1, profile.artworkMaxPx)
        // Build the request once per image + size: a fresh ImageRequest on every recomposition can
        // restart Coil's load.
        val request = remember(context, image, targetPx, profile.artworkCrossfade) {
            image?.let {
                ImageRequest.Builder(context)
                    .data(it)
                    .size(targetPx)
                    .crossfade(profile.artworkCrossfade)
                    .build()
            }
        }
        Icon(
            Icons.Rounded.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.42f),
        )
        if (request != null) AsyncImage(
            model = request,
            contentDescription = "Album artwork for ${track.title}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
