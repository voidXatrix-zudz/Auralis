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

        /** Consecutive identical non-null answers after which retrying cannot add anything. */
        const val STAGNANT_LIMIT = 2
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
        var stagnant = 0
        while (true) {
            // The fetch and the artwork upgrade share one permit: both are network lookups and both
            // count toward the concurrency cap, but the permit is never held across a delay.
            var answered = false
            val next = gate.withPermit {
                val song = attempt { fetch(videoId) }
                answered = song != null
                val merged = track.filledWith(song)
                val artwork = artworkFor(merged)
                if (artwork != null) merged.copy(artworkUrl = artwork) else merged
            }
            if (next != track) {
                track = next
                stagnant = 0
                onFilled(track)
            } else if (answered) {
                stagnant++
            } else {
                stagnant = 0
            }
            if (!track.needsMetadataFill) break
            // A provider that keeps answering with the same incomplete song will not improve by being
            // asked again; stop after a couple of identical successful answers instead of burning the
            // whole retry window on it.
            if (stagnant >= STAGNANT_LIMIT) break
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
     * Asks the existing square-art resolver once the row has a searchable title, artist and real
     * artwork is still missing (blank or the 4:3 video still). A cover that already loaded is never
     * replaced, and a row without a usable title/artist is never searched.
     */
    private suspend fun artworkFor(track: Track): String? {
        val current = track.artworkUrl
        if (!current.isNullOrBlank() && !isVideoThumbnailUrl(current)) return null
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
fun Track.filledWith(song: YtSong?): Track {
    val songTitle = song?.title?.trim().orEmpty()
    val songArtist = song?.artist?.trim().orEmpty()
        .takeUnless { it.equals("Unknown artist", ignoreCase = true) }
        .orEmpty()
    val songAlbum = song?.album?.trim().orEmpty()
    val missingTitle = title.isBlank() || title == remoteId
    val nextTitle = if (missingTitle && songTitle.isNotEmpty()) songTitle else title
    val nextArtist = artist.ifBlank { songArtist }
    // A provider that returns a real square cover replaces a blank or 4:3 still; the video's own
    // thumbnail is not "art" and is left for the square-art resolver instead.
    val songArtwork = song?.thumbnailUrl?.takeUnless { it.isNullOrBlank() || isVideoThumbnailUrl(it) }
    val currentArtwork = artworkUrl
    val nextArtwork = songArtwork?.takeIf {
        currentArtwork.isNullOrBlank() || isVideoThumbnailUrl(currentArtwork)
    } ?: currentArtwork
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