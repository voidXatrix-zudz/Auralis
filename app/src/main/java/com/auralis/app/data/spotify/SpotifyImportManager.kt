package com.auralis.app.data.spotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auralis.app.AppContainer
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.PlaylistYtEntryEntity
import com.auralis.app.data.db.SpotifyImportItemEntity
import com.auralis.app.data.db.YtMatchCacheEntity
import com.auralis.app.data.db.toDomain
import com.auralis.app.data.youtube.YtSong
import com.auralis.app.domain.model.Track
import com.auralis.app.logging.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Process-wide container handle so the import worker can reach the app graph offline. */
object SpotifyGraph {
    @Volatile
    var container: AppContainer? = null
}

/** Enqueues one CSV import as a WorkManager job. One CSV file becomes one local playlist. */
class SpotifyImportManager(private val context: Context) {
    fun enqueue(importId: Long, playlistId: Long, filePath: String, fileName: String) {
        val data = workDataOf(
            KEY_IMPORT_ID to importId,
            KEY_PLAYLIST_ID to playlistId,
            KEY_FILE_PATH to filePath,
            KEY_FILE_NAME to fileName,
        )
        val request = OneTimeWorkRequestBuilder<SpotifyImportWorker>().setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(importId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(importId: Long) = WorkManager.getInstance(context).cancelUniqueWork(workName(importId))

    companion object {
        const val KEY_IMPORT_ID = "importId"
        const val KEY_PLAYLIST_ID = "playlistId"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"
        const val CHANNEL_ID = "auralis_imports"
        const val NOTIFICATION_ID = 4301
        fun workName(importId: Long) = "auralis_spotify_import_$importId"
    }
}

/**
 * Matches every row of one Exportify CSV to YouTube Music (or to a local song) and fills a playlist.
 * Searches are throttled to three at a time; results are cached in Room so a re-import is instant.
 */
class SpotifyImportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = SpotifyGraph.container ?: return Result.failure()
        val importId = inputData.getLong(SpotifyImportManager.KEY_IMPORT_ID, -1L)
        val playlistId = inputData.getLong(SpotifyImportManager.KEY_PLAYLIST_ID, -1L)
        val filePath = inputData.getString(SpotifyImportManager.KEY_FILE_PATH)
        val fileName = inputData.getString(SpotifyImportManager.KEY_FILE_NAME).orEmpty()
        if (importId < 0 || playlistId < 0 || filePath.isNullOrBlank()) return Result.failure()

        val dao = container.database.spotifyDao()
        val playlistDao = container.database.playlistDao()
        return try {
            runCatching { setForeground(foreground(fileName, 0, 0)) }
            val text = withContext(Dispatchers.IO) { File(filePath).readText() }
            val rows = SpotifyCsv.parse(text)
            if (rows.isEmpty()) {
                dao.updateProgress(importId, STATE_DONE, 0, 0, 0, 0)
                return Result.success()
            }
            val localTracks = container.database.trackDao().getAllNow().map { it.toDomain() }
            val processed = AtomicInteger(0)
            val matched = AtomicInteger(0)
            val review = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val lastReport = AtomicInteger(0)

            val outcomes = arrayOfNulls<RowOutcome>(rows.size)
            coroutineScope {
                val semaphore = Semaphore(3)
                rows.mapIndexed { index, row ->
                    async(Dispatchers.IO) {
                        val outcome = matchRow(row, localTracks, dao, container, semaphore)
                        outcomes[index] = outcome
                        when (outcome.status) {
                            "MATCHED" -> matched.incrementAndGet()
                            "REVIEW" -> review.incrementAndGet()
                            else -> failed.incrementAndGet()
                        }
                        val done = processed.incrementAndGet()
                        val step = (rows.size / 100).coerceAtLeast(5)
                        if (done - lastReport.get() >= step || done == rows.size) {
                            lastReport.set(done)
                            runCatching {
                                setForeground(foreground(fileName, done, rows.size))
                                setProgress(workDataOf("done" to done, "total" to rows.size))
                            }
                            runCatching {
                                dao.updateProgress(importId, STATE_RUNNING, done, matched.get(), review.get(), failed.get())
                            }
                        }
                    }
                }.awaitAll()
            }

            // Persist items in source order and fill the playlist for confident matches.
            val items = ArrayList<SpotifyImportItemEntity>(rows.size)
            val localEntries = ArrayList<PlaylistEntryEntity>()
            val remoteEntries = ArrayList<PlaylistYtEntryEntity>()
            var localPosition = 0
            outcomes.forEachIndexed { index, outcome ->
                if (outcome == null) return@forEachIndexed
                items.add(
                    SpotifyImportItemEntity(
                        importId = importId,
                        position = index,
                        title = outcome.row.title,
                        artist = outcome.row.artist,
                        album = outcome.row.album,
                        durationMs = outcome.row.durationMs,
                        status = outcome.status,
                        matchedVideoId = outcome.matched?.videoId,
                        matchedTitle = outcome.matched?.title,
                        matchedArtist = outcome.matched?.artist,
                        candidates = outcome.candidatesJson,
                    ),
                )
                val local = outcome.localTrack
                val song = outcome.matched?.takeIf { outcome.localTrack == null }
                when {
                    local != null -> localEntries.add(PlaylistEntryEntity(playlistId = playlistId, trackId = local.id, position = localPosition++))
                    song != null -> remoteEntries.add(
                        PlaylistYtEntryEntity(
                            playlistId = playlistId,
                            videoId = song.videoId,
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            durationMs = song.durationMs,
                            thumbnailUrl = song.thumbnailUrl,
                            position = localPosition++,
                        ),
                    )
                }
            }
            dao.insertItems(items)
            if (localEntries.isNotEmpty()) playlistDao.insertEntries(localEntries)
            if (remoteEntries.isNotEmpty()) playlistDao.insertYtEntries(remoteEntries)
            playlistDao.touch(playlistId, System.currentTimeMillis())
            dao.updateProgress(importId, STATE_DONE, rows.size, matched.get(), review.get(), failed.get())
            runCatching { notifyDone(fileName, matched.get(), review.get(), failed.get()) }
            Result.success()
        } catch (error: Throwable) {
            AppLog.e("Spotify", "Import failed for $fileName", error)
            runCatching { dao.updateProgress(importId, STATE_DONE, 0, 0, 0, 0) }
            Result.failure()
        }
    }

    private suspend fun matchRow(
        row: SpotifyRow,
        localTracks: List<Track>,
        dao: com.auralis.app.data.db.SpotifyDao,
        container: AppContainer,
        semaphore: Semaphore,
    ): RowOutcome {
        val local = localTracks.firstOrNull { matchesLocal(row, it) }
        if (local != null) {
            return RowOutcome(row, local, null, emptyList(), "MATCHED", null)
        }
        val query = SpotifyMatcher.query(row)
        val key = query.lowercase()
        val cached = runCatching { dao.cachedMatch(key) }.getOrNull()
        val songs = when {
            cached != null -> listOfNotNull(cached.toSong())
            else -> semaphore.withPermit {
                runCatching { container.youtube.search(query) }.getOrDefault(emptyList())
            }.also { results ->
                val best = SpotifyMatcher.best(row, results)
                runCatching {
                    dao.putMatch(
                        YtMatchCacheEntity(
                            queryKey = key,
                            videoId = best?.song?.videoId,
                            title = best?.song?.title,
                            artist = best?.song?.artist,
                            album = best?.song?.album,
                            durationMs = best?.song?.durationMs ?: 0L,
                            thumbnailUrl = best?.song?.thumbnailUrl,
                            cachedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        val candidates = SpotifyMatcher.candidates(row, songs)
        val best = candidates.firstOrNull()
        val status = when {
            best == null -> "FAILED"
            best.confident -> "MATCHED"
            else -> "REVIEW"
        }
        val json = if (status == "MATCHED") null else encodeCandidates(candidates)
        val matchedSong = if (status == "MATCHED") best?.song else null
        return RowOutcome(row, null, matchedSong, emptyList(), status, json)
    }

    private fun matchesLocal(row: SpotifyRow, track: Track): Boolean {
        val a = "${row.title} ${row.artist}".lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val b = "${track.title} ${track.artist}".lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (a.isBlank() || b.isBlank()) return false
        if (!track.title.equals(row.title, ignoreCase = true)) {
            // Require a close title and an artist that appears in both.
            if (!b.contains(row.title.lowercase().trim())) return false
        }
        val artistTokens = row.artist.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        return artistTokens.isEmpty() || artistTokens.any { track.artist.lowercase().contains(it) }
    }

    private fun encodeCandidates(candidates: List<SpotifyMatcher.Candidate>): String {
        val array = JSONArray()
        candidates.forEach { candidate ->
            val song = candidate.song
            array.put(
                JSONObject().apply {
                    put("videoId", song.videoId)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("durationMs", song.durationMs)
                    put("thumbnailUrl", song.thumbnailUrl ?: "")
                    put("score", candidate.score)
                },
            )
        }
        return array.toString()
    }

    private fun YtMatchCacheEntity.toSong(): YtSong? {
        val id = videoId ?: return null
        return YtSong(id, title.orEmpty(), artist.orEmpty(), album.orEmpty(), durationMs, thumbnailUrl)
    }

    private data class RowOutcome(
        val row: SpotifyRow,
        val localTrack: Track?,
        val matched: YtSong?,
        val candidates: List<SpotifyMatcher.Candidate>,
        val status: String,
        val candidatesJson: String?,
    )

    private fun foreground(fileName: String, done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val progress = if (total > 0) (done * 100 / total) else 0
        val notification = NotificationCompat.Builder(applicationContext, SpotifyImportManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Importing Spotify playlist")
            .setContentText(if (total > 0) "$fileName · $done/$total" else fileName)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SpotifyImportManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SpotifyImportManager.NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(fileName: String, matched: Int, review: Int, failed: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, SpotifyImportManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Import complete")
            .setContentText("$fileName · $matched matched, $review to review, $failed failed")
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SpotifyImportManager.NOTIFICATION_ID + 1, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SpotifyImportManager.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(SpotifyImportManager.CHANNEL_ID, "Spotify import", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val STATE_RUNNING = "RUNNING"
        const val STATE_DONE = "DONE"
    }
}
