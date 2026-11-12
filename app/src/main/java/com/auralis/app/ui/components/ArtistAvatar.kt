package com.auralis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.auralis.app.data.lyrics.LyricsMatcher
import com.auralis.app.ui.performance.LocalPerformanceProfile

/**
 * Resolves a circular artist photo (Part L). Provided once at the root so any screen can show an
 * artist image without threading a ViewModel through the composition.
 */
fun interface ArtistImageResolver {
    suspend fun resolve(name: String, browseId: String?, knownUrl: String?): String?

    companion object {
        /** Inert resolver used in previews/tests: never fetches, so the initial placeholder shows. */
        val Disabled = ArtistImageResolver { _, _, _ -> null }
    }
}

/** The app-wide artist photo resolver; screens read it through [ArtistAvatar]. */
val LocalArtistImages = staticCompositionLocalOf { ArtistImageResolver.Disabled }

/**
 * Circular artist photo with an initial-letter placeholder that shows while the image loads or when
 * none exists. [imageUrl] is a thumbnail already in hand (used directly); otherwise [browseId]/[name]
 * are resolved off the main thread, so lists never block while scrolling.
 */
@Composable
fun ArtistAvatar(
    name: String,
    modifier: Modifier = Modifier,
    browseId: String? = null,
    imageUrl: String? = null,
    size: Dp = 48.dp,
) {
    val resolver = LocalArtistImages.current
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current

    var resolved by remember(name, browseId, imageUrl) {
        mutableStateOf(imageUrl?.takeIf { it.isNotBlank() })
    }
    LaunchedEffect(name, browseId, imageUrl) {
        if (resolved == null && name.isNotBlank()) {
            resolved = runCatching { resolver.resolve(name, browseId, imageUrl) }.getOrNull()
        }
    }

    val initial = remember(name) {
        LyricsMatcher.primaryArtist(name).trim().firstOrNull()?.uppercaseChar()?.toString()
    }
    val targetPx = with(LocalDensity.current) { size.toPx().toInt() }.coerceIn(1, profile.artworkMaxPx)

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) {
            Text(
                initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxSize(0.55f),
            )
        }
        val url = resolved
        if (url != null) {
            val request = remember(context, url, targetPx, profile.artworkCrossfade) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(targetPx)
                    .crossfade(profile.artworkCrossfade)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "Photo of $name",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
