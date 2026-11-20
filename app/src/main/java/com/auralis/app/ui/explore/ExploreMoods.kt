package com.auralis.app.ui.explore

import androidx.compose.ui.graphics.Color

/**
 * One Explore mood tile. [accent] is the solid strip down the left edge, [shade] fills the darker
 * right side. [artUrl] is optional: when a mood has art it is drawn tinted inside the tile, and a
 * plain colour block is used when it does not.
 */
data class ExploreMood(
    val id: String,
    val title: String,
    val accent: Color,
    val shade: Color,
    val artUrl: String? = null,
)

/**
 * The ten Explore moods. Everything behind a tile is fetched from the streaming side only
 * (YouTube Music via InnerTube), so these tiles never read or filter the local library or
 * downloads.
 */
object ExploreMoods {
    val all: List<ExploreMood> = listOf(
        ExploreMood("chill", "Chill", Color(0xFF2BC4B0), Color(0xFF0C3A35)),
        ExploreMood("commute", "Commute", Color(0xFF5C6BC0), Color(0xFF1A2044)),
        ExploreMood("diwali", "Diwali", Color(0xFFF0A63C), Color(0xFF3A290B)),
        ExploreMood("energize", "Energize", Color(0xFFFF5C4D), Color(0xFF3F1410)),
        ExploreMood("feelgood", "Feel good", Color(0xFFFF6FA5), Color(0xFF3C1323)),
        ExploreMood("focus", "Focus", Color(0xFF4A8DFF), Color(0xFF10203E)),
        ExploreMood("gaming", "Gaming", Color(0xFF8A6CFF), Color(0xFF20123F)),
        ExploreMood("party", "Party", Color(0xFFE456C8), Color(0xFF350E2D)),
        ExploreMood("romance", "Romance", Color(0xFFE2557B), Color(0xFF39101F)),
        ExploreMood("sad", "Sad", Color(0xFF6B7C93), Color(0xFF19212F)),
    )

    fun byId(id: String): ExploreMood? = all.firstOrNull { it.id == id }
}