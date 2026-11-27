package com.auralis.app.data.settings

import com.auralis.app.data.lyrics.LyricsProviderCatalog
import com.auralis.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings merge that keeps existing users' provider order while surfacing new providers. */
class LyricsProviderOrderTest {

    private val legacyOrder = listOf(
        "LRCLIB", "SimpMusic", "NetEase", "KuGou", "BetterLyrics", "YouTubeMusic", "LyricsOvh",
    )

    @Test
    fun `an existing order gets the new providers appended, not reordered`() {
        val merged = mergeProviderOrder(legacyOrder)
        assertEquals(legacyOrder, merged.take(legacyOrder.size))
        val expectedAppended = LyricsProviderCatalog.ALL.map { it.id }.filter { it !in legacyOrder }
        assertEquals(expectedAppended, merged.drop(legacyOrder.size))
    }

    @Test
    fun `unknown ids are dropped and new providers still appended`() {
        val merged = mergeProviderOrder(legacyOrder + "RemovedProvider")
        assertEquals(legacyOrder, merged.take(legacyOrder.size))
        val expectedAppended = LyricsProviderCatalog.ALL.map { it.id }.filter { it !in legacyOrder }
        assertEquals(expectedAppended, merged.drop(legacyOrder.size))
    }

    @Test
    fun `a complete order is left untouched`() {
        val complete = AppSettings.DEFAULT_LYRICS_PROVIDER_ORDER
        assertEquals(complete, mergeProviderOrder(complete))
    }

    @Test
    fun `the default order lists every catalog provider`() {
        assertEquals(
            LyricsProviderCatalog.ALL.map { it.id }.toSet(),
            AppSettings.DEFAULT_LYRICS_PROVIDER_ORDER.toSet(),
        )
    }

    @Test
    fun `the default enabled set turns on the resilient providers`() {
        val enabled = AppSettings().lyricsProvidersEnabled
        assertTrue(enabled.containsAll(listOf("LRCLIB", "LrcRed", "BetterLyrics", "KuGou", "YouTubeMusic")))
    }

    @Test
    fun `the default order starts with the owner's eleven providers and only those are enabled`() {
        val eleven = listOf(
            "LrcRed", "BiniLyrics", "BetterLyrics", "BetterLyricsPortato", "LyricsPlus", "Unison",
            "KuGou", "LRCLIB", "YouTubeMusic", "YouTubeCaptions", "SimpMusic",
        )
        assertEquals(eleven, AppSettings.DEFAULT_LYRICS_PROVIDER_ORDER.take(eleven.size))
        assertEquals(eleven.toSet(), AppSettings().lyricsProvidersEnabled)
    }
}
