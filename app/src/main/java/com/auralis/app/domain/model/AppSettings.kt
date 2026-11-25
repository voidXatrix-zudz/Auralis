package com.auralis.app.domain.model

import com.auralis.app.domain.audio.EqPresets

/** Persisted user settings (DataStore). Defaults match the web reference. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: String = "Teal",
    val dynamicTheme: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqGains: List<Float> = EqPresets.gains("Flat"),
    /** Device-band gains in millibels, in the order reported by the hardware equalizer. */
    val eqBandLevels: List<Int> = emptyList(),
    /** Number of device bands [eqBandLevels] was captured for; 0 means "not captured yet". */
    val eqBandCount: Int = 0,
    val preampDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val bassDb: Float = 0f,
    val bassBoostEnabled: Boolean = false,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val speed: Float = 1f,
    val balance: Float = 0f,
    val volume: Float = 1f,
    val replayGain: ReplayGainMode = ReplayGainMode.TRACK,
    val lyricSizeSp: Int = 22,
    val lyricsAutoScroll: Boolean = true,
    /** Global timing correction for synced lyrics, in milliseconds (positive = lyrics later). */
    val lyricsOffsetMs: Int = 0,
    /** Library sort selector: "CUSTOM", "RECENTS" or "AZ" (Part R). */
    val librarySort: String = "CUSTOM",
    /** Library list/grid toggle; false = list (Part R). */
    val libraryGridView: Boolean = false,
    val performanceProfile: PerformanceMode = PerformanceMode.AUTOMATIC,
    val gestureSeekEnabled: Boolean = true,
    val gestureSwipeEnabled: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val crossfadeSeconds: Int = 0,
    val resumeOnOutputReconnect: Boolean = false,
    val carKeepScreenOn: Boolean = true,
    val themeStyle: ThemeStyle = ThemeStyle.NORMAL,
    /** Glassmorphism "Dock transparency": 0 = fully see-through, 100 = opaque. Default 35 (STEP 8.2). */
    val dockGlassOpacity: Int = 35,
    /** Glassmorphism "Glass clarity": 0 = frosted/opaque, 100 = clear. Default 60 (STEP 8.2). */
    val glassClarity: Int = 60,
    /** Draw the 1dp specular hairline on glass surfaces. */
    val glassOutlines: Boolean = true,
    /** Watch MediaStore/folders for new, changed and removed songs and update the library automatically. */
    val autoScanEnabled: Boolean = true,
    /** Include songs from subfolders of each chosen music folder. */
    val includeSubfolders: Boolean = true,
    /** Full-screen photo behind the Now Playing player. */
    val playerBackground: PlayerBackground = PlayerBackground.NONE,
    /** Epoch millis of the last GitHub release check (throttles the launch check to 12 hours). */
    val lastUpdateCheckAt: Long = 0L,
    /** Release tag the user skipped; automatic checks stay silent for that version. */
    val skippedUpdateTag: String = "",
    /** Opt-in YouTube Music integration (account, Home feed, liked songs). Off by default. */
    val youtubeIntegration: Boolean = false,
    /**
     * Show the account's own personalized YouTube Music rows on Home. On by default: when off (or
     * when not connected) Home is the plain local library Home, never a generic charts feed.
     */
    val personalizedHome: Boolean = true,
    /** Epoch millis until which the post-sign-in "Connect YouTube Music" prompt stays hidden. */
    val youtubeConnectPromptSnoozeUntil: Long = 0L,
    /** Sync in-app plays to the YouTube Music account history. Default on once signed in. */
    val youtubeSyncHistory: Boolean = true,
    /** Mirror likes to the YouTube Music account. Default on once signed in. */
    val youtubeSyncLikes: Boolean = true,
    /** Mirror local playlists to the YouTube Music account. Default on once signed in. */
    val youtubeSyncPlaylists: Boolean = true,
    /** Keep the signed-in account live with a small polling engine. Default on when integration is on. */
    val liveSyncEnabled: Boolean = true,
    /** Epoch millis of the last successful live-sync pass, so Settings can show "Last synced". */
    val liveSyncLastSyncedAt: Long = 0L,
    /**
     * Hide anything that is not a catalogue song (music videos, UGC uploads, podcasts, long mixes).
     * Off by default: the normal filter keeps official videos and typeless tracks under 12 minutes.
     */
    val youtubeSongsOnly: Boolean = false,
    /** Lyrics providers in lookup order (ids; includes disabled ones so order survives toggles). */
    val lyricsProviderOrder: List<String> = DEFAULT_LYRICS_PROVIDER_ORDER,
    /** Enabled lyrics providers. Defaults chosen for reliability; the rest are opt-in. */
    val lyricsProvidersEnabled: Set<String> = setOf(
        "LrcRed", "LRCLIB", "BetterLyrics", "KuGou", "YouTubeMusic",
    ),
    /** Enabled metadata providers. */
    val metadataProvidersEnabled: Set<String> = setOf("MusicBrainz", "Deezer"),
    /** When on, keep trying remaining providers for a synced result before accepting unsynced. */
    val preferSyncedLyrics: Boolean = true,
    /** Optional Better Lyrics API key (syllable TTML + Portato providers). Stored encrypted. */
    val betterLyricsApiKey: String = "",
    /** Optional Musixmatch user token; the provider is skipped silently when blank. Stored encrypted. */
    val musixmatchToken: String = "",
    /** Optional PaxSenix key (reserved; PaxSenix providers are registered disabled). Stored encrypted. */
    val paxsenixApiKey: String = "",
    /** Optional Genius access token (the public search path works without one). Stored encrypted. */
    val geniusApiKey: String = "",
    /** When on, Auto-fix tags may overwrite existing (non-empty) tag fields. */
    val overwriteExistingTags: Boolean = false,
    /** Show artist photos throughout the UI (Part L). */
    val fetchArtistPhotos: Boolean = true,
    /** Use Deezer's public artist search for artist photos when no YouTube id is known (Part L). */
    val useDeezerForArtistPhotos: Boolean = true,
    /** Last.fm API key for genre tags (empty = provider disabled). Stored encrypted. */
    val lastFmApiKey: String = "",
    /** Streaming quality on Wi-Fi/Ethernet. Default Best. */
    val wifiStreamQuality: StreamQuality = StreamQuality.BEST,
    /** Streaming quality on cellular (and metered Wi-Fi). Default Normal. */
    val mobileStreamQuality: StreamQuality = StreamQuality.NORMAL,
    /** Force Low on cellular and stop cellular prefetch. */
    val dataSaverEnabled: Boolean = false,
    /** Which resolver stack to use for YouTube streams. Default Auto (fastest). */
    val streamSource: StreamSourceMode = StreamSourceMode.AUTO,
    /** Include the embedded YouTube.js provider in the Auto stream race. On by default. */
    val youtubeJsEnabled: Boolean = true,
    /** Reduce blur and heavy visual effects even on capable devices. */
    val reduceBlurAndEffects: Boolean = false,
    /** Download the matching lyrics alongside a downloaded song. */
    val downloadLyricsWithSongs: Boolean = true,
    /** Cache size budget in MB (0 = unlimited). Used by the Storage radio list. */
    val cacheSizeMb: Int = 512,
    /** True once the one-time first-run welcome screen has been dismissed (shown before the login gate). */
    val welcomeSeen: Boolean = false,
) {
    companion object {
        /** Default lyrics provider lookup order: word-synced first, then line-synced, then plain. */
        val DEFAULT_LYRICS_PROVIDER_ORDER: List<String> = listOf(
            "LrcRed", "BiniLyrics", "BetterLyrics", "BetterLyricsPortato", "LyricsPlus", "Unison",
            "KuGou", "LRCLIB", "YouTubeMusic", "YouTubeCaptions", "SimpMusic", "NetEase",
            "Musixmatch", "Genius", "PaxSenix", "PaxSenixSpotify", "PaxSenixMusixmatch", "LyricsOvh",
        )
    }
}
