package com.auralis.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.auralis.app.data.db.FavoriteEntity
import com.auralis.app.data.db.HistoryDao
import com.auralis.app.data.db.LyricsEntity
import com.auralis.app.data.db.PlayEventEntity
import com.auralis.app.data.db.PlayHistoryEntity
import com.auralis.app.data.db.PlaylistDao
import com.auralis.app.data.db.PlaylistEntity
import com.auralis.app.data.db.PlaylistEntryEntity
import com.auralis.app.data.db.TrackDao
import com.auralis.app.data.db.FavoriteDao
import com.auralis.app.data.db.LyricsDao
import com.auralis.app.data.db.PlayEventDao
import com.auralis.app.data.settings.SettingsRepository
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.domain.model.PlayerBackground
import com.auralis.app.domain.model.ReplayGainMode
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.domain.model.ThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupSummary(
    val playlists: Int,
    val favorites: Int,
    val history: Int,
    val lyrics: Int,
    val playEvents: Int,
)

/**
 * Versioned, SAF-based JSON backup. It stores user-created data only (settings, playlists,
 * favorites, history, lyrics and play events), never copies the music library itself, and skips
 * references to tracks that are not present on the restoring device.
 */
class BackupRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val lyricsDao: LyricsDao,
    private val playEventDao: PlayEventDao,
    private val settingsRepository: SettingsRepository,
) {
    fun suggestedFileName(): String =
        "AURALIS-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"

    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val existingTracks = trackDao.getAllNow().mapTo(HashSet()) { it.id }
        val playlists = playlistDao.getAll()
        val entries = playlistDao.getAllEntries().groupBy { it.playlistId }
        val favorites = favoriteDao.getAll().filter { it.trackId in existingTracks }
        val history = historyDao.getAll().filter { it.trackId in existingTracks }
        val lyrics = lyricsDao.getAll().filter { it.trackId in existingTracks }
        val events = playEventDao.getAll().filter { it.trackId in existingTracks }
        val settings = settingsRepository.settings.first()

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("settings", settingsToJson(settings))
        root.put("playlists", JSONArray().apply {
            for (playlist in playlists) {
                put(JSONObject().apply {
                    put("name", playlist.name)
                    put("createdAt", playlist.createdAt)
                    put("updatedAt", playlist.updatedAt)
                    put("tracks", JSONArray(entries[playlist.id].orEmpty().sortedBy { it.position }.map { it.trackId }))
                })
            }
        })
        root.put("favorites", JSONArray().apply {
            favorites.forEach { put(JSONObject().put("trackId", it.trackId).put("addedAt", it.addedAt)) }
        })
        root.put("history", JSONArray().apply {
            history.forEach {
                put(JSONObject().put("trackId", it.trackId).put("lastPlayedAt", it.lastPlayedAt).put("playCount", it.playCount))
            }
        })
        root.put("lyrics", JSONArray().apply {
            lyrics.forEach {
                put(JSONObject().put("trackId", it.trackId).put("source", it.source).put("raw", it.raw).put("updatedAt", it.updatedAt))
            }
        })
        root.put("playEvents", JSONArray().apply {
            events.forEach {
                put(JSONObject().put("trackId", it.trackId).put("playedAt", it.playedAt).put("durationMs", it.durationMs))
            }
        })

        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalArgumentException("The selected backup file could not be written")
        stream.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        BackupSummary(playlists.size, favorites.size, history.size, lyrics.size, events.size)
    }

    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_BACKUP_BYTES) throw IllegalArgumentException("That backup is too large")
            bytes.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("The backup file could not be read")
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("This is not a valid AURALIS backup") }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("This file is not an AURALIS backup")
        val version = root.optInt("version", 0)
        if (version !in 1..VERSION) throw IllegalArgumentException("Backup version $version is not supported")
        val existingTrackIds = trackDao.getAllNow().mapTo(HashSet()) { it.id }

        root.optJSONObject("settings")?.let { json ->
            runCatching {
                val imported = settingsFromJson(json)
                settingsRepository.update { current -> current.copy(
                    theme = imported.theme, accent = imported.accent, dynamicTheme = imported.dynamicTheme,
                    eqEnabled = imported.eqEnabled, eqPreset = imported.eqPreset, eqGains = imported.eqGains,
                    eqBandLevels = imported.eqBandLevels, eqBandCount = imported.eqBandCount,
                    preampDb = imported.preampDb, limiterEnabled = imported.limiterEnabled,
                    bassDb = imported.bassDb, bassBoostEnabled = imported.bassBoostEnabled,
                    virtualizerEnabled = imported.virtualizerEnabled, virtualizerStrength = imported.virtualizerStrength,
                    speed = imported.speed, balance = imported.balance, volume = imported.volume,
                    replayGain = imported.replayGain, lyricSizeSp = imported.lyricSizeSp,
                    lyricsAutoScroll = imported.lyricsAutoScroll, performanceProfile = imported.performanceProfile,
                    lyricsOffsetMs = imported.lyricsOffsetMs,
                    gestureSeekEnabled = imported.gestureSeekEnabled, gestureSwipeEnabled = imported.gestureSwipeEnabled,
                    doubleTapSeekSeconds = imported.doubleTapSeekSeconds, crossfadeSeconds = imported.crossfadeSeconds,
                    resumeOnOutputReconnect = imported.resumeOnOutputReconnect, carKeepScreenOn = imported.carKeepScreenOn,
                    themeStyle = imported.themeStyle,
                    dockGlassTransparency = imported.dockGlassTransparency, dockGlassBlur = imported.dockGlassBlur,
                    autoScanEnabled = imported.autoScanEnabled,
                    includeSubfolders = imported.includeSubfolders,
                    playerBackground = imported.playerBackground,
                ) }
            }.onFailure { Log.w(TAG, "Backup settings were incomplete", it) }
        }

        val existingPlaylists = playlistDao.getAll().associateBy { it.name.lowercase() }.toMutableMap()
        val existingEntries = playlistDao.getAllEntries().mapTo(HashSet()) { "${it.playlistId}:${it.trackId}" }
        var restoredPlaylists = 0
        root.optJSONArray("playlists")?.let { array ->
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val name = json.optString("name").trim().ifBlank { "Restored playlist" }
                val now = System.currentTimeMillis()
                val playlist = existingPlaylists[name.lowercase()] ?: run {
                    val id = playlistDao.insert(
                        PlaylistEntity(
                            name = name,
                            createdAt = json.optLong("createdAt", now),
                            updatedAt = now,
                        ),
                    )
                    PlaylistEntity(id = id, name = name, createdAt = json.optLong("createdAt", now), updatedAt = now)
                        .also { existingPlaylists[name.lowercase()] = it }
                }
                val tracks = json.optJSONArray("tracks") ?: JSONArray()
                var position = playlistDao.maxPosition(playlist.id) + 1
                for (trackIndex in 0 until tracks.length()) {
                    val trackId = tracks.optString(trackIndex)
                    if (trackId !in existingTrackIds) continue
                    if (existingEntries.add("${playlist.id}:$trackId")) {
                        playlistDao.insertEntries(listOf(PlaylistEntryEntity(playlistId = playlist.id, trackId = trackId, position = position)))
                        position += 1
                    }
                }
                playlistDao.touch(playlist.id, now)
                restoredPlaylists += 1
            }
        }

        var restoredFavorites = 0
        root.optJSONArray("favorites")?.let { array ->
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val trackId = json.optString("trackId")
                if (trackId !in existingTrackIds) continue
                favoriteDao.add(FavoriteEntity(trackId, json.optLong("addedAt", System.currentTimeMillis())))
                restoredFavorites += 1
            }
        }

        var restoredHistory = 0
        root.optJSONArray("history")?.let { array ->
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val trackId = json.optString("trackId")
                if (trackId !in existingTrackIds) continue
                val existing = historyDao.get(trackId)
                val incomingCount = json.optInt("playCount", 0).coerceAtLeast(0)
                val incomingLast = json.optLong("lastPlayedAt", 0L)
                historyDao.upsert(
                    PlayHistoryEntity(
                        trackId = trackId,
                        lastPlayedAt = maxOf(incomingLast, existing?.lastPlayedAt ?: 0L),
                        playCount = maxOf(incomingCount, existing?.playCount ?: 0),
                    ),
                )
                restoredHistory += 1
            }
        }

        var restoredLyrics = 0
        root.optJSONArray("lyrics")?.let { array ->
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val trackId = json.optString("trackId")
                val raw = json.optString("raw")
                if (trackId !in existingTrackIds || raw.isBlank()) continue
                lyricsDao.upsert(
                    LyricsEntity(
                        trackId = trackId,
                        source = json.optString("source", "USER"),
                        raw = raw,
                        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
                restoredLyrics += 1
            }
        }

        var restoredEvents = 0
        val existingEvents = playEventDao.getAll().mapTo(HashSet()) { "${it.trackId}:${it.playedAt}" }
        root.optJSONArray("playEvents")?.let { array ->
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val trackId = json.optString("trackId")
                if (trackId !in existingTrackIds) continue
                val playedAt = json.optLong("playedAt", 0L)
                val key = "$trackId:$playedAt"
                if (!existingEvents.add(key)) continue
                playEventDao.insert(
                    PlayEventEntity(
                        trackId = trackId,
                        playedAt = playedAt,
                        durationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L),
                    ),
                )
                restoredEvents += 1
            }
        }

        BackupSummary(restoredPlaylists, restoredFavorites, restoredHistory, restoredLyrics, restoredEvents)
    }

    private fun settingsToJson(settings: AppSettings): JSONObject = JSONObject().apply {
        put("theme", settings.theme.name)
        put("accent", settings.accent)
        put("dynamicTheme", settings.dynamicTheme)
        put("eqEnabled", settings.eqEnabled)
        put("eqPreset", settings.eqPreset)
        put("eqGains", JSONArray(settings.eqGains))
        put("eqBandLevels", JSONArray(settings.eqBandLevels))
        put("eqBandCount", settings.eqBandCount)
        put("preampDb", settings.preampDb.toDouble())
        put("limiterEnabled", settings.limiterEnabled)
        put("bassDb", settings.bassDb.toDouble())
        put("bassBoostEnabled", settings.bassBoostEnabled)
        put("virtualizerEnabled", settings.virtualizerEnabled)
        put("virtualizerStrength", settings.virtualizerStrength)
        put("speed", settings.speed.toDouble())
        put("balance", settings.balance.toDouble())
        put("volume", settings.volume.toDouble())
        put("replayGain", settings.replayGain.name)
        put("lyricSizeSp", settings.lyricSizeSp)
        put("lyricsAutoScroll", settings.lyricsAutoScroll)
        put("lyricsOffsetMs", settings.lyricsOffsetMs)
        put("performanceProfile", settings.performanceProfile.name)
        put("gestureSeekEnabled", settings.gestureSeekEnabled)
        put("gestureSwipeEnabled", settings.gestureSwipeEnabled)
        put("doubleTapSeekSeconds", settings.doubleTapSeekSeconds)
        put("crossfadeSeconds", settings.crossfadeSeconds)
        put("resumeOnOutputReconnect", settings.resumeOnOutputReconnect)
        put("carKeepScreenOn", settings.carKeepScreenOn)
        put("themeStyle", settings.themeStyle.name)
        put("dockGlassTransparency", settings.dockGlassTransparency)
        put("dockGlassBlur", settings.dockGlassBlur)
        put("autoScanEnabled", settings.autoScanEnabled)
        put("includeSubfolders", settings.includeSubfolders)
        put("playerBackground", settings.playerBackground.name)
    }

    private fun settingsFromJson(json: JSONObject): AppSettings {
        val defaults = AppSettings()
        val gains = json.optJSONArray("eqGains")?.let { array ->
            buildList { for (index in 0 until minOf(array.length(), 10)) add(array.optDouble(index, 0.0).toFloat()) }
        }?.takeIf { it.size == 10 } ?: defaults.eqGains
        return AppSettings(
            theme = json.optString("theme").let { name -> ThemeMode.entries.firstOrNull { it.name == name } ?: defaults.theme },
            accent = json.optString("accent", defaults.accent),
            dynamicTheme = json.optBoolean("dynamicTheme", defaults.dynamicTheme),
            eqEnabled = json.optBoolean("eqEnabled", defaults.eqEnabled),
            eqPreset = json.optString("eqPreset", defaults.eqPreset),
            eqGains = gains,
            eqBandLevels = json.optJSONArray("eqBandLevels")?.let { array ->
                buildList { for (index in 0 until array.length()) add(array.optInt(index, 0)) }
            } ?: defaults.eqBandLevels,
            eqBandCount = json.optInt("eqBandCount", defaults.eqBandCount).coerceIn(0, 32),
            preampDb = json.optDouble("preampDb", defaults.preampDb.toDouble()).toFloat().coerceIn(0f, 12f),
            limiterEnabled = json.optBoolean("limiterEnabled", defaults.limiterEnabled),
            bassDb = json.optDouble("bassDb", defaults.bassDb.toDouble()).toFloat(),
            bassBoostEnabled = json.optBoolean("bassBoostEnabled", defaults.bassBoostEnabled),
            virtualizerEnabled = json.optBoolean("virtualizerEnabled", defaults.virtualizerEnabled),
            virtualizerStrength = json.optInt("virtualizerStrength", defaults.virtualizerStrength),
            speed = json.optDouble("speed", defaults.speed.toDouble()).toFloat(),
            balance = json.optDouble("balance", defaults.balance.toDouble()).toFloat(),
            volume = json.optDouble("volume", defaults.volume.toDouble()).toFloat(),
            replayGain = json.optString("replayGain").let { name -> ReplayGainMode.entries.firstOrNull { it.name == name } ?: defaults.replayGain },
            lyricSizeSp = json.optInt("lyricSizeSp", defaults.lyricSizeSp),
            lyricsAutoScroll = json.optBoolean("lyricsAutoScroll", defaults.lyricsAutoScroll),
            lyricsOffsetMs = json.optInt("lyricsOffsetMs", defaults.lyricsOffsetMs).coerceIn(-5_000, 5_000),
            performanceProfile = json.optString("performanceProfile").let { name -> PerformanceMode.entries.firstOrNull { it.name == name } ?: defaults.performanceProfile },
            gestureSeekEnabled = json.optBoolean("gestureSeekEnabled", defaults.gestureSeekEnabled),
            gestureSwipeEnabled = json.optBoolean("gestureSwipeEnabled", defaults.gestureSwipeEnabled),
            doubleTapSeekSeconds = json.optInt("doubleTapSeekSeconds", defaults.doubleTapSeekSeconds),
            crossfadeSeconds = json.optInt("crossfadeSeconds", defaults.crossfadeSeconds),
            resumeOnOutputReconnect = json.optBoolean("resumeOnOutputReconnect", defaults.resumeOnOutputReconnect),
            carKeepScreenOn = json.optBoolean("carKeepScreenOn", defaults.carKeepScreenOn),
            themeStyle = json.optString("themeStyle").let { name ->
                ThemeStyle.entries.firstOrNull { it.name == name } ?: defaults.themeStyle
            },
            dockGlassTransparency = json.optInt("dockGlassTransparency", defaults.dockGlassTransparency).coerceIn(0, 100),
            dockGlassBlur = json.optInt("dockGlassBlur", defaults.dockGlassBlur).coerceIn(0, 100),
            autoScanEnabled = json.optBoolean("autoScanEnabled", defaults.autoScanEnabled),
            includeSubfolders = json.optBoolean("includeSubfolders", defaults.includeSubfolders),
            playerBackground = json.optString("playerBackground").let { name ->
                PlayerBackground.entries.firstOrNull { it.name == name } ?: defaults.playerBackground
            },
        )
    }

    private companion object {
        const val TAG = "AuralisBackup"
        const val FORMAT = "auralis-backup"
        const val VERSION = 1
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    }
}
