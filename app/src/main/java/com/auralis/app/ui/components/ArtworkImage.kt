package com.auralis.app.ui.components

import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.auralis.app.data.youtube.fallbackThumbnailUrl
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.performance.LocalPerformanceProfile
import java.io.File

private val ArtworkKeyPattern = Regex("[a-zA-Z0-9_-]{1,80}")

/** Local sidecar art (downloaded songs) is used directly; anything else is treated as a remote URL. */
private fun localOrRemoteArt(value: String): Any = when {
    value.startsWith("file://") -> Uri.parse(value)
    value.startsWith("/") -> File(value)
    else -> normalizeArtworkUrl(value)
}

/**
 * Always paints a themed placeholder first; corrupt or missing cache files simply leave it visible.
 *
 * The decode size is clamped to the on-screen size (and to the active profile's pixel ceiling), so a
 * full-screen backdrop does not decode a 4000px image at 44dp, and Low mode keeps bitmaps small.
 */
@Composable
fun ArtworkImage(
    track: Track? = null,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    artUri: Any? = null,
) {
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current

    val primaryImage: Any? = remember(artUri, track?.artKey, track?.artworkUrl) {
        artUri?.let { uri ->
            when (uri) {
                is Uri -> uri
                is String -> uri.takeIf { it.isNotBlank() }?.let(::normalizeArtworkUrl)
                else -> uri
            }
        } ?: track?.artworkUrl?.takeIf { it.isNotBlank() }?.let(::localOrRemoteArt)
          ?: track?.artKey?.takeIf { it.matches(ArtworkKeyPattern) }
              ?.let { key -> File(context.filesDir, "artwork/$key.jpg").takeIf(File::isFile) }
    }

    val fallbackImage: Any? = remember(track?.remoteId, track?.uri) {
        val videoId = track?.remoteId
            ?: track?.uri?.takeIf { it.startsWith("auralis-yt://") }?.removePrefix("auralis-yt://")
        videoId?.let(::fallbackThumbnailUrl)
    }

    var loadFailed by remember(primaryImage) { mutableStateOf(false) }
    val effectiveImage = if (loadFailed && fallbackImage != null && fallbackImage != primaryImage) {
        fallbackImage
    } else {
        primaryImage
    }

    BoxWithConstraints(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
        val request = remember(context, effectiveImage, targetPx, profile.artworkCrossfade) {
            effectiveImage?.let { data ->
                ImageRequest.Builder(context)
                    .data(data)
                    .size(targetPx)
                    .crossfade(profile.artworkCrossfade)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .listener(
                        onStart = {
                            AppLog.d("Artwork", "Loading art: $data")
                        },
                        onSuccess = { _, result ->
                            AppLog.i("Artwork", "Image load SUCCESS: $data (source=${result.dataSource})")
                        },
                        onError = { _, result ->
                            AppLog.e("Artwork", "Image load ERROR for $data: ${result.throwable.message}", result.throwable)
                            if (!loadFailed && fallbackImage != null && fallbackImage != data) {
                                AppLog.w("Artwork", "Retrying with fallback art: $fallbackImage")
                                loadFailed = true
                            }
                        }
                    )
                    .build()
            }
        }

        val albumPainter = rememberVectorPainter(Icons.Rounded.Album)

        Icon(
            Icons.Rounded.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.42f),
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = track?.title?.let { "Album artwork for $it" } ?: "Album artwork",
                placeholder = albumPainter,
                error = albumPainter,
                fallback = albumPainter,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
