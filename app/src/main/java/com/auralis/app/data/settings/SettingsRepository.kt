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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
    val performanceProfile = stringPreferencesKey("performance_profile")
    val gestureSeek = booleanPreferencesKey("gesture_seek")
    val gestureSwipe = booleanPreferencesKey("gesture_swipe")
    val doubleTapSeek = intPreferencesKey("double_tap_seek_seconds")
    val crossfade = intPreferencesKey("crossfade_seconds")
    val resumeOnReconnect = booleanPreferencesKey("resume_on_output_reconnect")
    val carKeepScreenOn = booleanPreferencesKey("car_keep_screen_on")
    val themeStyle = stringPreferencesKey("theme_style")
    val dockGlassTransparency = intPreferencesKey("dock_glass_transparency")
    val dockGlassBlur = intPreferencesKey("dock_glass_blur")
    val glassClarity = intPreferencesKey("glass_clarity")
    val glassOutlines = booleanPreferencesKey("glass_outlines")
    val autoScan = booleanPreferencesKey("auto_scan_enabled")
    val includeSubfolders = booleanPreferencesKey("include_subfolders")
    val playerBackground = stringPreferencesKey("player_background")
    val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    val skippedUpdateTag = stringPreferencesKey("skipped_update_tag")
    val youtubeIntegration = booleanPreferencesKey("youtube_integration")
    val youtubeConnectPromptDismissed = booleanPreferencesKey("youtube_connect_prompt_dismissed")
    val youtubeSyncHistory = booleanPreferencesKey("youtube_sync_history")
    val youtubeSyncLikes = booleanPreferencesKey("youtube_sync_likes")
    val youtubeSyncPlaylists = booleanPreferencesKey("youtube_sync_playlists")
    val lyricsProviderOrder = stringPreferencesKey("lyrics_provider_order")
    val lyricsProvidersEnabled = stringPreferencesKey("lyrics_providers_enabled")
    val metadataProvidersEnabled = stringPreferencesKey("metadata_providers_enabled")
    val preferSyncedLyrics = booleanPreferencesKey("prefer_synced_lyrics")
    val overwriteExistingTags = booleanPreferencesKey("overwrite_existing_tags")
    val fetchArtistPhotos = booleanPreferencesKey("fetch_artist_photos")
    val useDeezerForArtistPhotos = booleanPreferencesKey("use_deezer_artist_photos")
    val lastFmApiKey = stringPreferencesKey("lastfm_api_key_enc")
    val wifiStreamQuality = intPreferencesKey("wifi_stream_quality")
    val mobileStreamQuality = intPreferencesKey("mobile_stream_quality")
    val dataSaverEnabled = booleanPreferencesKey("data_saver_enabled")
    val streamSource = stringPreferencesKey("stream_source")
    val reduceBlurAndEffects = booleanPreferencesKey("reduce_blur_effects")
    val downloadLyricsWithSongs = booleanPreferencesKey("download_lyrics_with_songs")
    val cacheSizeMb = intPreferencesKey("cache_size_mb")
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
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    /** Reads one resolver's persisted rolling stats (Part M). */
    suspend fun resolverStat(newpipe: Boolean): ResolverStat {
        val prefs = context.settingsStore.data.first()
        return if (newpipe) {
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
    }

    /** Persists one resolution outcome for the resolver race (Part M). */
    suspend fun recordResolverResult(newpipe: Boolean, latencyMs: Long, succeeded: Boolean) {
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
            performanceProfile = enumOr(this[Keys.performanceProfile], d.performanceProfile),
            gestureSeekEnabled = this[Keys.gestureSeek] ?: d.gestureSeekEnabled,
            gestureSwipeEnabled = this[Keys.gestureSwipe] ?: d.gestureSwipeEnabled,
            doubleTapSeekSeconds = (this[Keys.doubleTapSeek] ?: d.doubleTapSeekSeconds).coerceIn(5, 60),
            crossfadeSeconds = (this[Keys.crossfade] ?: d.crossfadeSeconds).coerceIn(0, 12),
            resumeOnOutputReconnect = this[Keys.resumeOnReconnect] ?: d.resumeOnOutputReconnect,
            carKeepScreenOn = this[Keys.carKeepScreenOn] ?: d.carKeepScreenOn,
            themeStyle = enumOr(this[Keys.themeStyle], d.themeStyle),
            dockGlassTransparency = (this[Keys.dockGlassTransparency] ?: d.dockGlassTransparency).coerceIn(0, 100),
            dockGlassBlur = (this[Keys.dockGlassBlur] ?: d.dockGlassBlur).coerceIn(0, 100),
            glassClarity = (this[Keys.glassClarity] ?: d.glassClarity).coerceIn(0, 100),
            glassOutlines = this[Keys.glassOutlines] ?: d.glassOutlines,
            autoScanEnabled = this[Keys.autoScan] ?: d.autoScanEnabled,
            includeSubfolders = this[Keys.includeSubfolders] ?: d.includeSubfolders,
            playerBackground = enumOr(this[Keys.playerBackground], d.playerBackground),
            lastUpdateCheckAt = (this[Keys.lastUpdateCheckAt] ?: d.lastUpdateCheckAt).coerceAtLeast(0L),
            skippedUpdateTag = this[Keys.skippedUpdateTag] ?: d.skippedUpdateTag,
            youtubeIntegration = this[Keys.youtubeIntegration] ?: d.youtubeIntegration,
            youtubeConnectPromptDismissed = this[Keys.youtubeConnectPromptDismissed] ?: d.youtubeConnectPromptDismissed,
            youtubeSyncHistory = this[Keys.youtubeSyncHistory] ?: d.youtubeSyncHistory,
            youtubeSyncLikes = this[Keys.youtubeSyncLikes] ?: d.youtubeSyncLikes,
            youtubeSyncPlaylists = this[Keys.youtubeSyncPlaylists] ?: d.youtubeSyncPlaylists,
            lyricsProviderOrder = decodeList(this[Keys.lyricsProviderOrder], d.lyricsProviderOrder),
            lyricsProvidersEnabled = decodeSet(this[Keys.lyricsProvidersEnabled], d.lyricsProvidersEnabled),
            metadataProvidersEnabled = decodeSet(this[Keys.metadataProvidersEnabled], d.metadataProvidersEnabled),
            preferSyncedLyrics = this[Keys.preferSyncedLyrics] ?: d.preferSyncedLyrics,
            overwriteExistingTags = this[Keys.overwriteExistingTags] ?: d.overwriteExistingTags,
            fetchArtistPhotos = this[Keys.fetchArtistPhotos] ?: d.fetchArtistPhotos,
            useDeezerForArtistPhotos = this[Keys.useDeezerForArtistPhotos] ?: d.useDeezerForArtistPhotos,
            lastFmApiKey = decrypt(this[Keys.lastFmApiKey]),
            wifiStreamQuality = StreamQuality.fromIndex(this[Keys.wifiStreamQuality] ?: d.wifiStreamQuality.ordinal),
            mobileStreamQuality = StreamQuality.fromIndex(this[Keys.mobileStreamQuality] ?: d.mobileStreamQuality.ordinal),
            dataSaverEnabled = this[Keys.dataSaverEnabled] ?: d.dataSaverEnabled,
            streamSource = this[Keys.streamSource]?.let { runCatching { StreamSourceMode.valueOf(it) }.getOrNull() } ?: d.streamSource,
            reduceBlurAndEffects = this[Keys.reduceBlurAndEffects] ?: d.reduceBlurAndEffects,
            downloadLyricsWithSongs = this[Keys.downloadLyricsWithSongs] ?: d.downloadLyricsWithSongs,
            cacheSizeMb = this[Keys.cacheSizeMb] ?: d.cacheSizeMb,
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
        this[Keys.performanceProfile] = s.performanceProfile.name
        this[Keys.gestureSeek] = s.gestureSeekEnabled
        this[Keys.gestureSwipe] = s.gestureSwipeEnabled
        this[Keys.doubleTapSeek] = s.doubleTapSeekSeconds
        this[Keys.crossfade] = s.crossfadeSeconds
        this[Keys.resumeOnReconnect] = s.resumeOnOutputReconnect
        this[Keys.carKeepScreenOn] = s.carKeepScreenOn
        this[Keys.themeStyle] = s.themeStyle.name
        this[Keys.dockGlassTransparency] = s.dockGlassTransparency
        this[Keys.dockGlassBlur] = s.dockGlassBlur
        this[Keys.glassClarity] = s.glassClarity
        this[Keys.glassOutlines] = s.glassOutlines
        this[Keys.autoScan] = s.autoScanEnabled
        this[Keys.includeSubfolders] = s.includeSubfolders
        this[Keys.playerBackground] = s.playerBackground.name
        this[Keys.lastUpdateCheckAt] = s.lastUpdateCheckAt
        this[Keys.skippedUpdateTag] = s.skippedUpdateTag
        this[Keys.youtubeIntegration] = s.youtubeIntegration
        this[Keys.youtubeConnectPromptDismissed] = s.youtubeConnectPromptDismissed
        this[Keys.youtubeSyncHistory] = s.youtubeSyncHistory
        this[Keys.youtubeSyncLikes] = s.youtubeSyncLikes
        this[Keys.youtubeSyncPlaylists] = s.youtubeSyncPlaylists
        this[Keys.lyricsProviderOrder] = s.lyricsProviderOrder.joinToString(",")
        this[Keys.lyricsProvidersEnabled] = s.lyricsProvidersEnabled.joinToString(",")
        this[Keys.metadataProvidersEnabled] = s.metadataProvidersEnabled.joinToString(",")
        this[Keys.preferSyncedLyrics] = s.preferSyncedLyrics
        this[Keys.overwriteExistingTags] = s.overwriteExistingTags
        this[Keys.fetchArtistPhotos] = s.fetchArtistPhotos
        this[Keys.useDeezerForArtistPhotos] = s.useDeezerForArtistPhotos
        this[Keys.lastFmApiKey] = encrypt(s.lastFmApiKey)
        this[Keys.wifiStreamQuality] = s.wifiStreamQuality.ordinal
        this[Keys.mobileStreamQuality] = s.mobileStreamQuality.ordinal
        this[Keys.dataSaverEnabled] = s.dataSaverEnabled
        this[Keys.streamSource] = s.streamSource.name
        this[Keys.reduceBlurAndEffects] = s.reduceBlurAndEffects
        this[Keys.downloadLyricsWithSongs] = s.downloadLyricsWithSongs
        this[Keys.cacheSizeMb] = s.cacheSizeMb
    }

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
