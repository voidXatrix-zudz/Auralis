package com.auralis.app.domain.model

enum class LyricsStyle {
    CLASSIC_FULLSCREEN,
    CINEMATIC_KINETIC,
}

enum class LyricsDisplayMode {
    ORIGINAL_ONLY,
    DUAL_LINE,
    ROMANIZED_ONLY,
}

enum class LyricsProviderChoice(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isSyllableCapable: Boolean,
) {
    AUTO("auto", "Auto (Best Match)", "Syllable-first concurrent discovery", true),
    LRC_RED("lrcred", "lrc.red", "Apple Music studio syllable TTML", true),
    BINI("bini", "Bini Lyrics", "Apple Music TTML with ISRC discovery", true),
    MUSIXMATCH("musixmatch", "Musixmatch", "RichSync word timing & 14M+ songs", true),
    UNISON("unison", "Unison", "Community karaoke & Asian sync catalog", true),
    LRCLIB("lrclib", "LrcLib", "Open community synchronized lyrics database", false),
    BETTER_LYRICS("betterlyrics", "BetterLyrics", "Apple TTML & QQ Music Portato", true),
    LYRICS_PLUS("lyricsplus", "LyricsPlus", "Spotify & Musixmatch community mirror", true),
    KUGOU("kugou", "KuGou", "Extensive Asian & global catalog", false),
    LOCAL("local", "Local File", "Embedded tags or companion .lrc / .ttml", true);

    /**
     * Provider id used by the online lyrics aggregator, or null when the choice only expresses
     * "no preference" ([AUTO]) or a non-network source ([LOCAL]). The ids are the same strings
     * [com.auralis.app.data.lyrics.LyricsProviderCatalog] exposes.
     */
    val catalogId: String?
        get() = when (this) {
            AUTO, LOCAL -> null
            LRC_RED -> "LrcRed"
            BINI -> "BiniLyrics"
            MUSIXMATCH -> "Musixmatch"
            UNISON -> "Unison"
            LRCLIB -> "LRCLIB"
            BETTER_LYRICS -> "BetterLyrics"
            LYRICS_PLUS -> "LyricsPlus"
            KUGOU -> "KuGou"
        }

    companion object {
        fun fromId(id: String?): LyricsProviderChoice {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class LyricsGlowPalette(
    val id: String,
    val displayName: String,
    val litColorArgb: Long,
    val midColorArgb: Long,
    val glowColorArgb: Long,
    val ambientColorArgb: Long,
) {
    CINEMA_WHITE(
        id = "white",
        displayName = "Cinema White",
        litColorArgb = 0xFFFFFFFF,
        midColorArgb = 0xFFE8F2FF,
        glowColorArgb = 0x85FFFFFF,
        ambientColorArgb = 0x14C8DCFF,
    ),
    GLACIAL_BLUE(
        id = "blue",
        displayName = "Glacial Blue",
        litColorArgb = 0xFFF4FAFF,
        midColorArgb = 0xFF7CD4FF,
        glowColorArgb = 0x946ECDFF,
        ambientColorArgb = 0x1A46A5FF,
    ),
    WARM_GOLD(
        id = "gold",
        displayName = "Warm Gold",
        litColorArgb = 0xFFFFFDF5,
        midColorArgb = 0xFFFFD57E,
        glowColorArgb = 0x8CFFCD6E,
        ambientColorArgb = 0x17FFB450,
    ),
    LUNAR_VIOLET(
        id = "violet",
        displayName = "Lunar Violet",
        litColorArgb = 0xFFFAF5FF,
        midColorArgb = 0xFFC89BFF,
        glowColorArgb = 0x8FC084FC,
        ambientColorArgb = 0x1AA855F7,
    );

    companion object {
        fun fromId(id: String?): LyricsGlowPalette {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: CINEMA_WHITE
        }
    }
}

enum class LyricsFontChoice(
    val id: String,
    val displayName: String,
    val subtitle: String,
) {
    INTER("inter", "Inter", "Modern clean sans-serif"),
    OUTFIT("outfit", "Outfit", "Geometric precision"),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", "Cyber terminal monospace"),
    PAPYRUS("papyrus", "Papyrus", "Organic Egyptian brush script"),
    MINECRAFT("minecraft", "Minecraft", "8-Bit retro arcade pixel"),
    PATRICK_HAND("patrick_hand", "Patrick Hand", "Warm whimsical handwriting"),
    COMFORTAA("comfortaa", "Comfortaa", "Smooth rounded geometric"),
    CAVEAT("caveat", "Caveat", "Natural expressive cursive"),
    LOBSTER("lobster", "Lobster", "Bold retro vintage script"),
    BEBAS_NEUE("bebas_neue", "Bebas Neue", "Impactful cinema poster caps"),
    ORBITRON("orbitron", "Orbitron", "Futuristic sci-fi synthwave"),
    PLAYFAIR("playfair", "Playfair Display", "High-contrast luxury editorial"),
    DANCING_SCRIPT("dancing_script", "Dancing Script", "Lively flowing calligraphy"),
    CINZEL("cinzel", "Cinzel", "Grand Roman classical serif");

    companion object {
        fun fromId(id: String?): LyricsFontChoice {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: INTER
        }
    }
}

enum class LyricsBaseColor(
    val id: String,
    val displayName: String,
    val colorArgb: Long,
) {
    PURE_WHITE("white", "Pure White", 0xFFFFFFFF),
    WARM_CREAM("cream", "Warm Cream", 0xFFFFFBEB),
    AMBER_GOLD("amber", "Amber Gold", 0xFFFFD54F),
    ELECTRIC_TEAL("teal", "Electric Teal", 0xFF5EEAD4),
    NEON_ROSE("rose", "Neon Rose", 0xFFF472B6),
    GLACIAL_MINT("mint", "Glacial Mint", 0xFF6EE7B7),
    LAVENDER("lavender", "Lavender Dream", 0xFFC084FC),
    DEEP_CYAN("cyan", "Deep Cyan", 0xFF38BDF8);

    companion object {
        fun fromId(id: String?): LyricsBaseColor {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: PURE_WHITE
        }
    }
}

data class LyricsSettings(
    val style: LyricsStyle = LyricsStyle.CLASSIC_FULLSCREEN,
    val commonZoom: Float = 1.00f,
    val sparkleDensity: Float = 0.35f,
    val lrSwing: Float = 0.80f,
    val verticalSpacing: Float = 1.15f,
    val cameraGlide: Float = 1.00f,
    val bounce: Float = 1.00f,
    val palette: LyricsGlowPalette = LyricsGlowPalette.CINEMA_WHITE,
    val preferredProvider: LyricsProviderChoice = LyricsProviderChoice.AUTO,
    val romanizeForeignLyrics: Boolean = true,
    val lyricsDisplayMode: LyricsDisplayMode = LyricsDisplayMode.DUAL_LINE,
    val fontChoice: LyricsFontChoice = LyricsFontChoice.INTER,
    val fontSizeScale: Float = 1.00f,
    val baseColor: LyricsBaseColor = LyricsBaseColor.PURE_WHITE,
    val glowIntensity: Float = 1.00f,
    val inactiveOpacity: Float = 0.40f,
    val isBold: Boolean = true,
)
