package com.auralis.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.app.data.lyrics.LyricsProviderCatalog
import com.auralis.app.domain.audio.EqGainsCodec
import com.auralis.app.domain.audio.EqLevelsCodec
import com.auralis.app.domain.audio.EqPresets
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.ReplayGainMode
import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.domain.model.StreamSourceMode
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.logging.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Keeps the user's saved provider order but appends any provider added since the list was stored,
 * so new sources still appear in Settings. Their enabled state is untouched, so a new provider
 * stays off until the user turns it on.
 */
internal fun mergeProviderOrder(stored: List<String>): List<String> {
    val known = LyricsProviderCatalog.ALL.map { it.id }
    val kept = stored.filter { it in known }.distinct()
    return kept + known.filter { it !in kept }
}

private val LEGACY_DEFAULT_LYRICS_ORDER = listOf(
    "LrcRed", "BiniLyrics", "BetterLyrics", "BetterLyricsPortato", "LyricsPlus", "Unison",
    "KuGou", "LRCLIB", "YouTubeMusic", "YouTubeCaptions", "SimpMusic", "NetEase",
    "Musixmatch", "Genius", "PaxSenix", "PaxSenixSpotify", "PaxSenixMusixmatch", "LyricsOvh",
)

/**
 * One-time migration for the renamed "Dock opacity" control. The old "Dock transparency" direction is
 * inverted (transparency 30% == opacity 70%) and the result is clamped to the 0..100 range.
 */
internal fun dockTransparencyToOpacity(transparencyPercent: Int): Int =
    (100 - transparencyPercent).coerceIn(0, 100)

private object Keys {
    val theme = stringPreferencesKey("theme")
    val accent = stringPreferencesKey("accent")
    val dynamicTheme = booleanPreferencesKey("dynamic_theme")
    val eqEnabled = booleanPreferencesKey("eq_enabled")
    val eqPreset = stringPreferencesKey("eq_preset")
    val eqGains = stringPreferencesKey("eq_gains")
    val eqBandLevels = stringPreferencesKey("eq_band_levels")
    val eqBandCount = intPreferencesKey("eq_band_count")
    val preamp = floatPreferencesKey("preamp_db")
    val limiter = booleanPreferencesKey("limiter_enabled")
    val bass = floatPreferencesKey("bass_db")
    val bassEnabled = booleanPreferencesKey("bass_boost_enabled")
    val virtualizerEnabled = booleanPreferencesKey("virtualizer_enabled")
    val virtualizerStrength = intPreferencesKey("virtualizer_strength")
    val speed = floatPreferencesKey("speed")
    val balance = floatPreferencesKey("balance")
    val volume = floatPreferencesKey("volume")
    val replayGain = stringPreferencesKey("replay_gain")
    val lyricSize = intPreferencesKey("lyric_size_sp")
    val lyricsAutoScroll = booleanPreferencesKey("lyrics_auto_scroll")
    val lyricsOffset = intPreferencesKey("lyrics_offset_ms")
    val librarySort = stringPreferencesKey("library_sort")
    val libraryGridView = booleanPreferencesKey("library_grid_view")
    val performanceProfile = stringPreferencesKey("performance_profile")
    val gestureSeek = booleanPreferencesKey("gesture_seek")
    val gestureSwipe = booleanPreferencesKey("gesture_swipe")
    val doubleTapSeek = intPreferencesKey("double_tap_seek_seconds")
    val crossfade = intPreferencesKey("crossfade_seconds")
    val resumeOnReconnect = booleanPreferencesKey("resume_on_output_reconnect")
    val carKeepScreenOn = booleanPreferencesKey("car_keep_screen_on")
    val themeStyle = stringPreferencesKey("theme_style")
    val themeStyleUserSelected = booleanPreferencesKey("theme_style_user_selected")
    val dockGlassOpacity = intPreferencesKey("dock_glass_opacity")
    /** Legacy pre-rename key ("Dock transparency"). Read once for migration, then removed on write. */
    val dockGlassTransparencyLegacy = intPreferencesKey("dock_glass_transparency")
    /** Removed control ("Blur strength"): the key is deleted on the next write and never read. */
    val dockGlassBlurLegacy = intPreferencesKey("dock_glass_blur")
    val glassClarity = intPreferencesKey("glass_clarity")
    val glassOutlines = booleanPreferencesKey("glass_outlines")
    val autoScan = booleanPreferencesKey("auto_scan_enabled")
    val includeSubfolders = booleanPreferencesKey("include_subfolders")
    val playerBackground = stringPreferencesKey("player_background")
    val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    val skippedUpdateTag = stringPreferencesKey("skipped_update_tag")
    val youtubeIntegration = booleanPreferencesKey("youtube_integration")
    val youtubePersonalizedHome = booleanPreferencesKey("youtube_personalized_home")
    val youtubeConnectPromptSnoozeUntil = longPreferencesKey("youtube_connect_prompt_snooze_until")
    val youtubeSyncHistory = booleanPreferencesKey("youtube_sync_history")
    val youtubeSyncLikes = booleanPreferencesKey("youtube_sync_likes")
    val youtubeSyncPlaylists = booleanPreferencesKey("youtube_sync_playlists")
    val youtubeSongsOnly = booleanPreferencesKey("youtube_songs_only")
    val liveSyncEnabled = booleanPreferencesKey("live_sync_enabled")
    val liveSyncLastSyncedAt = longPreferencesKey("live_sync_last_synced_at")
    val lyricsProviderOrder = stringPreferencesKey("lyrics_provider_order")
    val lyricsProvidersEnabled = stringPreferencesKey("lyrics_providers_enabled")
    val lyricsProviderDefaultsMigrated = booleanPreferencesKey("lyrics_provider_defaults_migrated")
    val metadataProvidersEnabled = stringPreferencesKey("metadata_providers_enabled")
    val preferSyncedLyrics = booleanPreferencesKey("prefer_synced_lyrics")
    val overwriteExistingTags = booleanPreferencesKey("overwrite_existing_tags")
    val fetchArtistPhotos = booleanPreferencesKey("fetch_artist_photos")
    val useDeezerForArtistPhotos = booleanPreferencesKey("use_deezer_artist_photos")
    val lastFmApiKey = stringPreferencesKey("lastfm_api_key_enc")
    val betterLyricsApiKey = stringPreferencesKey("betterlyrics_api_key_enc")
    val musixmatchToken = stringPreferencesKey("musixmatch_token_enc")
    val paxsenixApiKey = stringPreferencesKey("paxsenix_api_key_enc")
    val geniusApiKey = stringPreferencesKey("genius_api_key_enc")
    val wifiStreamQuality = intPreferencesKey("wifi_stream_quality")
    val mobileStreamQuality = intPreferencesKey("mobile_stream_quality")
    val dataSaverEnabled = booleanPreferencesKey("data_saver_enabled")
    val streamSource = stringPreferencesKey("stream_source")
    val youtubeJsEnabled = booleanPreferencesKey("youtube_js_enabled")
    val reduceBlurAndEffects = booleanPreferencesKey("reduce_blur_effects")
    val downloadLyricsWithSongs = booleanPreferencesKey("download_lyrics_with_songs")
    val cacheSizeMb = intPreferencesKey("cache_size_mb")
    /** First-run onboarding: true once the welcome screen has been dismissed, so it never returns. */
    val welcomeSeen = booleanPreferencesKey("welcome_seen")
    // Part M: rolling stream-resolver stats (latency + success/failure counts).
    val resolverNpAvg = longPreferencesKey("resolver_newpipe_avg_ms")
    val resolverNpSuccess = intPreferencesKey("resolver_newpipe_success")
    val resolverNpFailure = intPreferencesKey("resolver_newpipe_failure")
    val resolverItAvg = longPreferencesKey("resolver_innertube_avg_ms")
    val resolverItSuccess = intPreferencesKey("resolver_innertube_success")
    val resolverItFailure = intPreferencesKey("resolver_innertube_failure")
}

/**
 * Rolling success/latency record for one stream resolver (Part M). The recent average is bounded by
 * [MAX_SAMPLES] so a device that gets faster (or slower) over time adapts. Persisted in DataStore.
 */
data class ResolverStat(
    val avgMs: Long = 0L,
    val success: Int = 0,
    val failure: Int = 0,
) {
    val total: Int get() = success + failure

    /** A clear success-rate win beats latency; otherwise the lower average latency wins. */
    fun beats(other: ResolverStat): Boolean {
        val mine = if (total == 0) DEFAULT_RATE else success.toDouble() / total
        val theirs = if (other.total == 0) DEFAULT_RATE else other.success.toDouble() / other.total
        if (kotlin.math.abs(mine - theirs) > RATE_MARGIN) return mine > theirs
        val myLatency = if (success == 0) NEUTRAL_LATENCY_MS else avgMs
        val theirLatency = if (other.success == 0) NEUTRAL_LATENCY_MS else other.avgMs
        return myLatency < theirLatency
    }

    /** Folds one resolution outcome into the rolling average/counters. */
    fun updated(latencyMs: Long, succeeded: Boolean): ResolverStat {
        val ok = success + if (succeeded) 1 else 0
        val bad = failure + if (succeeded) 0 else 1
        val n = (ok + bad).coerceAtMost(MAX_SAMPLES)
        val newAvg = if (succeeded) {
            if (n <= 1) latencyMs else (avgMs * (n - 1) + latencyMs) / n
        } else {
            avgMs
        }
        return copy(avgMs = newAvg, success = ok, failure = bad)
    }

    companion object {
        const val MAX_SAMPLES = 20
        const val DEFAULT_RATE = 0.5
        const val NEUTRAL_LATENCY_MS = 4_000L
        const val RATE_MARGIN = 0.1
    }
}

/** Persists [AppSettings] in Preferences DataStore. Unknown or corrupt values fall back to defaults. */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // CorruptionException is an IOException, but a type change can also raise
            // ClassCastException; either way fall back to defaults rather than crashing startup.
            AppLog.w("Settings", "Couldn't read saved settings (${error.javaClass.simpleName}); using defaults")
            emit(emptyPreferences())
        }
        .map { prefs ->
            runCatching { prefs.toSettings() }.getOrElse { error ->
                AppLog.w("Settings", "Settings parse failed (${error.javaClass.simpleName}); using defaults")
                AppSettings()
            }
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            if (updated.themeStyle != current.themeStyle) prefs[Keys.themeStyleUserSelected] = true
            // Persist the one-time upgrade default merge. Later explicit deselections are preserved.
            prefs[Keys.lyricsProviderDefaultsMigrated] = true
            prefs.write(updated)
        }
    }

    /** Reads one resolver's persisted rolling stats (Part M). */
    suspend fun resolverStat(newpipe: Boolean): ResolverStat = runCatching {
        val prefs = context.settingsStore.data.first()
        if (newpipe) {
            ResolverStat(
                avgMs = prefs[Keys.resolverNpAvg] ?: 0L,
                success = prefs[Keys.resolverNpSuccess] ?: 0,
                failure = prefs[Keys.resolverNpFailure] ?: 0,
            )
        } else {
            ResolverStat(
                avgMs = prefs[Keys.resolverItAvg] ?: 0L,
                success = prefs[Keys.resolverItSuccess] ?: 0,
                failure = prefs[Keys.resolverItFailure] ?: 0,
            )
        }
    }.getOrDefault(ResolverStat())

    /** Persists one resolution outcome for the resolver race (Part M). */
    suspend fun recordResolverResult(newpipe: Boolean, latencyMs: Long, succeeded: Boolean) {
        runCatching {
            context.settingsStore.edit { prefs ->
                val avgKey = if (newpipe) Keys.resolverNpAvg else Keys.resolverItAvg
                val okKey = if (newpipe) Keys.resolverNpSuccess else Keys.resolverItSuccess
                val failKey = if (newpipe) Keys.resolverNpFailure else Keys.resolverItFailure
                val current = ResolverStat(
                    avgMs = prefs[avgKey] ?: 0L,
                    success = prefs[okKey] ?: 0,
                    failure = prefs[failKey] ?: 0,
                )
                val next = current.updated(latencyMs, succeeded)
                prefs[avgKey] = next.avgMs
                prefs[okKey] = next.success
                prefs[failKey] = next.failure
            }
        }.onFailure { AppLog.w("Settings", "Couldn't persist resolver stats (${it.javaClass.simpleName})") }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val preset = this[Keys.eqPreset] ?: d.eqPreset
        return AppSettings(
            theme = enumOr(this[Keys.theme], d.theme),
            accent = this[Keys.accent] ?: d.accent,
            dynamicTheme = this[Keys.dynamicTheme] ?: d.dynamicTheme,
            eqEnabled = this[Keys.eqEnabled] ?: d.eqEnabled,
            eqPreset = preset,
            eqGains = EqGainsCodec.decode(this[Keys.eqGains], EqPresets.gains(preset)),
            eqBandLevels = EqLevelsCodec.decode(this[Keys.eqBandLevels]),
            eqBandCount = (this[Keys.eqBandCount] ?: d.eqBandCount).coerceIn(0, 32),
            preampDb = (this[Keys.preamp] ?: d.preampDb).coerceIn(0f, 12f),
            limiterEnabled = this[Keys.limiter] ?: d.limiterEnabled,
            bassDb = (this[Keys.bass] ?: d.bassDb).coerceIn(0f, 12f),
            bassBoostEnabled = this[Keys.bassEnabled] ?: d.bassBoostEnabled,
            virtualizerEnabled = this[Keys.virtualizerEnabled] ?: d.virtualizerEnabled,
            virtualizerStrength = (this[Keys.virtualizerStrength] ?: d.virtualizerStrength).coerceIn(0, 1000),
            speed = (this[Keys.speed] ?: d.speed).coerceIn(0.5f, 2f),
            balance = (this[Keys.balance] ?: d.balance).coerceIn(-1f, 1f),
            volume = (this[Keys.volume] ?: d.volume).coerceIn(0f, 1f),
            replayGain = enumOr(this[Keys.replayGain], d.replayGain),
            lyricSizeSp = (this[Keys.lyricSize] ?: d.lyricSizeSp).coerceIn(14, 40),
            lyricsAutoScroll = this[Keys.lyricsAutoScroll] ?: d.lyricsAutoScroll,
            lyricsOffsetMs = (this[Keys.lyricsOffset] ?: d.lyricsOffsetMs).coerceIn(-5_000, 5_000),
            librarySort = this[Keys.librarySort] ?: d.librarySort,
            libraryGridView = this[Keys.libraryGridView] ?: d.libraryGridView,
            performanceProfile = enumOr(this[Keys.performanceProfile], d.performanceProfile),
            gestureSeekEnabled = this[Keys.gestureSeek] ?: d.gestureSeekEnabled,
            gestureSwipeEnabled = this[Keys.gestureSwipe] ?: d.gestureSwipeEnabled,
            doubleTapSeekSeconds = (this[Keys.doubleTapSeek] ?: d.doubleTapSeekSeconds).coerceIn(5, 60),
            crossfadeSeconds = (this[Keys.crossfade] ?: d.crossfadeSeconds).coerceIn(0, 12),
            resumeOnOutputReconnect = this[Keys.resumeOnReconnect] ?: d.resumeOnOutputReconnect,
            carKeepScreenOn = this[Keys.carKeepScreenOn] ?: d.carKeepScreenOn,
            themeStyle = if (this[Keys.themeStyleUserSelected] == true) {
                enumOr(this[Keys.themeStyle], d.themeStyle)
            } else {
                d.themeStyle
            },
            dockGlassOpacity = (
                this[Keys.dockGlassOpacity]
                    ?: this[Keys.dockGlassTransparencyLegacy]?.let { dockTransparencyToOpacity(it) }
                    ?: d.dockGlassOpacity
                ).coerceIn(0, 100),
            glassClarity = (this[Keys.glassClarity] ?: d.glassClarity).coerceIn(0, 100),
            glassOutlines = this[Keys.glassOutlines] ?: d.glassOutlines,
            autoScanEnabled = this[Keys.autoScan] ?: d.autoScanEnabled,
            includeSubfolders = this[Keys.includeSubfolders] ?: d.includeSubfolders,
            playerBackground = enumOr(this[Keys.playerBackground], d.playerBackground),
            lastUpdateCheckAt = (this[Keys.lastUpdateCheckAt] ?: d.lastUpdateCheckAt).coerceAtLeast(0L),
            skippedUpdateTag = this[Keys.skippedUpdateTag] ?: d.skippedUpdateTag,
            youtubeIntegration = this[Keys.youtubeIntegration] ?: d.youtubeIntegration,
            personalizedHome = this[Keys.youtubePersonalizedHome] ?: d.personalizedHome,
            youtubeConnectPromptSnoozeUntil = this[Keys.youtubeConnectPromptSnoozeUntil] ?: d.youtubeConnectPromptSnoozeUntil,
            youtubeSyncHistory = this[Keys.youtubeSyncHistory] ?: d.youtubeSyncHistory,
            youtubeSyncLikes = this[Keys.youtubeSyncLikes] ?: d.youtubeSyncLikes,
            youtubeSyncPlaylists = this[Keys.youtubeSyncPlaylists] ?: d.youtubeSyncPlaylists,
            youtubeSongsOnly = this[Keys.youtubeSongsOnly] ?: d.youtubeSongsOnly,
            liveSyncEnabled = this[Keys.liveSyncEnabled] ?: d.liveSyncEnabled,
            liveSyncLastSyncedAt = (this[Keys.liveSyncLastSyncedAt] ?: d.liveSyncLastSyncedAt).coerceAtLeast(0L),
            lyricsProviderOrder = decodeList(this[Keys.lyricsProviderOrder], d.lyricsProviderOrder)
                .let { order -> if (order == LEGACY_DEFAULT_LYRICS_ORDER) d.lyricsProviderOrder else order }
                .let(::mergeLyricsProviderOrder),
            lyricsProvidersEnabled = decodeSet(this[Keys.lyricsProvidersEnabled], d.lyricsProvidersEnabled).let { enabled ->
                if (this[Keys.lyricsProviderDefaultsMigrated] == true) enabled else enabled + d.lyricsProvidersEnabled
            },
            metadataProvidersEnabled = decodeSet(this[Keys.metadataProvidersEnabled], d.metadataProvidersEnabled),
            preferSyncedLyrics = this[Keys.preferSyncedLyrics] ?: d.preferSyncedLyrics,
            betterLyricsApiKey = decrypt(this[Keys.betterLyricsApiKey]),
            musixmatchToken = decrypt(this[Keys.musixmatchToken]),
            paxsenixApiKey = decrypt(this[Keys.paxsenixApiKey]),
            geniusApiKey = decrypt(this[Keys.geniusApiKey]),
            overwriteExistingTags = this[Keys.overwriteExistingTags] ?: d.overwriteExistingTags,
            fetchArtistPhotos = this[Keys.fetchArtistPhotos] ?: d.fetchArtistPhotos,
            useDeezerForArtistPhotos = this[Keys.useDeezerForArtistPhotos] ?: d.useDeezerForArtistPhotos,
            lastFmApiKey = decrypt(this[Keys.lastFmApiKey]),
            wifiStreamQuality = StreamQuality.fromIndex(this[Keys.wifiStreamQuality] ?: d.wifiStreamQuality.ordinal),
            mobileStreamQuality = StreamQuality.fromIndex(this[Keys.mobileStreamQuality] ?: d.mobileStreamQuality.ordinal),
            dataSaverEnabled = this[Keys.dataSaverEnabled] ?: d.dataSaverEnabled,
            streamSource = this[Keys.streamSource]?.let { runCatching { StreamSourceMode.valueOf(it) }.getOrNull() } ?: d.streamSource,
            youtubeJsEnabled = this[Keys.youtubeJsEnabled] ?: d.youtubeJsEnabled,
            reduceBlurAndEffects = this[Keys.reduceBlurAndEffects] ?: d.reduceBlurAndEffects,
            downloadLyricsWithSongs = this[Keys.downloadLyricsWithSongs] ?: d.downloadLyricsWithSongs,
            cacheSizeMb = this[Keys.cacheSizeMb] ?: d.cacheSizeMb,
            welcomeSeen = this[Keys.welcomeSeen] ?: d.welcomeSeen,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[Keys.theme] = s.theme.name
        this[Keys.accent] = s.accent
        this[Keys.dynamicTheme] = s.dynamicTheme
        this[Keys.eqEnabled] = s.eqEnabled
        this[Keys.eqPreset] = s.eqPreset
        this[Keys.eqGains] = EqGainsCodec.encode(s.eqGains)
        this[Keys.eqBandLevels] = EqLevelsCodec.encode(s.eqBandLevels)
        this[Keys.eqBandCount] = s.eqBandCount
        this[Keys.preamp] = s.preampDb
        this[Keys.limiter] = s.limiterEnabled
        this[Keys.bass] = s.bassDb
        this[Keys.bassEnabled] = s.bassBoostEnabled
        this[Keys.virtualizerEnabled] = s.virtualizerEnabled
        this[Keys.virtualizerStrength] = s.virtualizerStrength
        this[Keys.speed] = s.speed
        this[Keys.balance] = s.balance
        this[Keys.volume] = s.volume
        this[Keys.replayGain] = s.replayGain.name
        this[Keys.lyricSize] = s.lyricSizeSp
        this[Keys.lyricsAutoScroll] = s.lyricsAutoScroll
        this[Keys.lyricsOffset] = s.lyricsOffsetMs
        this[Keys.librarySort] = s.librarySort
        this[Keys.libraryGridView] = s.libraryGridView
        this[Keys.performanceProfile] = s.performanceProfile.name
        this[Keys.gestureSeek] = s.gestureSeekEnabled
        this[Keys.gestureSwipe] = s.gestureSwipeEnabled
        this[Keys.doubleTapSeek] = s.doubleTapSeekSeconds
        this[Keys.crossfade] = s.crossfadeSeconds
        this[Keys.resumeOnReconnect] = s.resumeOnOutputReconnect
        this[Keys.carKeepScreenOn] = s.carKeepScreenOn
        this[Keys.themeStyle] = s.themeStyle.name
        this[Keys.dockGlassOpacity] = s.dockGlassOpacity
        this.remove(Keys.dockGlassTransparencyLegacy)
        this.remove(Keys.dockGlassBlurLegacy)
        this[Keys.glassClarity] = s.glassClarity
        this[Keys.glassOutlines] = s.glassOutlines
        this[Keys.autoScan] = s.autoScanEnabled
        this[Keys.includeSubfolders] = s.includeSubfolders
        this[Keys.playerBackground] = s.playerBackground.name
        this[Keys.lastUpdateCheckAt] = s.lastUpdateCheckAt
        this[Keys.skippedUpdateTag] = s.skippedUpdateTag
        this[Keys.youtubeIntegration] = s.youtubeIntegration
        this[Keys.youtubePersonalizedHome] = s.personalizedHome
        this[Keys.youtubeConnectPromptSnoozeUntil] = s.youtubeConnectPromptSnoozeUntil
        this[Keys.youtubeSyncHistory] = s.youtubeSyncHistory
        this[Keys.youtubeSyncLikes] = s.youtubeSyncLikes
        this[Keys.youtubeSyncPlaylists] = s.youtubeSyncPlaylists
        this[Keys.youtubeSongsOnly] = s.youtubeSongsOnly
        this[Keys.liveSyncEnabled] = s.liveSyncEnabled
        this[Keys.liveSyncLastSyncedAt] = s.liveSyncLastSyncedAt
        this[Keys.lyricsProviderOrder] = s.lyricsProviderOrder.joinToString(",")
        this[Keys.lyricsProvidersEnabled] = s.lyricsProvidersEnabled.joinToString(",")
        this[Keys.metadataProvidersEnabled] = s.metadataProvidersEnabled.joinToString(",")
        this[Keys.preferSyncedLyrics] = s.preferSyncedLyrics
        this[Keys.betterLyricsApiKey] = encrypt(s.betterLyricsApiKey)
        this[Keys.musixmatchToken] = encrypt(s.musixmatchToken)
        this[Keys.paxsenixApiKey] = encrypt(s.paxsenixApiKey)
        this[Keys.geniusApiKey] = encrypt(s.geniusApiKey)
        this[Keys.overwriteExistingTags] = s.overwriteExistingTags
        this[Keys.fetchArtistPhotos] = s.fetchArtistPhotos
        this[Keys.useDeezerForArtistPhotos] = s.useDeezerForArtistPhotos
        this[Keys.lastFmApiKey] = encrypt(s.lastFmApiKey)
        this[Keys.wifiStreamQuality] = s.wifiStreamQuality.ordinal
        this[Keys.mobileStreamQuality] = s.mobileStreamQuality.ordinal
        this[Keys.dataSaverEnabled] = s.dataSaverEnabled
        this[Keys.streamSource] = s.streamSource.name
        this[Keys.youtubeJsEnabled] = s.youtubeJsEnabled
        this[Keys.reduceBlurAndEffects] = s.reduceBlurAndEffects
        this[Keys.downloadLyricsWithSongs] = s.downloadLyricsWithSongs
        this[Keys.cacheSizeMb] = s.cacheSizeMb
        this[Keys.welcomeSeen] = s.welcomeSeen
    }

    /**
     * Keeps the user's saved provider order but appends any provider added since the list was
     * stored, so new sources still appear in Settings. Their enabled state is untouched, so a new
     * provider stays off until the user turns it on.
     */
    private fun mergeLyricsProviderOrder(stored: List<String>): List<String> = mergeProviderOrder(stored)

    private fun decodeList(raw: String?, default: List<String>): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: default

    private fun decodeSet(raw: String?, default: Set<String>): Set<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: default

    /** Encrypts/decrypts the Last.fm key through the Android Keystore; a failure degrades to plaintext-free "". */
    private fun encrypt(value: String): String =
        if (value.isBlank()) "" else runCatching { SecretStore.encrypt(context, value) }.getOrDefault("")

    private fun decrypt(value: String?): String =
        if (value.isNullOrBlank()) "" else runCatching { SecretStore.decrypt(context, value) }.getOrDefault("")

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
