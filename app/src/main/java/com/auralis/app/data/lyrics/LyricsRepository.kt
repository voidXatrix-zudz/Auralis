package com.auralis.app.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.auralis.app.data.db.LyricsDao
import com.auralis.app.data.db.LyricsEntity
import com.auralis.app.data.db.SourceDao
import com.auralis.app.domain.lyrics.LrcParser
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsSource
import com.auralis.app.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves lyrics for a track using a strict priority:
 * 1. lyrics the user pasted or imported (never overwritten automatically),
 * 2. lyrics embedded in the audio file,
 * 3. a local sidecar `.lrc` file,
 * 4. cached LRCLIB lyrics, then a live LRCLIB lookup (metadata only, any song).
 *
 * Everything is cached in Room keyed by (trackId, source), so adding embedded lyrics cannot delete
 * a manual version. Discovery and network failures are non-fatal and never block playback.
 */
class LyricsRepository(
    private val context: Context,
    private val lyricsDao: LyricsDao,
    private val sourceDao: SourceDao,
    private val lrclib: LrclibClient = LrclibClient(),
) {
    /** Parsed lyrics kept in memory (current track + prefetched queue neighbours). */
    private val parsedCache = LruCache<String, Lyrics>(24)

    /** Non-blocking lookup used by the screen to show prefetched/cached lyrics instantly. */
    fun cached(trackId: String): Lyrics? = parsedCache.get(trackId)

    /** Warms the next queue item: resolve, parse off the main thread and cache the result. */
    suspend fun prefetch(track: Track) {
        runCatching { load(track) }
    }

    suspend fun load(
        track: Track,
        allowDiscovery: Boolean = true,
        allowNetwork: Boolean = true,
    ): Lyrics? = withContext(Dispatchers.IO) {
        parsedCache.get(track.id)?.let { return@withContext it }
        val stored = runCatching { lyricsDao.forTrack(track.id) }.getOrDefault(emptyList())

        // 1. Manual lyrics always win and are never replaced by automatic discovery.
        stored.firstOrNull { it.source == LyricsSource.USER.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }

        // 2. Embedded lyrics are preferred over sidecar files. A cached copy avoids re-reading the
        //    audio file on every visit, while a stale/missing cache falls back to the real tag.
        stored.firstOrNull { it.source == LyricsSource.EMBEDDED.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }
        if (allowDiscovery) {
            val embedded = EmbeddedLyrics.read(context, track)
            if (embedded != null) {
                cache(track, LyricsSource.EMBEDDED, embedded.text)
                return@withContext parseRaw(track.id, embedded.text, LyricsSource.EMBEDDED)
            }
        }

        // 3. Sidecar LRC.
        stored.firstOrNull { it.source == LyricsSource.LRC.name }?.let {
            return@withContext parseAndCache(track.id, it)
        }
        if (!allowDiscovery) return@withContext null
        val discovered = discover(track)
        if (discovered != null) {
            cache(track, LyricsSource.LRC, discovered.first)
            return@withContext parseRaw(track.id, discovered.first, LyricsSource.LRC)
        }

        // 4. LRCLIB: a cached row wins (a blank row means "checked, nothing found"), so the API is
        //    only contacted when this song has no lyrics anywhere yet.
        stored.firstOrNull { it.source == LyricsSource.LRCLIB.name }?.let { cached ->
            if (cached.raw.isBlank()) return@withContext null
            return@withContext parseAndCache(track.id, cached)
        }
        if (!allowNetwork) return@withContext null
        when (val online = runCatching { lrclib.lookup(track) }.getOrNull()) {
            // Request failed (offline, rate limit): retry on a later visit, do not cache a miss.
            null -> null
            LrclibResult.NotFound -> {
                cache(track, LyricsSource.LRCLIB, "")
                null
            }
            is LrclibResult.Found -> {
                val raw = online.synced ?: online.plain.orEmpty()
                if (raw.isBlank()) {
                    cache(track, LyricsSource.LRCLIB, "")
                    null
                } else {
                    cache(track, LyricsSource.LRCLIB, raw)
                    parseRaw(track.id, raw, LyricsSource.LRCLIB)
                }
            }
        }
    }

    suspend fun import(track: Track, uri: Uri): Lyrics = withContext(Dispatchers.IO) {
        val raw = readText(uri) ?: throw IllegalArgumentException("The selected file could not be read as text")
        if (raw.isBlank()) throw IllegalArgumentException("The selected file is empty")
        saveText(track, raw, LyricsSource.USER)
    }

    suspend fun saveText(track: Track, raw: String, source: LyricsSource = LyricsSource.USER): Lyrics =
        withContext(Dispatchers.IO) {
            require(raw.isNotBlank()) { "Lyrics are empty" }
            cache(track, source, raw)
            parseRaw(track.id, raw, source)
        }

    suspend fun remove(track: Track, source: LyricsSource): Boolean = withContext(Dispatchers.IO) {
        val rows = lyricsDao.forTrack(track.id)
        if (rows.none { it.source == source.name }) return@withContext false
        lyricsDao.delete(track.id, source.name)
        parsedCache.remove(track.id)
        true
    }

    /** Drops cached automatic lyrics so the next load re-reads the file / folder / LRCLIB. */
    suspend fun invalidateAutomatic(track: Track) = withContext(Dispatchers.IO) {
        runCatching { lyricsDao.delete(track.id, LyricsSource.EMBEDDED.name) }
        runCatching { lyricsDao.delete(track.id, LyricsSource.LRC.name) }
        runCatching { lyricsDao.delete(track.id, LyricsSource.LRCLIB.name) }
        parsedCache.remove(track.id)
    }

    private suspend fun cache(track: Track, source: LyricsSource, raw: String) {
        runCatching {
            lyricsDao.upsert(
                LyricsEntity(
                    trackId = track.id,
                    source = source.name,
                    raw = raw,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not cache ${source.name} lyrics for ${track.id}", it) }
    }

    private suspend fun discover(track: Track): Pair<String, String>? {
        val candidates = candidateNames(track)
        val preferred = candidates.map { it.lowercase() }.toSet()
        val source = runCatching { sourceDao.getById(track.sourceId) }.getOrNull()

        // SAF tree: walk the recorded relative path from the granted tree.
        source?.treeUri?.let { tree ->
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            if (root != null && root.exists()) {
                var directory: DocumentFile = root
                val parent = track.relativePath.substringBeforeLast('/', "")
                if (parent.isNotBlank()) {
                    for (segment in parent.split('/')) {
                        val child = directory.findFile(segment) ?: return@let
                        if (!child.isDirectory) return@let
                        directory = child
                    }
                }
                // Exact candidates first (cheap).
                for (name in candidates) {
                    val candidate = directory.findFile(name) ?: continue
                    if (candidate.isFile) readText(candidate.uri)?.let { return it to name }
                }
                // Fuzzy match against every .lrc in the folder using track metadata and LRC headers.
                val files = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
                    .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
                var best: Triple<Int, String, DocumentFile>? = null
                for (file in files) {
                    val name = file.name ?: continue
                    val raw = readText(file.uri) ?: continue
                    val score = score(name, raw, track, preferred)
                    if (best == null || score > best.first) best = Triple(score, name, file)
                    if (score >= 100) return raw to name
                    if (best != null && best.first >= 100) break
                }
                if (best != null && best.first >= MIN_MATCH_SCORE) {
                    val winner = best
                    val raw = readText(winner.third.uri)
                    if (raw != null) return raw to winner.second
                }
            }
        }

        // MediaStore: exact sibling lookup, then a fuzzy scan of the same relative folder.
        val directoryPath = track.relativePath.substringBeforeLast('/', "")
        if (directoryPath.isNotBlank()) {
            val relative = directoryPath.trim('/').plus("/")
            runCatching {
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND lower(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.lrc'",
                    arrayOf(relative),
                    null,
                )?.use { cursor ->
                    var best: Triple<Int, Long, String>? = null
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val uri = ContentUris.withAppendedId(collection, id)
                        val raw = readText(uri) ?: continue
                        val score = score(name, raw, track, preferred)
                        if (best == null || score > best.first) best = Triple(score, id, name)
                        if (score >= 100) return raw to name
                    }
                    if (best != null && best.first >= MIN_MATCH_SCORE) {
                        val winner = best
                        val uri = ContentUris.withAppendedId(collection, winner.second)
                        val raw = readText(uri)
                        if (raw != null) return raw to winner.third
                    }
                }
            }.onFailure { Log.d(TAG, "MediaStore lyric lookup unavailable for ${track.displayName}", it) }
        }

        // Last resort for devices exposing legacy storage paths.
        val legacyDirectory = File(
            android.os.Environment.getExternalStorageDirectory(),
            directoryPath.trim('/'),
        )
        if (legacyDirectory.isDirectory) {
            val files = legacyDirectory.listFiles { file -> file.isFile && file.name.endsWith(".lrc", true) }
                .orEmpty()
            val exact = candidates.firstNotNullOfOrNull { name -> files.firstOrNull { it.name.equals(name, true) } }
            if (exact != null && exact.length() <= MAX_LYRICS_BYTES) {
                runCatching { exact.readText() }.getOrNull()?.let { return it to exact.name }
            }
            var best: Pair<Int, File>? = null
            for (file in files) {
                if (file.length() > MAX_LYRICS_BYTES) continue
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                val score = score(file.name, raw, track, preferred)
                if (best == null || score > best.first) best = score to file
                if (score >= 100) return raw to file.name
            }
            if (best != null && best.first >= MIN_MATCH_SCORE) {
                runCatching { best.second.readText() }.getOrNull()?.let { return it to best.second.name }
            }
        }
        return null
    }

    private fun candidateNames(track: Track): List<String> {
        val fileBase = track.displayName.substringBeforeLast('.', track.displayName)
        val title = track.title.trim().ifBlank { fileBase }
        val artist = track.artist.trim()
        val names = linkedSetOf("$fileBase.lrc", "$fileBase.LRC")
        if (title.isNotBlank() && title != fileBase) names += "$title.lrc"
        if (artist.isNotBlank() && title.isNotBlank()) {
            names += "$artist - $title.lrc"
            names += "$title - $artist.lrc"
        }
        // A few tag-style variants used by downloaders.
        names += "${track.artist} - ${track.title}.lrc".replace("  ", " ")
        return names.filter { it.isNotBlank() && !it.startsWith(" - ") }
    }

    /**
     * Scores a sidecar file by filename and embedded `[ti:]`/`[ar:]`/`[al:]` header fields. 100 is an
     * exact title match; partial token matches are accepted above [MIN_MATCH_SCORE].
     */
    private fun score(name: String, raw: String, track: Track, preferred: Set<String>): Int {
        val fileBase = normalize(name.substringBeforeLast('.', name))
        if (fileBase in preferred) return 100
        val header = headerTags(raw)
        val title = normalize(track.title.ifBlank { track.displayName.substringBeforeLast('.') })
        val artist = normalize(track.artist)
        val album = normalize(track.album)
        val tagTitle = normalize(header["ti"].orEmpty())
        val tagArtist = normalize(header["ar"].orEmpty())
        val tagAlbum = normalize(header["al"].orEmpty())
        if (title.isNotBlank() && (fileBase == title || tagTitle == title)) {
            return if (artist.isBlank() || fileBase.contains(artist) || tagArtist == artist) 100 else 90
        }
        if (tagTitle.isNotBlank() && tagTitle == title) return 100
        var score = 0
        if (title.isNotBlank() && (fileBase.contains(title) || tagTitle.contains(title))) score += 60
        if (title.isNotBlank() && tagTitle.contains(title)) score += 50
        if (artist.isNotBlank() && (fileBase.contains(artist) || tagArtist == artist)) score += 30
        // Album/relative-path agreement breaks ties between similarly named sidecars.
        if (album.isNotBlank() && (tagAlbum == album || normalize(track.relativePath).contains(album))) score += 15
        val titleTokens = title.split(' ').filter { it.length > 2 }
        if (titleTokens.isNotEmpty()) {
            val hits = titleTokens.count { fileBase.contains(it) || tagTitle.contains(it) }
            score += (hits * 40) / titleTokens.size
        }
        return score
    }

    private fun headerTags(raw: String): Map<String, String> {
        val field = Regex("^\\[(ti|ar|al):\\s*(.*?)]$", RegexOption.IGNORE_CASE)
        val result = HashMap<String, String>(4)
        for (line in raw.lineSequence().take(40)) {
            val match = field.find(line.trim()) ?: continue
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return result
    }

    /** Lowercase, strip extension-like punctuation and collapse whitespace for comparison. */
    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[\\[\\](){}_.,'’]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val data = stream.readBounded(MAX_LYRICS_BYTES)
            val text = data.toString(Charsets.UTF_8)
            text.takeIf { it.isNotBlank() }
        }
    }.onFailure { Log.w(TAG, "Could not read lyrics from $uri", it) }.getOrNull()

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    private suspend fun parseAndCache(trackId: String, entity: LyricsEntity): Lyrics {
        val source = runCatching { LyricsSource.valueOf(entity.source) }.getOrDefault(LyricsSource.USER)
        return parseRaw(trackId, entity.raw, source)
    }

    /** LRC parsing is CPU work: always run it on Dispatchers.Default, never on the main thread. */
    private suspend fun parseRaw(trackId: String, raw: String, source: LyricsSource): Lyrics {
        val parsed = withContext(Dispatchers.Default) {
            // Malformed lyrics must degrade to plain text, never crash the screen.
            runCatching { LrcParser.parse(raw, source) }.getOrElse {
                Lyrics(source, raw, null, raw.trim().takeIf { text -> text.isNotBlank() })
            }
        }
        parsedCache.put(trackId, parsed)
        return parsed
    }

    private companion object {
        const val TAG = "AuralisLyrics"
        const val MAX_LYRICS_BYTES = 512 * 1024
        const val MIN_MATCH_SCORE = 50
    }
}
