package com.auralis.app.data.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads and writes tags through a content URI. The original bytes are backed up to the app cache
 * before the write and restored if the provider fails, keeping tag editing as safe as the storage
 * provider allows.
 */
class MetadataEditor(private val context: Context) {

    data class ReadResult(val format: TagFormat, val tag: TagData, val dynamic: Boolean)

    suspend fun read(uri: Uri, displayName: String): ReadResult = withContext(Dispatchers.IO) {
        val bytes = readAll(uri)
        val format = detect(bytes, displayName)
        val tag = when (format) {
            TagFormat.MP3 -> Id3v2.read(bytes) ?: TagData()
            TagFormat.FLAC -> FlacTags.read(bytes) ?: throw IllegalArgumentException("The FLAC metadata blocks are malformed")
            TagFormat.UNSUPPORTED -> throw IllegalArgumentException("Editing is supported for MP3 and FLAC files only")
        }
        ReadResult(format, tag, false)
    }

    suspend fun write(uri: Uri, displayName: String, tag: TagData): TagData = withContext(Dispatchers.IO) {
        val original = readAll(uri)
        val format = detect(original, displayName)
        val updated = when (format) {
            TagFormat.MP3 -> Id3v2.write(original, tag)
            TagFormat.FLAC -> FlacTags.write(original, tag)
            TagFormat.UNSUPPORTED -> throw IllegalArgumentException("Editing is supported for MP3 and FLAC files only")
        }
        // Re-parse before touching the real file. A malformed rebuild never reaches storage.
        val verified = when (format) {
            TagFormat.MP3 -> Id3v2.read(updated)
            TagFormat.FLAC -> FlacTags.read(updated)
            TagFormat.UNSUPPORTED -> null
        } ?: throw IllegalStateException("The rebuilt tag could not be verified")
        if (tag.title.isNotBlank() && verified.title != tag.title.trim()) {
            throw IllegalStateException("The tag verification did not match the edited title")
        }

        val backup = File(context.cacheDir, "tag-backup-${uri.hashCode()}.bin")
        backup.outputStream().use { it.write(original) }
        try {
            writeAll(uri, updated)
            runCatching { backup.delete() }
            verified
        } catch (error: Exception) {
            Log.e(TAG, "Tag write failed, restoring original bytes for $uri", error)
            runCatching { writeAll(uri, original) }
                .onFailure { Log.e(TAG, "Could not restore the original tag for $uri", it) }
            throw error
        } finally {
            runCatching { backup.delete() }
        }
    }

    fun detect(bytes: ByteArray, displayName: String): TagFormat {
        if (FlacTags.hasTag(bytes)) return TagFormat.FLAC
        if (Id3v2.hasTag(bytes)) return TagFormat.MP3
        if (bytes.size > 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) return TagFormat.MP3
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> TagFormat.MP3
            "flac" -> TagFormat.FLAC
            else -> TagFormat.UNSUPPORTED
        }
    }

    private fun readAll(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("The file could not be opened")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BYTES) throw IllegalArgumentException("This file is too large to edit safely")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun writeAll(uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "wt")
            ?: throw IllegalArgumentException("This file is read-only. Grant write access to the folder or allow the MediaStore edit request.")
        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private companion object {
        const val TAG = "AuralisTags"
        const val MAX_BYTES = 256L * 1024L * 1024L
    }
}
