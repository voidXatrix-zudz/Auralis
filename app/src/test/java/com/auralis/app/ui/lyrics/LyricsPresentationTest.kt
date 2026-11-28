package com.auralis.app.ui.lyrics

import com.auralis.app.data.lyrics.LyricsProviderCatalog
import com.auralis.app.domain.model.LyricsDisplayMode
import com.auralis.app.domain.model.LyricsProviderChoice
import com.auralis.app.domain.model.LyricsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPresentationTest {

    @Test
    fun `effectiveMode falls back to ORIGINAL_ONLY when romanization is disabled`() {
        assertEquals(
            LyricsDisplayMode.ORIGINAL_ONLY,
            LyricsPresentation.effectiveMode(
                LyricsSettings(lyricsDisplayMode = LyricsDisplayMode.DUAL_LINE, romanizeForeignLyrics = false),
            ),
        )
        assertEquals(
            LyricsDisplayMode.ORIGINAL_ONLY,
            LyricsPresentation.effectiveMode(
                LyricsSettings(lyricsDisplayMode = LyricsDisplayMode.ROMANIZED_ONLY, romanizeForeignLyrics = false),
            ),
        )
    }

    @Test
    fun `containsNonLatin detects non-Latin scripts and leaves Latin untouched`() {
        assertFalse(LyricsPresentation.containsNonLatin("Hello world, café!"))
        assertTrue(LyricsPresentation.containsNonLatin("안녕하세요"))
        assertTrue(LyricsPresentation.containsNonLatin("こんにちは"))
        assertTrue(LyricsPresentation.containsNonLatin("नमस्ते"))
    }

    @Test
    fun `all mapped LyricsProviderChoice catalogIds exist in LyricsProviderCatalog`() {
        val catalogIds = LyricsProviderCatalog.ALL.map { it.id }.toSet()
        for (choice in LyricsProviderChoice.entries) {
            val mapped = choice.catalogId
            if (choice == LyricsProviderChoice.AUTO || choice == LyricsProviderChoice.LOCAL) {
                assertNull("${choice.name} should map to null", mapped)
            } else {
                assertNotNull("${choice.name} should map to a catalog id", mapped)
                assertTrue("$mapped not found in LyricsProviderCatalog.ALL", mapped in catalogIds)
            }
        }
    }
}
