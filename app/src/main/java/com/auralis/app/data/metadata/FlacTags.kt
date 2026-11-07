package com.auralis.app.data.metadata

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * FLAC Vorbis-comment and picture reader/writer. STREAMINFO and all audio frames are copied
 * unchanged; only metadata blocks are rebuilt, so the audio stream is never re-encoded.
 */
object FlacTags {
    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS = 4
    private const val TYPE_PICTURE = 6

    private data class Block(val type: Int, val data: ByteArray)

    fun hasTag(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()

    fun read(data: ByteArray): TagData? {
        if (!hasTag(data)) return null
        val blocks = parseBlocks(data)?.first ?: return null
        val comments: List<Pair<String, String>> =
            blocks.firstOrNull { it.type == TYPE_VORBIS }?.let { block -> parseComments(block.data) } ?: emptyList()
        val picture = blocks.firstOrNull { it.type == TYPE_PICTURE }?.let { parsePicture(it.data) }
            ?.takeIf { it.second == 3 }?.first
            ?: blocks.firstOrNull { it.type == TYPE_PICTURE }?.let { parsePicture(it.data) }?.first
        fun value(key: String): String = comments.firstOrNull { it.first.equals(key, true) }?.second.orEmpty()
        return TagData(
            title = value("TITLE"),
            artist = value("ARTIST"),
            album = value("ALBUM"),
            albumArtist = value("ALBUMARTIST"),
            genre = value("GENRE"),
            year = value("DATE").take(4).toIntOrNull(),
            trackNumber = value("TRACKNUMBER").substringBefore('/').toIntOrNull(),
            discNumber = value("DISCNUMBER").substringBefore('/').toIntOrNull(),
            composer = value("COMPOSER"),
            artwork = picture,
        )
    }

    fun write(data: ByteArray, tag: TagData): ByteArray {
        val parsed = parseBlocks(data) ?: throw IllegalArgumentException("Not a valid FLAC file")
        val blocks = parsed.first
        val audio = data.copyOfRange(parsed.second, data.size)
        val existingComments: List<Pair<String, String>> =
            blocks.firstOrNull { it.type == TYPE_VORBIS }?.let { block -> parseComments(block.data) } ?: emptyList()
        val updated = LinkedHashMap<String, String>()
        for ((key, value) in existingComments) {
            if (key.uppercase() !in EDITABLE_KEYS) updated[key] = value
        }
        fun put(key: String, value: String?) {
            if (!value.isNullOrBlank()) updated[key] = value.trim()
        }
        put("TITLE", tag.title)
        put("ARTIST", tag.artist)
        put("ALBUM", tag.album)
        put("ALBUMARTIST", tag.albumArtist)
        put("GENRE", tag.genre)
        put("DATE", tag.year?.toString())
        put("TRACKNUMBER", tag.trackNumber?.toString())
        put("DISCNUMBER", tag.discNumber?.toString())
        put("COMPOSER", tag.composer)

        val rebuilt = ArrayList<Block>()
        val streamInfo = blocks.firstOrNull { it.type == TYPE_STREAMINFO }
            ?: throw IllegalArgumentException("FLAC STREAMINFO block is missing")
        rebuilt += streamInfo
        for (block in blocks) {
            if (block.type == TYPE_STREAMINFO || block.type == TYPE_VORBIS || block.type == TYPE_PADDING) continue
            if (block.type == TYPE_PICTURE) {
                if (tag.artwork != null || tag.removeArtwork) continue
                rebuilt += block
            } else {
                rebuilt += block
            }
        }
        rebuilt += Block(TYPE_VORBIS, buildComments(updated))
        if (tag.artwork != null && tag.artwork.isNotEmpty()) rebuilt += Block(TYPE_PICTURE, buildPicture(tag.artwork))
        val currentSize = blocks.sumOf { it.data.size + 4 }
        val newSize = rebuilt.sumOf { it.data.size + 4 }
        val padding = (currentSize - newSize).coerceIn(0, 65_536)
        if (padding > 0) rebuilt += Block(TYPE_PADDING, ByteArray(padding))

        val output = ByteArrayOutputStream(data.size + 2048)
        output.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        rebuilt.forEachIndexed { index, block ->
            val last = index == rebuilt.lastIndex
            output.write((if (last) 0x80 else 0) or (block.type and 0x7F))
            val length = block.data.size
            output.write((length shr 16) and 0xFF)
            output.write((length shr 8) and 0xFF)
            output.write(length and 0xFF)
            output.write(block.data)
        }
        output.write(audio)
        return output.toByteArray()
    }

    private fun parseBlocks(data: ByteArray): Pair<List<Block>, Int>? {
        if (!hasTag(data)) return null
        val blocks = ArrayList<Block>()
        var offset = 4
        var last = false
        while (!last && offset + 4 <= data.size) {
            val header = data[offset].toInt() and 0xFF
            last = header and 0x80 != 0
            val type = header and 0x7F
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length < 0 || offset + length > data.size) return null
            blocks += Block(type, data.copyOfRange(offset, offset + length))
            offset += length
            if (blocks.size > 1024) return null
        }
        return blocks to offset
    }

    private fun parseComments(block: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var offset = 0
        fun readInt(): Int? {
            if (offset + 4 > block.size) return null
            val value = (block[offset].toInt() and 0xFF) or
                ((block[offset + 1].toInt() and 0xFF) shl 8) or
                ((block[offset + 2].toInt() and 0xFF) shl 16) or
                ((block[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value
        }
        fun readString(length: Int): String? {
            if (length < 0 || offset + length > block.size) return null
            val value = String(block, offset, length, StandardCharsets.UTF_8)
            offset += length
            return value
        }
        val vendorLength = readInt() ?: return out
        readString(vendorLength) ?: return out
        val count = readInt() ?: return out
        for (index in 0 until count.coerceAtMost(4096)) {
            val length = readInt() ?: break
            val entry = readString(length) ?: break
            val equals = entry.indexOf('=')
            if (equals > 0) out += entry.substring(0, equals) to entry.substring(equals + 1)
        }
        return out
    }

    private fun buildComments(comments: Map<String, String>): ByteArray {
        val vendor = "AURALIS".toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        fun writeInt(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
            output.write((value shr 16) and 0xFF)
            output.write((value shr 24) and 0xFF)
        }
        writeInt(vendor.size)
        output.write(vendor)
        writeInt(comments.size)
        for ((key, value) in comments) {
            val bytes = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun parsePicture(block: ByteArray): Pair<ByteArray, Int>? {
        var offset = 0
        fun readInt(): Int? {
            if (offset + 4 > block.size) return null
            val value = ((block[offset].toInt() and 0xFF) shl 24) or
                ((block[offset + 1].toInt() and 0xFF) shl 16) or
                ((block[offset + 2].toInt() and 0xFF) shl 8) or
                (block[offset + 3].toInt() and 0xFF)
            offset += 4
            return value
        }
        val type = readInt() ?: return null
        val mimeLength = readInt() ?: return null
        if (mimeLength < 0 || offset + mimeLength > block.size) return null
        offset += mimeLength
        val descriptionLength = readInt() ?: return null
        if (descriptionLength < 0 || offset + descriptionLength > block.size) return null
        offset += descriptionLength
        repeat(4) { readInt() ?: return null }
        val dataLength = readInt() ?: return null
        if (dataLength < 0 || offset + dataLength > block.size) return null
        return block.copyOfRange(offset, offset + dataLength) to type
    }

    private fun buildPicture(bytes: ByteArray): ByteArray {
        val mime = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 12 && bytes.sliceArray(8..11).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> "image/jpeg"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val output = ByteArrayOutputStream(bytes.size + 64)
        fun writeInt(value: Int) {
            output.write((value shr 24) and 0xFF)
            output.write((value shr 16) and 0xFF)
            output.write((value shr 8) and 0xFF)
            output.write(value and 0xFF)
        }
        writeInt(3)
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        writeInt(mimeBytes.size)
        output.write(mimeBytes)
        writeInt(0)
        writeInt(bounds.outWidth.coerceAtLeast(0))
        writeInt(bounds.outHeight.coerceAtLeast(0))
        writeInt(24)
        writeInt(0)
        writeInt(bytes.size)
        output.write(bytes)
        return output.toByteArray()
    }

    private val EDITABLE_KEYS = setOf(
        "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE", "TRACKNUMBER", "DISCNUMBER", "COMPOSER",
    )
}
