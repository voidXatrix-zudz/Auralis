package com.auralis.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.auralis.app.data.youtube.fallbackThumbnailUrl
import com.auralis.app.data.youtube.normalizeArtworkUrl
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.theme.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Five colours taken from the current artwork. Everything on the Now Playing screen is derived from
 * these, so a track change glides the whole surface to the new palette instead of swapping themes.
 *
 * Every colour is already clamped to a luma ceiling so white text keeps its contrast against the
 * blended background; the raw artwork brightness is never used directly.
 */
@Immutable
data class ArtPalette(
    val dominant: Color,
    val vibrant: Color,
    val muted: Color,
    val darkMuted: Color,
    val lightVibrant: Color,
) {
    companion object {
        /** No artwork, or extraction failed: the app's own Midnight Indigo / Vanilla Cream family. */
        val Fallback: ArtPalette = ArtPalette(
            dominant = GlassTokens.Indigo,
            vibrant = lerp(GlassTokens.Indigo, GlassTokens.Cream, 0.42f),
            muted = lerp(GlassTokens.Indigo, GlassTokens.Cream, 0.22f),
            darkMuted = Color(0xFF141A2E),
            lightVibrant = GlassTokens.Cream,
        )
    }
}

/** Palette plus the tiny (96 px) artwork copy used for the blurred background layer. */
@Immutable
data class ArtVisuals(
    val palette: ArtPalette,
    val bitmap: ImageBitmap?,
)

/** Perceptual luma of a colour (sRGB weights), 0..1. */
internal fun Color.luma(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Scales a colour down until its luma fits [max], preserving hue and saturation. */
internal fun Color.capLuma(max: Float): Color {
    val current = luma()
    if (current <= max || current <= 0.001f) return this
    val k = max / current
    return Color(
        (red * k).coerceIn(0f, 1f),
        (green * k).coerceIn(0f, 1f),
        (blue * k).coerceIn(0f, 1f),
        alpha,
    )
}

internal fun Color.darkenBy(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(red * (1f - f), green * (1f - f), blue * (1f - f), alpha)
}

/**
 * Dependency-free quantiser: samples at most ~64×64 pixels, buckets into 16³ bins and picks the
 * most representative swatch for each slot. Runs off the main thread; never throws.
 */
internal object NowPlayingPaletteEngine {

    private const val SAMPLE_SIDE = 64

    fun fromDominant(argb: Int?): ArtPalette {
        if (argb == null) return ArtPalette.Fallback
        val c = Color(argb)
        return ArtPalette(
            dominant = c.capLuma(0.46f),
            vibrant = lerp(c, Color.White, 0.12f).capLuma(0.56f),
            muted = lerp(c, Color.White, 0.20f).capLuma(0.42f),
            darkMuted = c.darkenBy(0.62f).capLuma(0.18f),
            lightVibrant = lerp(c, Color.White, 0.38f).capLuma(0.62f),
        )
    }

    fun extract(bitmap: Bitmap): ArtPalette {
        if (bitmap.width <= 0 || bitmap.height <= 0) return ArtPalette.Fallback
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return ArtPalette.Fallback
        } else {
            bitmap
        }
        val stepX = max(1, source.width / SAMPLE_SIDE)
        val stepY = max(1, source.height / SAMPLE_SIDE)
        // count, sumR, sumG, sumB, sumSat, sumLuma
        val buckets = HashMap<Int, FloatArray>(512)
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val color = source.getPixel(x, y)
                if (((color ushr 24) and 0xFF) >= 32) {
                    val r = ((color shr 16) and 0xFF) / 255f
                    val g = ((color shr 8) and 0xFF) / 255f
                    val b = (color and 0xFF) / 255f
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    if (luma > 0.035f) {
                        val key = (((color shr 16) and 0xFF) shr 4 shl 8) or
                            (((color shr 8) and 0xFF) shr 4 shl 4) or
                            ((color and 0xFF) shr 4)
                        val acc = buckets.getOrPut(key) { FloatArray(6) }
                        acc[0] += 1f
                        acc[1] += r
                        acc[2] += g
                        acc[3] += b
                        acc[4] += sat
                        acc[5] += luma
                    }
                }
                x += stepX
            }
            y += stepY
        }
        if (buckets.isEmpty()) return ArtPalette.Fallback

        class Swatch(val color: Color, val count: Float, val sat: Float, val luma: Float)

        val swatches = buckets.values.map { acc ->
            val n = acc[0]
            Swatch(
                color = Color(acc[1] / n, acc[2] / n, acc[3] / n),
                count = n,
                sat = acc[4] / n,
                luma = acc[5] / n,
            )
        }

        val dominant = swatches.maxByOrNull { it.count }?.color ?: return ArtPalette.Fallback
        val vibrant = swatches
            .filter { it.sat > 0.22f && it.luma in 0.16f..0.84f }
            .maxByOrNull { it.count * (0.4f + it.sat) }
            ?.color
            ?: dominant
        val muted = swatches
            .filter { it.sat in 0.05f..0.42f }
            .maxByOrNull { it.count }
            ?.color
            ?: dominant
        val darkMuted = swatches
            .filter { it.luma < 0.30f }
            .maxByOrNull { it.count }
            ?.color
            ?: dominant.darkenBy(0.60f)
        val lightVibrant = swatches
            .filter { it.luma > 0.55f && it.sat > 0.10f }
            .maxByOrNull { it.count * (0.5f + it.sat) }
            ?.color
            ?: lerp(vibrant, Color.White, 0.25f)

        return ArtPalette(
            dominant = dominant.capLuma(0.46f),
            vibrant = vibrant.capLuma(0.56f),
            muted = muted.capLuma(0.42f),
            darkMuted = darkMuted.capLuma(0.18f),
            lightVibrant = lightVibrant.capLuma(0.62f),
        )
    }
}

/** Small process-wide cache so returning to a song shows its palette instantly. */
internal object NowPlayingPaletteCache {
    private val cache = LruCache<String, Pair<ArtPalette, Bitmap?>>(24)

    fun get(trackId: String): Pair<ArtPalette, Bitmap?>? = runCatching { cache.get(trackId) }.getOrNull()

    fun put(trackId: String, palette: ArtPalette, bitmap: Bitmap?) {
        runCatching { cache.put(trackId, palette to bitmap) }
    }
}

/**
 * Palette + tiny artwork for [track]. Seeds instantly from the scan-time [Track.artColor], then
 * refines from the real bitmap on a background thread. Keyed by track so rapid skips cancel.
 */
@Composable
fun rememberArtVisuals(track: Track?, artUri: Any?): ArtVisuals {
    val context = LocalContext.current
    val trackId = track?.id
    val cached = remember(trackId) { trackId?.let(NowPlayingPaletteCache::get) }
    val seed = remember(trackId, track?.artColor) { NowPlayingPaletteEngine.fromDominant(track?.artColor) }
    val state = produceState(
        initialValue = ArtVisuals(cached?.first ?: seed, cached?.second?.asImageBitmap()),
        trackId,
        artUri,
    ) {
        if (trackId == null) {
            value = ArtVisuals(ArtPalette.Fallback, null)
            return@produceState
        }
        NowPlayingPaletteCache.get(trackId)?.let { (palette, bitmap) ->
            value = ArtVisuals(palette, bitmap?.asImageBitmap())
            return@produceState
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { loadArtBitmap(context, track, artUri) }.getOrNull()
        }
        if (bitmap == null) {
            NowPlayingPaletteCache.put(trackId, ArtPalette.Fallback, null)
            value = ArtVisuals(ArtPalette.Fallback, null)
            return@produceState
        }
        val palette = withContext(Dispatchers.Default) {
            runCatching { NowPlayingPaletteEngine.extract(bitmap) }.getOrDefault(ArtPalette.Fallback)
        }
        NowPlayingPaletteCache.put(trackId, palette, bitmap)
        value = ArtVisuals(palette, bitmap.asImageBitmap())
    }
    return state.value
}

private suspend fun loadArtBitmap(context: Context, track: Track?, artUri: Any?): Bitmap? {
    val data = resolveArtData(context, track, artUri) ?: return null
    val request = ImageRequest.Builder(context)
        .data(data)
        .size(96)
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    return if (bitmap.config == Bitmap.Config.HARDWARE) {
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    } else {
        bitmap
    }
}

private fun resolveArtData(context: Context, track: Track?, artUri: Any?): Any? {
    artUri?.let { uri ->
        return when (uri) {
            is Uri -> uri
            is String -> uri.takeIf { it.isNotBlank() }?.let(::normalizeArtworkUrl)
            else -> uri
        }
    }
    track?.artworkUrl?.takeIf { it.isNotBlank() }?.let { url ->
        return when {
            url.startsWith("file://") -> Uri.parse(url)
            url.startsWith("/") -> File(url)
            else -> normalizeArtworkUrl(url)
        }
    }
    track?.artKey?.takeIf { it.matches(ArtKeyPattern) }?.let { key ->
        File(context.filesDir, "artwork/$key.jpg").takeIf(File::isFile)?.let { return it }
    }
    val videoId = track?.remoteId
        ?: track?.uri?.takeIf { it.startsWith("auralis-yt://") }?.removePrefix("auralis-yt://")
    return videoId?.let(::fallbackThumbnailUrl)
}

private val ArtKeyPattern = Regex("[a-zA-Z0-9_-]{1,80}")
