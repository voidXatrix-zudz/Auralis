package com.auralis.app.ui.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.auralis.app.data.artwork.ArtworkResolver
import com.auralis.app.data.youtube.fallbackThumbnailUrl
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import com.auralis.app.ui.performance.LocalPerformanceProfile
import java.io.File

private val ArtworkKeyPattern = Regex("[a-zA-Z0-9_-]{1,80}")

/** Neutral dark tile behind art that has not arrived yet (never pure black, never a grey wash). */
private val NeutralArtworkTile = Color(0xFF151518)

/** Local sidecar art (downloaded songs) is used directly; anything else is treated as a remote URL. */
private fun localOrRemoteArt(value: String): Any = when {
    value.startsWith("file://") -> Uri.parse(value)
    value.startsWith("/") -> File(value)
    else -> normalizeArtworkUrl(value)
}

/**
 * Always paints a themed placeholder first; corrupt or missing cache files simply leave it visible.
 *
 * The previous artwork stays painted underneath the incoming one and the new image fades in over
 * ~150 ms (profile permitting). That is what stops a restored mini-player, or a queue change, from
 * flashing an empty tile while Coil decodes the next bitmap.
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
    /** The mini-player's flat dark tile: no album glyph, no themed grey fill. */
    neutralPlaceholder: Boolean = false,
    /** Fixed decode size for small slots; null lets the on-screen size decide. */
    requestSizePx: Int? = null,
    /** Fired once when art has painted (or when every candidate failed), for skeleton hand-off. */
    onArtworkSettled: () -> Unit = {},
) {
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current
    val settledCallback by rememberUpdatedState(onArtworkSettled)

    // Ordered candidates: real art (largest square) → its smaller square variant → the video
    // thumbnail as an absolute last resort. Coil's Crop keeps everything square.
    val candidates: List<Any> = remember(artUri, track?.artKey, track?.artworkUrl, track?.remoteId, track?.uri) {
        val fromPlayback = artUri?.let { uri ->
            when (uri) {
                is Uri -> uri
                is String -> uri.takeIf { it.isNotBlank() }?.let { normalizeArtworkUrl(it) }
                else -> uri
            }
        }
        // The same track-derived art the Home lists use, kept as a fallback even when the player
        // supplied its own URI: the mini-player must resolve artwork exactly like the lists do.
        val fromTrack = track?.artworkUrl?.takeIf { it.isNotBlank() }?.let(::localOrRemoteArt)
            ?: track?.artKey?.takeIf { it.matches(ArtworkKeyPattern) }
                ?.let { key -> File(context.filesDir, "artwork/$key.jpg").takeIf(File::isFile) }
        val primary = fromPlayback ?: fromTrack
        buildList {
            if (primary != null) add(primary)
            if (fromTrack != null) add(fromTrack)
            if (primary is String) {
                val smaller = ArtworkResolver.smallerVariant(primary)
                if (smaller != primary) add(smaller)
            }
            val videoId = track?.remoteId
                ?: track?.uri?.takeIf { it.startsWith("auralis-yt://") }?.removePrefix("auralis-yt://")
            if (videoId != null) add(fallbackThumbnailUrl(videoId))
        }.distinct()
    }

    // Advance to the next candidate when one fails to load.
    var attempt by remember(candidates) { mutableStateOf(0) }
    val effectiveImage = candidates.getOrNull(attempt)

    // The last request that actually painted, and whether the incoming one has painted yet. The old
    // bitmap is drawn underneath until the new one is ready, so no recomposition flashes the tile.
    var lastPainted by remember { mutableStateOf<Pair<Any?, ImageRequest>?>(null) }
    var painted by remember(effectiveImage) { mutableStateOf(false) }
    val fadeMs = if (profile.artworkCrossfade) 150 else 0
    val fade by animateFloatAsState(
        targetValue = if (painted || fadeMs == 0) 1f else 0f,
        animationSpec = tween(durationMillis = fadeMs),
        label = "artworkFade",
    )

    BoxWithConstraints(
        modifier.clip(shape).background(
            if (neutralPlaceholder) NeutralArtworkTile else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        // Largest side the composable can actually occupy, before the profile ceiling.
        val displayPx = with(density) {
            val w = if (maxWidth.value.isFinite()) maxWidth.toPx() else 0f
            val h = if (maxHeight.value.isFinite()) maxHeight.toPx() else 0f
            maxOf(w, h)
        }
        val targetPx = (requestSizePx ?: displayPx.toInt()).coerceIn(1, profile.artworkMaxPx)
        // Build the request once per image + size: a fresh ImageRequest on every recomposition can
        // restart Coil's load. The fade is ours (alpha over the previous bitmap), so Coil's own
        // crossfade is off to avoid a double fade.
        val request = remember(context, effectiveImage, targetPx) {
            effectiveImage?.let { data ->
                ImageRequest.Builder(context)
                    .data(data)
                    .size(targetPx)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .listener(
                        onError = { _, result ->
                            // Successes (including memory-cache hits) stay out of the log; only a
                            // failed artwork request is worth a line.
                            AppLog.w(
                                "Artwork",
                                "Artwork load failed (source=$data): ${result.throwable.message}",
                            )
                            if (attempt < candidates.lastIndex) {
                                attempt++
                            } else {
                                settledCallback()
                            }
                        }
                    )
                    .build()
            }
        }

        val albumPainter = rememberVectorPainter(Icons.Rounded.Album)

        if (!neutralPlaceholder) {
            Icon(
                Icons.Rounded.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
        // The previous bitmap, still visible while the incoming one loads.
        lastPainted?.let { (data, previous) ->
            if (data != effectiveImage) {
                AsyncImage(
                    model = previous,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = track?.title?.let { "Album artwork for $it" } ?: "Album artwork",
                placeholder = if (neutralPlaceholder) null else albumPainter,
                error = if (neutralPlaceholder) null else albumPainter,
                fallback = if (neutralPlaceholder) null else albumPainter,
                onSuccess = {
                    painted = true
                    lastPainted = effectiveImage to request
                    settledCallback()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (fadeMs == 0) 1f else fade),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
