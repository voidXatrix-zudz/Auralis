package com.auralis.app.ui.lyrics

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.auralis.app.R
import com.auralis.app.domain.model.LyricsFontChoice

fun defaultLyricsFontFamily(choice: LyricsFontChoice): FontFamily {
    return when (choice) {
        LyricsFontChoice.INTER -> FontFamily.SansSerif
        LyricsFontChoice.OUTFIT -> FontFamily.SansSerif
        LyricsFontChoice.JETBRAINS_MONO -> FontFamily.Monospace
        LyricsFontChoice.PAPYRUS -> FontFamily.Cursive
        LyricsFontChoice.MINECRAFT -> FontFamily.Monospace
        LyricsFontChoice.PATRICK_HAND -> FontFamily.Cursive
        LyricsFontChoice.COMFORTAA -> FontFamily.SansSerif
        LyricsFontChoice.CAVEAT -> FontFamily.Cursive
        LyricsFontChoice.LOBSTER -> FontFamily.Cursive
        LyricsFontChoice.BEBAS_NEUE -> FontFamily.SansSerif
        LyricsFontChoice.ORBITRON -> FontFamily.SansSerif
        LyricsFontChoice.PLAYFAIR -> FontFamily.Serif
        LyricsFontChoice.DANCING_SCRIPT -> FontFamily.Cursive
        LyricsFontChoice.CINZEL -> FontFamily.Serif
    }
}

fun createAndroidLyricsFontResolver(): (LyricsFontChoice) -> FontFamily {
    val map = mapOf(
        LyricsFontChoice.INTER to FontFamily(Font(R.font.inter)),
        LyricsFontChoice.OUTFIT to FontFamily(Font(R.font.outfit)),
        LyricsFontChoice.JETBRAINS_MONO to FontFamily(Font(R.font.jetbrains_mono)),
        LyricsFontChoice.PAPYRUS to FontFamily(Font(R.font.papyrus)),
        LyricsFontChoice.MINECRAFT to FontFamily(Font(R.font.minecraft)),
        LyricsFontChoice.PATRICK_HAND to FontFamily(Font(R.font.patrick_hand)),
        LyricsFontChoice.COMFORTAA to FontFamily(Font(R.font.comfortaa)),
        LyricsFontChoice.CAVEAT to FontFamily(Font(R.font.caveat)),
        LyricsFontChoice.LOBSTER to FontFamily(Font(R.font.lobster)),
        LyricsFontChoice.BEBAS_NEUE to FontFamily(Font(R.font.bebas_neue)),
        LyricsFontChoice.ORBITRON to FontFamily(Font(R.font.orbitron)),
        LyricsFontChoice.PLAYFAIR to FontFamily(Font(R.font.playfair_display)),
        LyricsFontChoice.DANCING_SCRIPT to FontFamily(Font(R.font.dancing_script)),
        LyricsFontChoice.CINZEL to FontFamily(Font(R.font.cinzel)),
    )
    return { choice -> map[choice] ?: map[LyricsFontChoice.INTER] ?: FontFamily.SansSerif }
}

val LocalLyricsFontResolver = compositionLocalOf<(LyricsFontChoice) -> FontFamily> {
    { choice -> defaultLyricsFontFamily(choice) }
}

val LocalLyricsFontFamily = compositionLocalOf<FontFamily> { FontFamily.SansSerif }
