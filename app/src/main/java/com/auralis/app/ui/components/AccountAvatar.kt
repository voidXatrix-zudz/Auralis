package com.auralis.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * The app-wide account-picture source order: the AURALIS (Clerk) profile image first, then the
 * signed-in YouTube Music avatar, then nothing (callers draw the default person icon).
 */
fun resolveAccountImageUrl(clerkImageUrl: String?, youtubeAvatarUrl: String?): String? =
    clerkImageUrl?.takeIf { it.isNotBlank() } ?: youtubeAvatarUrl?.takeIf { it.isNotBlank() }

/** Google profile photos default to a tiny `=s96-c`; request the crisper `=s256-c` instead. */
fun upscaleAccountImageUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.contains("googleusercontent", ignoreCase = true) && url.contains("=s96-c")) {
        url.replace("=s96-c", "=s256-c")
    } else {
        url
    }
}

/**
 * A circular account picture shared by both themes. The caller styles the circle (clip, background,
 * ring) through [modifier]; this only paints the default person icon underneath and the network
 * image (loaded/cached/crossfaded by the app's singleton Coil loader) on top when one is available.
 */
@Composable
fun AccountAvatar(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconPaddingFraction: Float = 0.28f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resolved = remember(imageUrl) { upscaleAccountImageUrl(imageUrl) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val shortest = minOf(maxWidth, maxHeight)
        val iconSize: Dp = (shortest * (1f - 2f * iconPaddingFraction)).coerceAtLeast(8.dp)
        Icon(Icons.Rounded.Person, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))

        if (!resolved.isNullOrBlank()) {
            val sidePx = with(density) { shortest.roundToPx() }.coerceAtLeast(1)
            val request = remember(context, resolved, sidePx) {
                ImageRequest.Builder(context)
                    .data(resolved)
                    .size(sidePx)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
