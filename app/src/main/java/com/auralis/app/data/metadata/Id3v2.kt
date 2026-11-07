package com.auralis.app.data.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal, safe ID3v2.3/2.4 reader and writer for MP3 files. Unknown frames are preserved, the
 * audio payload is copied byte-for-byte, and the replacement tag is parsed again before writing.
 * ID3v1 at the end of the file is never touched.
 */
object Id3v2 {
    private val textFrameIds = mapOf(
        "TIT2" to "title", "TPE1" to "artist", "TALB" to "album", "TPE2" to "albumArtist",
        "TCON" to "genre", "TYER" to "year", "TDRC" to "year", "TRCK" to "track",
        "TPOS" to "disc", "TCOM" to "composer",
    )

    fun hasTag(data: ByteArray): Boolean =
        data.size >= 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()

    fun read(data: ByteArray): TagData? {
        if (!hasTag(data)) return null
        val header = parseHeader(data) ?: return null
        if (header.major < 3) return TagData()
        val end = (10 + header.size).coerceAtMost(data.size)
        var body = data.copyOfRange(10, end)
        if (header.unsynchronised) body = removeUnsynchronisation(body)
        val frames = parseFrames(body, header.major)
        fun text(id: String): String = frames.firstOrNull { it.first == id }
            ?.let { decodeText(it.second) }.orEmpty().trim()
        val artwork = frames.filter { it.first == "APIC" }
            .mapNotNull { decodePicture(it.second) }
            .let { pictures -> pictures.firstOrNull { it.second == 3 }?.first ?: pictures.firstOrNull()?.first }
        return TagData(
            title = text("TIT2"),
            artist = text("TPE1"),
            album = text("TALB"),
            albumArtist = text("TPE2"),
            genre = text("TCON").let(::expandGenre),
            year = text("TYER").ifBlank { text("TDRC") }.take(4).toIntOrNull(),
            trackNumber = text("TRCK").substringBefore('/').toIntOrNull(),
            discNumber = text("TPOS").substringBefore('/').toIntOrNull(),
            composer = text("TCOM"),
            artwork = artwork,
        )
    }

    /** Replaces the leading ID3v2 tag while preserving all audio bytes and unknown frames. */
    fun write(data: ByteArray, tag: TagData): ByteArray {
        val oldHeader = parseHeader(data)
        val oldFrames = if (oldHeader != null && oldHeader.major >= 3) {
            var body = data.copyOfRange(10, (10 + oldHeader.size).coerceAtMost(data.size))
            if (oldHeader.unsynchronised) body = removeUnsynchronisation(body)
            parseFrames(body, oldHeader.major)
        } else emptyList()
        val audioStart = oldHeader?.let { 10 + it.size + if (it.footer) 10 else 0 } ?: 0
        val audio = data.copyOfRange(audioStart.coerceAtMost(data.size), data.size)

        val output = ByteArrayOutputStream(data.size + 4096)
        val frames = ByteArrayOutputStream()
        fun addText(id: String, value: String) {
            if (value.isNotBlank()) frames.write(textFrame(id, value.trim()))
        }
        addText("TIT2", tag.title)
        addText("TPE1", tag.artist)
        addText("TALB", tag.album)
        addText("TPE2", tag.albumArtist)
        addText("TCON", tag.genre)
        tag.year?.let { addText("TYER", it.toString()) }
        tag.trackNumber?.let { addText("TRCK", it.toString()) }
        tag.discNumber?.let { addText("TPOS", it.toString()) }
        addText("TCOM", tag.composer)

        val picture = tag.artwork
        if (picture != null && picture.isNotEmpty()) {
            frames.write(pictureFrame(picture))
        } else if (!tag.removeArtwork) {
            val existing = oldFrames.firstOrNull { it.first == "APIC" }
            if (existing != null) frames.write(rawFrame("APIC", existing.second, 0, 0))
        }

        val known = textFrameIds.keys + "APIC"
        oldFrames.filter { it.first !in known && it.first.all { char -> char.isLetterOrDigit() } }
            .forEach { (id, payload) -> frames.write(rawFrame(id, payload, 0, 0)) }

        val frameBytes = frames.toByteArray()
        val padding = ByteArray(1024)
        val tagSize = frameBytes.size + padding.size
        output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        output.write(syncSafe(tagSize))
        output.write(frameBytes)
        output.write(padding)
        output.write(audio)
        return output.toByteArray()
    }

    private data class Header(val major: Int, val size: Int, val unsynchronised: Boolean, val footer: Boolean)

    private fun parseHeader(data: ByteArray): Header? {
        if (!hasTag(data) || data.size < 10) return null
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val size = syncSafeValue(data, 6)
        if (size < 0 || 10 + size > data.size + 16) return null
        return Header(major, size, flags and 0x80 != 0, flags and 0x10 != 0)
    }

    private fun parseFrames(body: ByteArray, major: Int): List<Pair<String, ByteArray>> {
        val frames = mutableListOf<Pair<String, ByteArray>>()
        var offset = 0
        if (major == 3 && body.size > 4 && body[0].toInt() and 0x40 != 0) {
            val extended = beInt(body, 0)
            offset = (4 + extended).coerceAtLeast(4)
        } else if (major == 4 && body.size > 4 && body[0].toInt() and 0x40 != 0) {
            offset = syncSafeValue(body, 0).coerceAtLeast(4)
        }
        while (offset + 10 <= body.size) {
            val id = String(body, offset, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val size = if (major == 4) syncSafeValue(body, offset + 4) else beInt(body, offset + 4)
            if (size <= 0 || offset + 10 + size > body.size) break
            val flags = body[offset + 9].toInt() and 0xFF
            var payload = body.copyOfRange(offset + 10, offset + 10 + size)
            if (major == 4 && flags and 0x01 != 0 && payload.size > 4) payload = payload.copyOfRange(4, payload.size)
            frames += id to payload
            offset += 10 + size
        }
        return frames
    }

    private fun textFrame(id: String, value: String): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_16LE)
        val payload = ByteArray(1 + 2 + encoded.size + 2)
        payload[0] = 1
        payload[1] = 0xFF.toByte()
        payload[2] = 0xFE.toByte()
        encoded.copyInto(payload, 3)
        return rawFrame(id, payload, 0, 0)
    }

    private fun pictureFrame(bytes: ByteArray): ByteArray {
        val mime = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 12 && bytes.sliceArray(8..11).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> "image/jpeg"
        }
        val output = ByteArrayOutputStream(bytes.size + 64)
        output.write(0) // ISO-8859-1
        output.write(mime.toByteArray(Charsets.ISO_8859_1))
        output.write(0)
        output.write(3) // front cover
        output.write(0) // empty description (ISO-8859-1)
        output.write(bytes)
        return rawFrame("APIC", output.toByteArray(), 0, 0)
    }

    private fun rawFrame(id: String, payload: ByteArray, flags1: Int, flags2: Int): ByteArray {
        val output = ByteArrayOutputStream(payload.size + 10)
        output.write(id.padEnd(4, ' ').take(4).toByteArray(Charsets.ISO_8859_1))
        output.write(byteArrayOf(
            ((payload.size shr 24) and 0xFF).toByte(),
            ((payload.size shr 16) and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
            flags1.toByte(),
            flags2.toByte(),
        ))
        output.write(payload)
        return output.toByteArray()
    }

    private fun decodeText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val encoding = payload[0].toInt() and 0xFF
        val body = payload.copyOfRange(1, payload.size)
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) Charsets.UTF_16LE
                else if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) Charsets.UTF_16BE
                else Charsets.UTF_16LE
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        var offset = if (encoding == 1 && body.size >= 2) 2 else 0
        var length = body.size - offset
        while (length >= 2 && body[offset + length - 1].toInt() == 0 && body[offset + length - 2].toInt() == 0) length -= 2
        while (length >= 1 && body[offset + length - 1].toInt() == 0) length -= 1
        return runCatching { String(body, offset, length, charset) }.getOrDefault("")
    }

    private fun decodePicture(payload: ByteArray): Pair<ByteArray, Int>? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        var offset = 1
        val mimeEnd = indexOfZero(payload, offset, singleByte = true) ?: return null
        offset = mimeEnd + 1
        if (offset >= payload.size) return null
        val pictureType = payload[offset].toInt() and 0xFF
        offset += 1
        val descriptionEnd = if (encoding == 0 || encoding == 3) {
            indexOfZero(payload, offset, singleByte = true)
        } else {
            indexOfDoubleZero(payload, offset)
        }
        offset = when {
            descriptionEnd == null -> return null
            encoding == 0 || encoding == 3 -> descriptionEnd + 1
            else -> descriptionEnd + 2
        }
        if (offset >= payload.size) return null
        return payload.copyOfRange(offset, payload.size) to pictureType
    }

    private fun indexOfZero(bytes: ByteArray, start: Int, singleByte: Boolean): Int? {
        var index = start
        while (index < bytes.size) {
            if (bytes[index].toInt() == 0) return index
            index += 1
        }
        return null
    }

    private fun indexOfDoubleZero(bytes: ByteArray, start: Int): Int? {
        var index = start
        while (index + 1 < bytes.size) {
            if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
            index += 2
        }
        return null
    }

    private fun removeUnsynchronisation(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(input.size)
        var index = 0
        while (index < input.size) {
            output.write(input[index].toInt())
            if (input[index].toInt() == 0xFF && index + 1 < input.size && input[index + 1].toInt() == 0) index += 1
            index += 1
        }
        return output.toByteArray()
    }

    private fun syncSafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    private fun syncSafeValue(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun expandGenre(value: String): String {
        val match = Regex("^\\((\\d{1,3})\\)$").find(value.trim()) ?: return value
        val index = match.groupValues[1].toIntOrNull() ?: return value
        return GENRES.getOrNull(index) ?: value
    }

    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz",
        "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno",
        "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno",
        "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul",
        "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer",
        "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll",
        "Hard Rock",
    )
}
