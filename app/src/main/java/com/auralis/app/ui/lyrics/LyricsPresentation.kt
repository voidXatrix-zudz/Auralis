package com.auralis.app.ui.lyrics

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.auralis.app.domain.model.LrcLine
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsDisplayMode
import com.auralis.app.domain.model.LyricsSettings

/**
 * Turns [LyricsDisplayMode] and the romanization toggle into the text actually drawn.
 *
 * Romanization uses the platform ICU transliterator (API 29+), so no font or dependency is added.
 * Below API 29 the toggle is inert and every mode collapses to [LyricsDisplayMode.ORIGINAL_ONLY];
 * the settings UI disables the switch with a note instead of pretending it works.
 */
object LyricsPresentation {

    /** True when the platform can transliterate (ICU transliteration landed in Android 10). */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val romanizationSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The mode that is really applied at render time. */
    fun effectiveMode(settings: LyricsSettings): LyricsDisplayMode =
        if (settings.romanizeForeignLyrics && romanizationSupported) {
            settings.lyricsDisplayMode
        } else {
            LyricsDisplayMode.ORIGINAL_ONLY
        }

    /** Latin script plus the accented Latin blocks; everything else is worth romanizing. */
    fun containsNonLatin(text: String): Boolean =
        text.any { it.isLetter() && it.code > 0x024F }

    /**
     * Romanizes one string, leaving Latin-only text untouched. Word-level callers pass single
     * words so their timings survive; line-level callers get the joined result.
     */
    fun romanize(text: String): String {
        if (text.isBlank() || !containsNonLatin(text)) return text
        val transliterator = transliterator ?: return text
        return runCatching { transliterator.transliterate(text) }.getOrDefault(text)
    }

    /** Keeps [LrcLine.words] and their timings, so word-by-word karaoke spans stay aligned. */
    fun romanize(line: LrcLine): LrcLine {
        if (line.words.isEmpty()) return line.copy(text = romanize(line.text))
        val words = line.words.map { it.copy(text = romanize(it.text)) }
        return line.copy(text = words.joinToString(" ") { it.text }, words = words)
    }

    /**
     * One renderable line plus, for [LyricsDisplayMode.DUAL_LINE], the romanized text that the
     * karaoke item draws underneath the original.
     */
    data class PresentedLine(
        val line: LrcLine,
        val secondaryText: String?,
    )

    fun present(lines: List<LrcLine>, mode: LyricsDisplayMode): List<PresentedLine> = lines.map { line ->
        when (mode) {
            LyricsDisplayMode.ORIGINAL_ONLY -> PresentedLine(line, null)
            LyricsDisplayMode.DUAL_LINE -> {
                val romanized = romanize(line)
                if (romanized.text == line.text) {
                    PresentedLine(line, null)
                } else {
                    PresentedLine(line, romanized.text)
                }
            }
            LyricsDisplayMode.ROMANIZED_ONLY -> {
                val romanized = romanize(line)
                PresentedLine(if (romanized.text.isBlank()) line else romanized, null)
            }
        }
    }

    /**
     * Plain (untimed) lyrics: dual mode interleaves each original line with its romanized form;
     * romanized-only replaces them.
     */
    fun presentPlain(text: String, mode: LyricsDisplayMode): String = when (mode) {
        LyricsDisplayMode.ORIGINAL_ONLY -> text
        LyricsDisplayMode.ROMANIZED_ONLY -> text.lines().joinToString("\n") { romanize(it) }
        LyricsDisplayMode.DUAL_LINE -> text.lines().joinToString("\n") { line ->
            val romanized = romanize(line)
            if (romanized == line || romanized.isBlank()) line else "$line\n$romanized"
        }
    }

    /** Cinematic engine input: romanized lines for both dual and romanized-only modes. */
    fun cinematicLines(lyrics: Lyrics?, mode: LyricsDisplayMode): List<LrcLine> {
        val synced = lyrics?.synced.orEmpty()
        return when (mode) {
            LyricsDisplayMode.ORIGINAL_ONLY -> synced
            LyricsDisplayMode.DUAL_LINE, LyricsDisplayMode.ROMANIZED_ONLY -> synced.map(::romanize)
        }
    }

    private val transliterator: Transliterator? by lazy {
        if (!romanizationSupported) null else createTransliterator()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createTransliterator(): Transliterator? =
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
}
