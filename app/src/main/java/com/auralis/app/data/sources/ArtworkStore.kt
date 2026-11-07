package com.auralis.app.data.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Stores small, deduplicated JPEG thumbnails; source artwork is never retained as a full bitmap. */
class ArtworkStore(context: Context) {
    private val directory = File(context.filesDir, "artwork").apply { mkdirs() }

    fun saveEmbedded(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_EMBEDDED_BYTES) return null
        return try {
            val key = sha256(bytes)
            writeThumbnail(key) { ByteArrayInputStream(bytes) }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Skipped embedded artwork while low on memory", e)
            null
        }
    }

    fun saveMediaStore(albumId: Long, open: () -> InputStream?): String? {
        if (albumId <= 0) return null
        return writeThumbnail("media-$albumId", open)
    }

    fun file(key: String): File? {
        if (!key.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) return null
        return File(directory, "$key.jpg").takeIf(File::isFile)
    }

    /** Dominant accent ARGB of a cached thumbnail, used by dynamic themes. */
    fun colorOf(key: String): Int? {
        val target = file(key) ?: return null
        var bitmap: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            bitmap = BitmapFactory.decodeFile(target.absolutePath, options) ?: return null
            com.auralis.app.ui.theme.ArtworkPalette.dominantColor(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract artwork colour for $key", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Skipped artwork colour while low on memory", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun writeThumbnail(key: String, open: () -> InputStream?): String? {
        val target = File(directory, "$key.jpg")
        if (target.isFile && target.length() > 0) return key
        var bitmap: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = open() ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = calculateSample(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
            bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val maxSide = maxOf(bitmap.width, bitmap.height)
            val output = if (maxSide > TARGET_SIZE) {
                val scale = TARGET_SIZE.toFloat() / maxSide
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            } else bitmap
            val temp = File(directory, "$key.tmp")
            temp.outputStream().buffered().use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) return null
            }
            if (output !== bitmap) output.recycle()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            key
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache album art $key", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Skipped oversized album art $key", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > TARGET_SIZE * 2) sample *= 2
        return sample
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AuralisArt"
        const val TARGET_SIZE = 512
        const val JPEG_QUALITY = 86
        const val MAX_EMBEDDED_BYTES = 24 * 1024 * 1024
    }
}
