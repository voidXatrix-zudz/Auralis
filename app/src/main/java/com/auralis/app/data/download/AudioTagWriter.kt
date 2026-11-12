package com.auralis.app.data.download

import android.graphics.BitmapFactory
import android.util.Base64
import com.auralis.app.data.metadata.TagData
import com.auralis.app.logging.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Best-effort metadata embedding for freshly downloaded audio: Ogg/Opus Vorbis comments (with a
 * base64 `METADATA_BLOCK_PICTURE`) and MP4/M4A iTunes-style atoms (`©nam`/`©ART`/`©alb`/`covr`).
 *
 * The audio stream itself is never re-encoded or even re-laid-out: for Opus only the header pages
 * are rebuilt, and for MP4 only the `moov` metadata is changed (with `stco`/`co64` chunk offsets
 * shifted if `moov` precedes `mdat`). Any failure leaves the original file untouched, so a download
 * is never lost to a tagging problem.
 */
object AudioTagWriter {
    private const val TAG = "Downloads"

    /** Embeds [tag] into [file] in place. Returns true only when the file was rewritten. */
    fun write(file: File, tag: TagData): Boolean {
        if (tag.title.isBlank() && tag.artist.isBlank() && tag.album.isBlank() &&
            (tag.artwork == null || tag.artwork.isEmpty())
        ) {
            return false
        }
        return runCatching {
            val original = file.readBytes()
            val tagged = when {
                isOgg(original) -> OpusTags.write(original, tag)
                isMp4(original) -> Mp4Tags.write(original, tag)
                else -> null
            } ?: return false
            val temp = File(file.parentFile, file.name + ".tagtmp")
            temp.writeBytes(tagged)
            if (!temp.renameTo(file)) {
                // renameTo can fail on some storage providers; fall back to a copy.
                file.outputStream().use { out -> temp.inputStream().use { it.copyTo(out) } }
                temp.delete()
            }
            true
        }.onFailure { AppLog.w(TAG, "Could not embed tags into ${file.name}", it) }.getOrDefault(false)
    }

    private fun isOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()

    private fun isMp4(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()

    // ---- shared Vorbis-comment + FLAC-picture building (used by the Opus writer) -------------

    private fun commentsFor(tag: TagData): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        fun add(key: String, value: String) {
            if (value.isNotBlank()) list += key to value.trim()
        }
        add("TITLE", tag.title)
        add("ARTIST", tag.artist)
        add("ALBUM", tag.album)
        add("ALBUMARTIST", tag.albumArtist)
        add("GENRE", tag.genre)
        tag.year?.let { add("DATE", it.toString()) }
        tag.trackNumber?.let { add("TRACKNUMBER", it.toString()) }
        tag.discNumber?.let { add("DISCNUMBER", it.toString()) }
        add("COMPOSER", tag.composer)
        tag.artwork?.takeIf { it.isNotEmpty() }?.let { cover ->
            list += "METADATA_BLOCK_PICTURE" to Base64.encodeToString(pictureBlock(cover), Base64.NO_WRAP)
        }
        return list
    }

    private fun vorbisCommentPacket(vendor: String, comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(StandardCharsets.UTF_8)
        le32(vendorBytes.size)
        out.write(vendorBytes)
        le32(comments.size)
        for ((key, value) in comments) {
            val bytes = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            le32(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** The same FLAC picture block FLAC tracks use, reused verbatim here as a base64 comment. */
    private fun pictureBlock(bytes: ByteArray): ByteArray {
        val mime = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 12 && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
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

    // ---- Ogg / Opus --------------------------------------------------------------------------

    private object OpusTags {
        private val OPUS_HEAD = "OpusHead".toByteArray(StandardCharsets.US_ASCII)

        private class Page(
            val headerType: Int,
            val serial: Int,
            val lacing: List<Int>,
            val raw: ByteArray,
        )

        fun write(data: ByteArray, tag: TagData): ByteArray? {
            // Read the header pages: the OpusHead packet and then the OpusTags packet.
            val pages = ArrayList<Page>()
            var offset = 0
            var endedPackets = 0
            while (offset + 27 <= data.size && pages.size <= 8) {
                val page = parsePage(data, offset) ?: return null
                pages += page
                offset += page.raw.size
                endedPackets += page.lacing.count { it < 255 }
                if (endedPackets >= 2) break
            }
            val head = pages.firstOrNull() ?: return null
            if (pages.size < 2) return null
            // Packet 0 must be OpusHead; otherwise this is not an Opus stream we can safely edit.
            val headPayload = payloadOf(pages.first().raw)
            if (headPayload.size < OPUS_HEAD.size ||
                !headPayload.copyOfRange(0, OPUS_HEAD.size).contentEquals(OPUS_HEAD)
            ) {
                return null
            }

            val packet = ByteArrayOutputStream().apply {
                write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
                write(vorbisCommentPacket("AURALIS", commentsFor(tag)))
            }.toByteArray()

            val tagPages = buildTagPages(packet, head.serial, firstSequence = 1)
            val tail = renumber(data.copyOfRange(offset, data.size), startSequence = 1 + tagPages.size)

            val out = ByteArrayOutputStream(data.size + tagPages.sumOf { it.size } + 64)
            out.write(pages.first().raw)
            tagPages.forEach(out::write)
            out.write(tail)
            return out.toByteArray()
        }

        /** Splits the OpusTags packet into one or more Ogg pages (255 lacing values per page). */
        private fun buildTagPages(packet: ByteArray, serial: Int, firstSequence: Int): List<ByteArray> {
            val laces = ArrayList<Int>()
            var remaining = packet.size
            while (remaining >= 255) {
                laces += 255
                remaining -= 255
            }
            laces += remaining // a final value < 255 terminates the packet

            val pages = ArrayList<ByteArray>()
            var index = 0
            var sequence = firstSequence
            var continued = false
            while (index < laces.size) {
                val take = minOf(255, laces.size - index)
                val group = laces.subList(index, index + take)
                val startByte = laces.subList(0, index).sum()
                val payload = packet.copyOfRange(startByte, startByte + group.sum())
                pages += serializePage(if (continued) 0x01 else 0x00, serial, sequence, group, payload)
                index += take
                sequence++
                continued = true
            }
            return pages
        }

        private fun parsePage(data: ByteArray, offset: Int): Page? {
            if (offset + 27 > data.size) return null
            if (!(data[offset] == 'O'.code.toByte() && data[offset + 1] == 'g'.code.toByte() &&
                    data[offset + 2] == 'g'.code.toByte() && data[offset + 3] == 'S'.code.toByte())
            ) {
                return null
            }
            val headerType = data[offset + 5].toInt() and 0xFF
            var serial = 0
            for (i in 0 until 4) serial = serial or ((data[offset + 14 + i].toInt() and 0xFF) shl (8 * i))
            val segmentCount = data[offset + 26].toInt() and 0xFF
            if (offset + 27 + segmentCount > data.size) return null
            val lacing = IntArray(segmentCount) { data[offset + 27 + it].toInt() and 0xFF }
            val total = 27 + segmentCount + lacing.sum()
            if (offset + total > data.size) return null
            return Page(headerType, serial, lacing.toList(), data.copyOfRange(offset, offset + total))
        }

        private fun payloadOf(page: ByteArray): ByteArray {
            val segmentCount = page[26].toInt() and 0xFF
            var payloadSize = 0
            for (i in 0 until segmentCount) payloadSize += page[27 + i].toInt() and 0xFF
            return page.copyOfRange(27 + segmentCount, 27 + segmentCount + payloadSize)
        }

        /** Rewrites the page sequence numbers of every Ogg page in [bytes], keeping audio intact. */
        private fun renumber(bytes: ByteArray, startSequence: Int): ByteArray {
            val out = bytes.copyOf()
            var offset = 0
            var sequence = startSequence
            while (offset + 27 <= out.size) {
                if (!(out[offset] == 'O'.code.toByte() && out[offset + 1] == 'g'.code.toByte() &&
                        out[offset + 2] == 'g'.code.toByte() && out[offset + 3] == 'S'.code.toByte())
                ) {
                    break
                }
                val segmentCount = out[offset + 26].toInt() and 0xFF
                var payloadSize = 0
                for (i in 0 until segmentCount) payloadSize += out[offset + 27 + i].toInt() and 0xFF
                val total = 27 + segmentCount + payloadSize
                if (offset + total > out.size) break
                for (i in 0 until 4) out[offset + 18 + i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
                val crc = pageCrc(out, offset, total)
                for (i in 0 until 4) out[offset + 22 + i] = ((crc ushr (8 * i)) and 0xFF).toByte()
                sequence++
                offset += total
            }
            return out
        }

        private fun serializePage(
            headerType: Int,
            serial: Int,
            sequence: Int,
            lacing: List<Int>,
            payload: ByteArray,
        ): ByteArray {
            val out = ByteArray(27 + lacing.size + payload.size)
            out[0] = 'O'.code.toByte()
            out[1] = 'g'.code.toByte()
            out[2] = 'g'.code.toByte()
            out[3] = 'S'.code.toByte()
            out[4] = 0
            out[5] = headerType.toByte()
            // granule position stays 0 for header pages
            for (i in 0 until 4) out[14 + i] = ((serial ushr (8 * i)) and 0xFF).toByte()
            for (i in 0 until 4) out[18 + i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
            out[26] = lacing.size.toByte()
            for (i in lacing.indices) out[27 + i] = lacing[i].toByte()
            System.arraycopy(payload, 0, out, 27 + lacing.size, payload.size)
            val crc = pageCrc(out, 0, out.size)
            for (i in 0 until 4) out[22 + i] = ((crc ushr (8 * i)) and 0xFF).toByte()
            return out
        }

        private fun pageCrc(bytes: ByteArray, offset: Int, length: Int): Int {
            var crc = 0
            for (i in 0 until length) {
                // The CRC field (bytes 22..25 of the page) is zero while the checksum is computed.
                val b = if (i in 22..25) 0 else bytes[offset + i].toInt() and 0xFF
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) and 0xFF) xor b]
            }
            return crc
        }

        private val CRC_TABLE = IntArray(256) { index ->
            var crc = index shl 24
            repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            crc
        }
    }

    // ---- MP4 / M4A ---------------------------------------------------------------------------

    private object Mp4Tags {
        private val MOOV = "moov".toByteArray(StandardCharsets.US_ASCII)
        private val MDAT = "mdat".toByteArray(StandardCharsets.US_ASCII)
        private val UDTA = "udta".toByteArray(StandardCharsets.US_ASCII)
        private val META = "meta".toByteArray(StandardCharsets.US_ASCII)
        private val ILST = "ilst".toByteArray(StandardCharsets.US_ASCII)
        private val STCO = "stco".toByteArray(StandardCharsets.US_ASCII)
        private val CO64 = "co64".toByteArray(StandardCharsets.US_ASCII)
        private val NAM = byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
        private val ART = byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte())
        private val ALB = byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte())
        private val COVR = "covr".toByteArray(StandardCharsets.US_ASCII)

        private class Atom(val type: ByteArray, val start: Int, val size: Int, val headerLen: Int) {
            val dataStart: Int get() = start + headerLen
            val end: Int get() = start + size
        }

        fun write(data: ByteArray, tag: TagData): ByteArray? {
            val top = parseAtoms(data, 0, data.size)
            val moovIndex = top.indexOfFirst { it.type.contentEquals(MOOV) }
            if (moovIndex < 0) return null
            val mdatIndex = top.indexOfFirst { it.type.contentEquals(MDAT) }
            val moov = top[moovIndex]
            val oldMoov = data.copyOfRange(moov.start, moov.end)

            val children = parseAtoms(oldMoov, moov.headerLen, oldMoov.size)
            val parts = ArrayList<ByteArray>()
            for (child in children) {
                if (child.type.contentEquals(UDTA)) continue // replaced by the freshly built one
                parts += oldMoov.copyOfRange(child.start, child.end)
            }
            parts += buildUdta(tag)
            val newMoov = atom(MOOV, concat(parts))
            val delta = newMoov.size - oldMoov.size

            // Only `moov`-before-`mdat` files shift their chunk data.
            if (delta != 0 && mdatIndex >= 0 && moovIndex < mdatIndex) {
                patchChunkOffsets(newMoov, delta)
            }

            val out = ByteArrayOutputStream(data.size + delta)
            for ((index, atom) in top.withIndex()) {
                if (index == moovIndex) out.write(newMoov) else out.write(data, atom.start, atom.size)
            }
            return out.toByteArray()
        }

        private fun buildUdta(tag: TagData): ByteArray {
            val items = ArrayList<ByteArray>()
            if (tag.title.isNotBlank()) items += ilstItem(NAM, tag.title.toByteArray(StandardCharsets.UTF_8), 1)
            if (tag.artist.isNotBlank()) items += ilstItem(ART, tag.artist.toByteArray(StandardCharsets.UTF_8), 1)
            if (tag.album.isNotBlank()) items += ilstItem(ALB, tag.album.toByteArray(StandardCharsets.UTF_8), 1)
            tag.artwork?.takeIf { it.isNotEmpty() }?.let { cover ->
                val isPng = cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
                items += ilstItem(COVR, cover, if (isPng) 14 else 13)
            }
            val ilst = atom(ILST, concat(items))
            val hdlr = atom(
                "hdlr",
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 'm'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
                    'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0),
            )
            val meta = atom(META, concat(byteArrayOf(0, 0, 0, 0), hdlr, ilst))
            return atom(UDTA, meta)
        }

        private fun ilstItem(type: ByteArray, payload: ByteArray, dataType: Int): ByteArray {
            val dataAtom = atom(
                "data",
                concat(
                    byteArrayOf(
                        0, 0, 0, dataType.toByte(), // version/flags: 1 = UTF-8, 13 = JPEG, 14 = PNG
                        0, 0, 0, 0,                 // locale
                    ),
                    payload,
                ),
            )
            return atom(type, dataAtom)
        }

        private fun patchChunkOffsets(buffer: ByteArray, delta: Int) {
            walkAtoms(buffer, 0, buffer.size) { atom ->
                when {
                    atom.type.contentEquals(STCO) -> {
                        val base = atom.dataStart
                        val count = readInt32(buffer, base + 4)
                        for (i in 0 until count) {
                            val at = base + 8 + i * 4
                            if (at + 4 > atom.end) break
                            writeInt32(buffer, at, readInt32(buffer, at) + delta)
                        }
                    }
                    atom.type.contentEquals(CO64) -> {
                        val base = atom.dataStart
                        val count = readInt32(buffer, base + 4)
                        for (i in 0 until count) {
                            val at = base + 8 + i * 8
                            if (at + 8 > atom.end) break
                            writeInt64(buffer, at, readInt64(buffer, at) + delta)
                        }
                    }
                }
            }
        }

        private val CONTAINERS = listOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex", "traf", "moof")
            .map { it.toByteArray(StandardCharsets.US_ASCII) }

        private fun walkAtoms(buffer: ByteArray, from: Int, to: Int, visit: (Atom) -> Unit) {
            for (atom in parseAtoms(buffer, from, to)) {
                visit(atom)
                if (CONTAINERS.any { atom.type.contentEquals(it) }) {
                    walkAtoms(buffer, atom.dataStart, atom.end, visit)
                }
            }
        }

        private fun parseAtoms(bytes: ByteArray, from: Int, to: Int): List<Atom> {
            val out = ArrayList<Atom>()
            var offset = from
            while (offset + 8 <= to) {
                var size = readInt32(bytes, offset)
                val type = bytes.copyOfRange(offset + 4, offset + 8)
                var headerLen = 8
                if (size == 1) {
                    if (offset + 16 > to) break
                    val large = readInt64(bytes, offset + 8)
                    headerLen = 16
                    size = if (large > Int.MAX_VALUE) break else large.toInt()
                } else if (size == 0) {
                    size = to - offset
                }
                if (size < headerLen || offset + size > to) break
                out += Atom(type, offset, size, headerLen)
                offset += size
            }
            return out
        }

        private fun atom(type: String, payload: ByteArray): ByteArray =
            atom(type.toByteArray(StandardCharsets.US_ASCII), payload)

        private fun atom(type: ByteArray, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            writeInt32(out, 0, 8 + payload.size)
            System.arraycopy(type, 0, out, 4, 4)
            System.arraycopy(payload, 0, out, 8, payload.size)
            return out
        }

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream(parts.sumOf { it.size })
            parts.forEach(out::write)
            return out.toByteArray()
        }

        private fun concat(vararg parts: ByteArray): ByteArray = concat(parts.toList())

        private fun readInt32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = ((value ushr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }

        private fun readInt64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return value
        }

        private fun writeInt64(bytes: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
        }
    }
}
