package com.auralis.app.data.youtube

import com.auralis.app.data.db.YouTubeRecentEntity
import com.auralis.app.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Retries missing metadata for streamed songs while they are on screen.
 *
 * One instance belongs to the owner that lives as long as the screen interest (the ViewModel).
 * [request] is de-duplicated per track id, so a recomposition or a second row for the same song can
 * never start a second lookup. A lookup runs immediately and is then retried after 1, 3, 6, 10 and
 * 10 seconds; the last gap lands on the 30 s deadline, after which the track is reported through
 * [onSettled] and never polled again.
 *
 * At most [CONCURRENCY] lookups run at once. [release] and [releaseAll] cancel outstanding work when
 * the song leaves the screen or the app goes to the background; a later [request] starts a fresh
 * window instead of resuming an old one.
 */
class MetadataFiller(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> YtSong?,
    private val onFilled: suspend (Track) -> Unit,
    private val onSettled: (String) -> Unit,
    /** Last-resort artwork lookup through the existing metadata providers; only for titled tracks. */
    private val fallbackArtwork: suspend (Track) -> String? = { null },
) {
    companion object {
        /** Total retry budget for one song. Nothing is polled after this. */
        const val DEADLINE_MS = 30_000L

        /** Simultaneous metadata lookups across every visible song. */
        const val CONCURRENCY = 3

        /** Gaps before each retry; the sum is exactly [DEADLINE_MS]. */
        val RETRY_GAPS_MS: List<Long> = listOf(1_000L, 3_000L, 6_000L, 10_000L, 10_000L)
    }

    private val gate = Semaphore(CONCURRENCY)
    private val jobs = LinkedHashMap<String, Job>()

    /** Starts filling [track] unless it already has complete metadata or is already in progress. */
    @Synchronized
    fun request(track: Track) {
        if (!track.needsMetadataFill) return
        val id = track.id
        if (jobs.containsKey(id)) return
        jobs[id] = scope.launch {
            try {
                fill(track)
            } finally {
                synchronized(this@MetadataFiller) { jobs.remove(id) }
            }
        }
    }

    /** Cancels one song's retries; the next [request] starts a new window. */
    @Synchronized
    fun release(trackId: String) {
        jobs.remove(trackId)?.cancel()
    }

    /** Cancels every retry, e.g. when the app is backgrounded. */
    @Synchronized
    fun releaseAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private suspend fun fill(initial: Track) {
        val videoId = initial.remoteId ?: return
        var track = initial
        var elapsedMs = 0L
        var retry = 0
        while (true) {
            val song = gate.withPermit { attempt { fetch(videoId) } }
            val artwork = artworkFor(track, song)
            val filled = track.filledWith(song, artwork)
            if (filled != track) {
                track = filled
                onFilled(track)
            }
            if (!track.needsMetadataFill) break
            if (retry >= RETRY_GAPS_MS.size) break
            val gap = RETRY_GAPS_MS[retry]
            if (elapsedMs + gap > DEADLINE_MS) break
            delay(gap)
            elapsedMs += gap
            retry++
        }
        onSettled(initial.id)
    }

    /**
     * Real square art when the current URL is missing or still the 4:3 video thumbnail. The song's
     * own thumbnail is never used for that (it is the same video still), so the existing metadata
     * providers are asked only once the track already has a searchable title and artist.
     */
    private suspend fun artworkFor(track: Track, song: YtSong?): String? {
        val current = track.artworkUrl
        if (!current.isNullOrBlank() && !isVideoThumbnailUrl(current)) return null
        song?.thumbnailUrl?.takeUnless(::isVideoThumbnailUrl)?.let { return it }
        val title = track.title
        if (title.isBlank() || title == track.remoteId || track.artist.isBlank()) return null
        return attempt { fallbackArtwork(track) }
    }

    /** Runs a lookup without letting a failed provider cancel the whole retry window. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}

/** True while a streamed row is still the data-free placeholder written before metadata arrived. */
val Track.needsMetadataFill: Boolean
    get() {
        if (!isRemote || remoteId.isNullOrBlank()) return false
        return title.isBlank() || title == remoteId || artist.isBlank()
    }

/**
 * Fills only what is missing. A good title, artist, album, duration or artwork is never replaced,
 * and a blank provider result never erases a value already stored.
 */
fun Track.filledWith(song: YtSong?, resolvedArtwork: String? = null): Track {
    val songTitle = song?.title?.trim().orEmpty()
    val songArtist = song?.artist?.trim().orEmpty()
        .takeUnless { it.equals("Unknown artist", ignoreCase = true) }
        .orEmpty()
    val songAlbum = song?.album?.trim().orEmpty()
    val missingTitle = title.isBlank() || title == remoteId
    val nextTitle = if (missingTitle && songTitle.isNotEmpty()) songTitle else title
    val nextArtist = artist.ifBlank { songArtist }
    val currentArtwork = artworkUrl
    val nextArtwork = when {
        resolvedArtwork.isNullOrBlank() -> currentArtwork
        currentArtwork.isNullOrBlank() || isVideoThumbnailUrl(currentArtwork) -> resolvedArtwork
        else -> currentArtwork
    }
    return copy(
        title = nextTitle,
        displayName = if (missingTitle && songTitle.isNotEmpty()) songTitle else displayName,
        artist = nextArtist,
        albumArtist = albumArtist.ifBlank { nextArtist },
        album = album.ifBlank { songAlbum },
        durationMs = durationMs.takeIf { it > 0L } ?: song?.durationMs?.takeIf { it > 0L } ?: durationMs,
        artworkUrl = nextArtwork,
    )
}

/** Mirrors a successful fill into the history row without touching fields it already has. */
fun YouTubeRecentEntity.filledWith(track: Track): YouTubeRecentEntity {
    val nextTitle = if (title.isBlank() || title == videoId) {
        track.title.takeUnless { it.isBlank() || it == videoId } ?: title
    } else {
        title
    }
    val nextArtwork = thumbnailUrl.takeUnless { it.isNullOrBlank() || isVideoThumbnailUrl(it) }
        ?: track.artworkUrl
        ?: thumbnailUrl
    return copy(
        title = nextTitle,
        artist = artist.ifBlank { track.artist },
        album = album.ifBlank { track.album },
        durationMs = durationMs.takeIf { it > 0L } ?: track.durationMs,
        thumbnailUrl = nextArtwork,
    )
}