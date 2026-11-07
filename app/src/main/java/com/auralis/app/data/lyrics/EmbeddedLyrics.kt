package com.auralis.app.data.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.Track
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads lyrics embedded inside audio files without loading the audio payload:
 * ID3v2 USLT/SYLT/TXXX for MP3, Vorbis comments for FLAC and the `©lyr` atom for MP4/M4A.
 *
 * All parsers are byte-array based so they can be unit tested without an Android context.
 */
object EmbeddedLyrics {

    data class Result(val text: String, val synced: Boolean)

    private const val TAG = "AuralisEmbeddedLyrics"
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024
    private const val MAX_ATOM_BYTES = 2 * 1024 * 1024
    private val LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LRC", "UNSYNCED LYRICS")

    fun read(context: Context, track: Track): Result? {
        val ext = track.ext.lowercase()
        val mime = track.mime.lowercase()
        val isMp3 = ext == "mp3" || mime.contains("mpeg")
        val isFlac = ext == "flac" || mime.contains("flac")
        val isMp4 = ext in setOf("m4a", "mp4", "m4b", "aac") || mime.contains("mp4") || mime.contains("m4a")
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(track.uri))?.use { raw ->
                val input = raw.buffered(16 * 1024)
                when {
                    isFlac -> readFlac(input)
                    isMp4 -> readMp4(input)
                    isMp3 -> readId3(input)
                    else -> readId3(input) ?: readFlac(input) ?: readMp4(input)
                }
            }
        }.onFailure { Log.w(TAG, "Could not read embedded lyrics for ${track.displayName}", it) }.getOrNull()
    }

    // ---------------------------------------------------------------- ID3 / MP3

    private fun readId3(input: InputStream): Result? {
        val header = input.readExact(10) ?: return null
        if (!header.startsWithId3) return null
        val size = syncSafe(header, 6).coerceIn(0, MAX_TAG_BYTES)
        val body = input.readExact(size) ?: return null
        val tag = header + body
        return decodeId3(tag)
    }

    fun decodeId3(tag: ByteArray): Result? {
        if (tag.size < 10 || !tag.startsWithId3) return null
        val major = tag[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = tag[5].toInt() and 0xFF
        val declared = syncSafe(tag, 6)
        var body = tag.copyOfRange(10, (10 + declared).coerceAtMost(tag.size))
        if (flags and 0x80 != 0) body = removeUnsynchronisation(body)
        val frames = parseId3Frames(body, major)
        // Prefer synchronized lyrics, then the first plain lyrics frame.
        for ((id, payload) in frames) {
            if (id == "SYLT" || id == "SLT") {
                decodeSylt(payload)?.let { text ->
                    if (text.isNotBlank()) return Result(text, LrcParser.hasTimestamps(text))
                }
            }
        }
        for ((id, payload) in frames) {
            val text = when (id) {
                "USLT", "ULT" -> decodeUslt(payload)
                "TXXX", "TXX" -> decodeTxxx(payload)
                else -> null
            }
            if (!text.isNullOrBlank()) return Result(text.trimEnd('\u0000', '\n', ' '), LrcParser.hasTimestamps(text))
        }
        return null
    }

    private fun parseId3Frames(body: ByteArray, major: Int): List<Pair<String, ByteArray>> {
        val frames = mutableListOf<Pair<String, ByteArray>>()
        var offset = 0
        val headerSize = if (major == 2) 6 else 10
        while (offset + headerSize <= body.size) {
            val idLength = if (major == 2) 3 else 4
            val id = String(body, offset, idLength, Charsets.ISO_8859_1)
            if (id.isEmpty() || id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val size = when (major) {
                2 -> ((body[offset + 3].toInt() and 0xFF) shl 16) or
                    ((body[offset + 4].toInt() and 0xFF) shl 8) or (body[offset + 5].toInt() and 0xFF)
                3 -> beInt(body, offset + 4)
                else -> syncSafe(body, offset + 4)
            }
            val flags = if (major == 2) 0 else ((body[offset + 8].toInt() and 0xFF) shl 8) or (body[offset + 9].toInt() and 0xFF)
            val start = offset + headerSize
            if (size < 0 || start + size > body.size || size == 0) break
            var payload = body.copyOfRange(start, start + size)
            if (major == 4 && flags and 0x0002 != 0) payload = removeUnsynchronisation(payload)
            if (major == 3 && flags and 0x00C0 != 0) { offset = start + size; continue }
            if (major == 4 && flags and 0x000C != 0) { offset = start + size; continue }
            frames += id to payload
            offset = start + size
        }
        return frames
    }

    private fun decodeUslt(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        val descriptorStart = 4
        val textStart = terminator(payload, descriptorStart, encoding) ?: return null
        val text = decodeText(payload, textStart, payload.size, encoding)
        return text.trim('\u0000')
    }

    private fun decodeTxxx(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xFF
        val descriptionEnd = terminator(payload, 1, encoding) ?: return null
        val description = decodeText(payload, 1, descriptionEnd, encoding).trim('\u0000')
        if (description.uppercase().replace(" ", "") !in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")) return null
        return decodeText(payload, descriptionEnd, payload.size, encoding).trim('\u0000')
    }

    /** Converts an ID3 SYLT frame into LRC so the normal timeline and highlighting apply. */
    private fun decodeSylt(payload: ByteArray): String? {
        if (payload.size < 8) return null
        val encoding = payload[0].toInt() and 0xFF
        val timestampFormat = payload[4].toInt() and 0xFF
        val contentStart = terminator(payload, 6, encoding) ?: return null
        val output = StringBuilder()
        var offset = contentStart
        while (offset + 4 <= payload.size) {
            val next = terminatorOrEnd(payload, offset, encoding)
            val line = decodeText(payload, offset, next, encoding).trim('\u0000', '\n', '\r').trim()
            val timeOffset = next + if (encoding == 1 || encoding == 2) 2 else 1
            if (timeOffset + 4 > payload.size) break
            val rawTime = beInt(payload, timeOffset)
            if (rawTime < 0) break
            val timeMs = if (timestampFormat == 2) rawTime.toLong() else (rawTime.toLong() * 26L)
            if (line.isNotEmpty()) output.append(formatLrcTime(timeMs)).append(line).append('\n')
            offset = timeOffset + 4
        }
        return output.toString().takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(input: InputStream): Result? {
        val marker = input.readExact(4) ?: return null
        if (marker[0] != 'f'.code.toByte() || marker[1] != 'L'.code.toByte() ||
            marker[2] != 'a'.code.toByte() || marker[3] != 'C'.code.toByte()
        ) return null
        repeat(256) {
            val blockHeader = input.readExact(4) ?: return null
            val type = blockHeader[0].toInt() and 0x7F
            val last = blockHeader[0].toInt() and 0x80 != 0
            val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or (blockHeader[3].toInt() and 0xFF)
            if (type == 4) {
                val block = input.readExact(length) ?: return null
                decodeVorbisComment(block)?.let { return it }
            } else {
                if (length > 0 && !input.skipExact(length.toLong())) return null
            }
            if (last) return null
        }
        return null
    }

    fun decodeFlac(data: ByteArray): Result? {
        if (data.size < 8) return null
        var offset = 0
        if (data.startsWithFlac) offset = 4
        var guard = 0
        while (offset + 4 <= data.size && guard++ < 256) {
            val type = data[offset].toInt() and 0x7F
            val last = data[offset].toInt() and 0x80 != 0
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length < 0 || offset + length > data.size) return null
            if (type == 4) decodeVorbisComment(data.copyOfRange(offset, offset + length))?.let { return it }
            offset += length
            if (last) break
        }
        return null
    }

    private fun decodeVorbisComment(block: ByteArray): Result? {
        if (block.size < 8) return null
        var offset = 0
        fun readInt(): Int? {
            if (offset + 4 > block.size) return null
            val value = leInt(block, offset)
            offset += 4
            return value
        }
        val vendorLength = readInt() ?: return null
        if (vendorLength < 0 || offset + vendorLength > block.size) return null
        offset += vendorLength
        val count = readInt() ?: return null
        if (count < 0 || count > 100_000) return null
        var plain: String? = null
        repeat(count) {
            val length = readInt() ?: return null
            if (length < 0 || offset + length > block.size) return null
            val entry = String(block, offset, length, Charsets.UTF_8)
            offset += length
            val key = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "").trim()
            if (value.isNotBlank() && key in LYRICS_KEYS) {
                if (LrcParser.hasTimestamps(value)) return Result(value, true)
                if (plain == null) plain = value
            }
        }
        return plain?.let { Result(it, false) }
    }

    // ---------------------------------------------------------------- MP4 / M4A

    private fun readMp4(input: InputStream): Result? {
        val reader = StreamReader(input)
        // Verify the ftyp box before walking atoms.
        val first = reader.read(8) ?: return null
        if (String(first, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        val ftypSize = beInt(first, 0)
        if (ftypSize < 8 || !reader.skip((ftypSize - 8).toLong())) return null
        val end = Long.MAX_VALUE
        return walkAtoms(reader, end, 0)
    }

    private fun walkAtoms(reader: StreamReader, end: Long, depth: Int): Result? {
        if (depth > 12) return null
        while (reader.position + 8 <= end) {
            val atomStart = reader.position
            val header = reader.read(8) ?: return null
            var size = beInt(header, 0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) {
                val extended = reader.read(8) ?: return null
                size = beLong(extended, 0)
                headerSize = 16
            }
            if (size == 0L) return null
            val atomEnd = atomStart + size
            if (size < headerSize) return null
            when {
                type == "\u00A9lyr" -> {
                    val length = (atomEnd - reader.position).coerceAtMost(MAX_ATOM_BYTES.toLong()).toInt()
                    if (length <= 0) return null
                    val payload = reader.read(length) ?: return null
                    decodeMp4Lyrics(payload)?.let { return it }
                }
                type in MP4_CONTAINERS -> {
                    if (type == "meta" && !reader.skip(4)) return null
                    walkAtoms(reader, atomEnd, depth + 1)?.let { return it }
                }
            }
            if (reader.position < atomEnd) {
                val remaining = atomEnd - reader.position
                if (!reader.skip(remaining)) return null
            } else if (reader.position > atomEnd) {
                return null
            }
        }
        return null
    }

    fun decodeMp4Lyrics(payload: ByteArray): Result? {
        var offset = 0
        var text: String? = null
        while (offset + 16 <= payload.size) {
            val size = beInt(payload, offset)
            if (size < 16 || offset + size > payload.size) break
            val type = String(payload, offset + 4, 4, Charsets.ISO_8859_1)
            if (type == "data") {
                val dataType = beInt(payload, offset + 8) and 0xFFFFFF
                val start = offset + 16
                val end = offset + size
                if (end > start) {
                    val decoded = when (dataType) {
                        2 -> String(payload, start, end - start, Charsets.UTF_16BE)
                        else -> String(payload, start, end - start, Charsets.UTF_8)
                    }.trim('\u0000', '\uFEFF', '\n', '\r')
                    if (decoded.isNotBlank()) { text = decoded; break }
                }
            }
            offset += size
        }
        return text?.let { Result(it, LrcParser.hasTimestamps(it)) }
    }

    // ---------------------------------------------------------------- helpers

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end || start < 0 || end > bytes.size) return ""
        return when (encoding) {
            1 -> String(bytes, start, end - start, Charsets.UTF_16)
            2 -> String(bytes, start, end - start, Charsets.UTF_16BE)
            3 -> String(bytes, start, end - start, Charsets.UTF_8)
            else -> String(bytes, start, end - start, Charsets.ISO_8859_1)
        }.trim('\u0000', '\uFEFF')
    }

    /** Returns the index just after the text terminator, or null when the payload is truncated. */
    private fun terminator(bytes: ByteArray, start: Int, encoding: Int): Int? {
        val index = terminatorOrEnd(bytes, start, encoding)
        return when {
            encoding == 1 || encoding == 2 -> if (index + 1 < bytes.size) index + 2 else null
            index < bytes.size -> index + 1
            else -> null
        }
    }

    private fun terminatorOrEnd(bytes: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < bytes.size) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            return bytes.size
        }
        var i = start
        while (i < bytes.size) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return bytes.size
    }

    private fun formatLrcTime(timeMs: Long): String {
        val totalSeconds = (timeMs.coerceAtLeast(0L)) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs.coerceAtLeast(0L) % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    private fun removeUnsynchronisation(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(input.size)
        var index = 0
        while (index < input.size) {
            val value = input[index].toInt() and 0xFF
            output.write(value)
            if (value == 0xFF && index + 1 < input.size && input[index + 1].toInt() == 0) index++
            index++
        }
        return output.toByteArray()
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F))
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun beLong(bytes: ByteArray, offset: Int): Long {
        if (offset + 8 > bytes.size) return -1
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun InputStream.readExact(count: Int): ByteArray? {
        if (count < 0) return null
        if (count == 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val chunk = read(buffer, read, count - read)
            if (chunk <= 0) return null
            read += chunk
        }
        return buffer
    }

    private fun InputStream.skipExact(count: Long): Boolean {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    private class StreamReader(private val input: InputStream) {
        var position = 0L
            private set

        fun read(count: Int): ByteArray? {
            val bytes = input.readExact(count) ?: return null
            position += count
            return bytes
        }

        fun skip(count: Long): Boolean {
            if (count <= 0) return true
            if (!input.skipExact(count)) return false
            position += count
            return true
        }
    }

    private val ByteArray.startsWithId3: Boolean
        get() = size >= 10 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() && this[2] == '3'.code.toByte()

    private val ByteArray.startsWithFlac: Boolean
        get() = size >= 4 && this[0] == 'f'.code.toByte() && this[1] == 'L'.code.toByte() &&
            this[2] == 'a'.code.toByte() && this[3] == 'C'.code.toByte()

    private val MP4_CONTAINERS = setOf(
        "moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "stbl", "edts", "tref",
    )
    private const val MAX_ATOM_SCAN = 64L * 1024 * 1024
}
