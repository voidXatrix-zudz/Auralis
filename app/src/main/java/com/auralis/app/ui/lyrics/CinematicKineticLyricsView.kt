package com.auralis.app.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.LrcWord
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsDisplayMode
import com.auralis.app.domain.model.LyricsGlowPalette
import com.auralis.app.domain.model.LyricsSettings
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.screens.ArtPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Mulberry32(seed: Long) {
    private var state = seed.toInt()

    fun nextFloat(): Float {
        state += 0x6D2B79F5.toInt()
        var z = state
        z = ((z xor (z ushr 15)) * (z or 1))
        z = z xor (z + ((z xor (z ushr 7)) * (z or 61)))
        val unsigned = (z xor (z ushr 14)).toLong() and 0xFFFFFFFFL
        return (unsigned.toDouble() / 4294967296.0).toFloat()
    }
}

data class KineticWordAnchors(
    val entryPt: Offset,
    val exitPt: Offset,
    val centerPt: Offset,
    val baselineY: Float,
)

data class KineticWordNode(
    val clean: String,
    val isHero: Boolean,
    val startMs: Long,
    val endMs: Long,
    val gapAfter: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val waveY: Float,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
    val fontStyle: FontStyle,
    val fontSizeSp: Float,
    val anchors: KineticWordAnchors,
    val layoutResult: TextLayoutResult,
)

sealed interface KineticBlock {
    val startMs: Long
    val endMs: Long

    data class PhraseBlock(
        override val startMs: Long,
        override val endMs: Long,
        val words: List<KineticWordNode>,
    ) : KineticBlock

    data class InstrumentalBlock(
        override val startMs: Long,
        override val endMs: Long,
        val cx: Float,
        val cy: Float,
    ) : KineticBlock
}

private data class StarParticle(
    val layer: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val parallax: Float,
    val phase: Float,
    val speed: Float,
    val driftX: Float,
    val driftY: Float,
)

/**
 * Ported Node Player Cinematic Kinetic lyrics engine with Mulberry32 starfield/sparkles,
 * critically-damped 2D camera physics, animated multi-color palette glow, and word-by-word sweep.
 */
@Composable
fun CinematicKineticLyricsView(
    lyrics: Lyrics?,
    isLoading: Boolean,
    isPlaying: Boolean,
    settings: LyricsSettings,
    artPalette: ArtPalette = ArtPalette.Fallback,
    layoutSeed: Long = 42L,
    modifier: Modifier = Modifier,
    onSeekTo: (Long) -> Unit = {},
    positionProvider: () -> Long = { 0L },
    isLandscape: Boolean = false,
) {
    val syncedLines = lyrics?.synced.orEmpty()
    val displayMode = remember(settings.lyricsDisplayMode, settings.romanizeForeignLyrics) {
        LyricsPresentation.effectiveMode(settings)
    }
    val displayLines = remember(syncedLines, displayMode) {
        LyricsPresentation.cinematicLines(lyrics, displayMode)
    }
    if (isLoading && syncedLines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Color(settings.palette.midColorArgb), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text("Looking for lyrics…", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
            }
        }
        return
    }
    if (syncedLines.isEmpty()) {
        Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = lyrics?.plain?.takeIf { it.isNotBlank() } ?: "No synced lyrics available",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 16.sp,
            )
        }
        return
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    var widthPx by remember { mutableFloatStateOf(1080f) }
    var heightPx by remember { mutableFloatStateOf(1920f) }

    var camX by remember { mutableFloatStateOf(0f) }
    var camY by remember { mutableFloatStateOf(0f) }
    var camZoom by remember { mutableFloatStateOf(1f) }
    var camVx by remember { mutableFloatStateOf(0f) }
    var camVy by remember { mutableFloatStateOf(0f) }
    var camVz by remember { mutableFloatStateOf(0f) }
    var smoothCommonZoom by remember { mutableFloatStateOf(settings.commonZoom) }

    var layoutBlocks by remember { mutableStateOf<List<KineticBlock>>(emptyList()) }
    var allWords by remember { mutableStateOf<List<KineticWordNode>>(emptyList()) }

    val stars = remember {
        val rand = Mulberry32(1337L)
        List(180) { i ->
            val layer = if (i < 95) 0 else if (i < 150) 1 else 2
            StarParticle(
                layer = layer,
                x = rand.nextFloat() * 2000f - 1000f,
                y = rand.nextFloat() * 2000f - 1000f,
                size = when (layer) {
                    0 -> 0.9f + rand.nextFloat() * 0.9f
                    1 -> 1.4f + rand.nextFloat() * 1.3f
                    else -> 2.6f + rand.nextFloat() * 2.2f
                },
                parallax = when (layer) {
                    0 -> 0.08f
                    1 -> 0.22f
                    else -> 0.42f
                },
                phase = rand.nextFloat() * (PI.toFloat() * 2f),
                speed = 0.8f + rand.nextFloat() * 1.8f,
                driftX = (rand.nextFloat() - 0.5f) * 6f,
                driftY = -3f - rand.nextFloat() * 8f,
            )
        }
    }

    val fontResolver = LocalLyricsFontResolver.current
    val chosenFontFamily = remember(settings.fontChoice, fontResolver) { fontResolver(settings.fontChoice) }

    LaunchedEffect(
        displayLines,
        layoutSeed,
        settings.lrSwing,
        settings.verticalSpacing,
        settings.fontChoice,
        settings.fontSizeScale,
        settings.isBold,
        widthPx,
        isLandscape,
        chosenFontFamily,
    ) {
        val rand = Mulberry32(layoutSeed)
        val vScale = settings.verticalSpacing
        val maxLineW = if (isLandscape) {
            min(520f * density.density, max(280f * density.density, widthPx * 0.52f))
        } else {
            min(540f * density.density, max(360f * density.density, widthPx * 0.92f))
        }
        val swingPx = (if (isLandscape) 90f else 140f) * settings.lrSwing * density.density

        var cursorY = 0f
        val newBlocks = mutableListOf<KineticBlock>()
        val newAllWords = mutableListOf<KineticWordNode>()
        var lastAlign = -1
        var streak = 0

        for (i in displayLines.indices) {
            val curLine = displayLines[i]
            val prevLine = if (i > 0) displayLines[i - 1] else null

            if (prevLine != null) {
                val prevEndMs = prevLine.endMs ?: (prevLine.timeMs + 3000L)
                val gap = curLine.timeMs - prevEndMs
                if (gap >= 2800L) {
                    cursorY += 160f * vScale * density.density
                    newBlocks.add(
                        KineticBlock.InstrumentalBlock(
                            startMs = prevEndMs + 250L,
                            endMs = curLine.timeMs - 200L,
                            cx = 0f,
                            cy = cursorY,
                        ),
                    )
                    cursorY += 240f * vScale * density.density
                } else if (gap >= 1500L) {
                    cursorY += (340f + rand.nextFloat() * 70f) * vScale * (density.density * 0.75f)
                } else {
                    cursorY += (195f + rand.nextFloat() * 55f) * vScale * (density.density * 0.75f)
                }
            }

            val lastWordEnd = if (newAllWords.isNotEmpty()) newAllWords.last().endMs else 0L

            val rawWords: List<Pair<LrcWord, Long>> = if (curLine.words.isNotEmpty()) {
                var wordCursor = max(curLine.timeMs, lastWordEnd)
                curLine.words.mapIndexed { wIdx, w ->
                    val s = max(w.timeMs, wordCursor)
                    val rawEnd = w.endMs ?: curLine.words.getOrNull(wIdx + 1)?.timeMs ?: curLine.endMs ?: (s + 380L)
                    val dur = max(180L, rawEnd - w.timeMs)
                    val e = max(s + 180L, max(rawEnd, s + dur))
                    wordCursor = e
                    w.copy(timeMs = s, endMs = e) to e
                }
            } else {
                val tokens = curLine.text.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (tokens.isEmpty()) {
                    emptyList()
                } else {
                    val lineStart = max(curLine.timeMs, lastWordEnd)
                    val curEndMs = curLine.endMs
                    val lineDur = if (curEndMs != null && curEndMs > lineStart) {
                        curEndMs - lineStart
                    } else if (i < displayLines.size - 1 && displayLines[i + 1].timeMs > lineStart) {
                        (displayLines[i + 1].timeMs - lineStart).coerceIn(1200L, 7000L)
                    } else {
                        3500L
                    }

                    val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                    var runningStart = lineStart
                    tokens.mapIndexed { idx, token ->
                        val isLast = idx == tokens.lastIndex
                        val wordDur = if (isLast) {
                            max(200L, (lineStart + lineDur) - runningStart)
                        } else {
                            max(180L, (token.length.toFloat() / totalChars * lineDur).toLong())
                        }
                        val e = runningStart + wordDur
                        val w = LrcWord(timeMs = runningStart, text = token, endMs = e)
                        runningStart += wordDur
                        w to e
                    }
                }
            }

            if (rawWords.isEmpty()) continue

            val alignMode: Int
            if (streak < 2 && rand.nextFloat() < 0.36f && lastAlign != -1) {
                alignMode = lastAlign
                streak++
            } else {
                val roll = rand.nextFloat()
                alignMode = if (roll < 0.44f) 0 else if (roll < 0.88f) 2 else 1
                streak = 1
            }
            lastAlign = alignMode

            val measuredWords = rawWords.mapIndexed { wIdx, (word, wordEnd) ->
                val isHero = word.text.startsWith("*") && word.text.endsWith("*")
                val clean = if (isHero) word.text.removeSurrounding("*") else word.text

                val fontStyle: FontStyle = if (isHero && (wIdx + i) % 2 != 0) FontStyle.Italic else FontStyle.Normal
                val fontWeight: FontWeight = if (settings.isBold) {
                    if (isHero) FontWeight.Black else FontWeight.ExtraBold
                } else {
                    if (isHero) FontWeight.Bold else FontWeight.Medium
                }
                val fontSizeSp: Float = (when {
                    isHero -> 84f
                    clean.length <= 3 -> 58f
                    else -> 64f
                } * settings.fontSizeScale)

                val layoutResult = textMeasurer.measure(
                    text = clean,
                    style = TextStyle(
                        fontFamily = chosenFontFamily,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        fontSize = fontSizeSp.sp,
                    ),
                )

                KineticWordNode(
                    clean = clean,
                    isHero = isHero,
                    startMs = word.timeMs,
                    endMs = wordEnd,
                    gapAfter = clean.endsWith(",") || clean.endsWith(".") || clean.endsWith("!"),
                    x = 0f,
                    y = 0f,
                    width = layoutResult.size.width.toFloat(),
                    height = layoutResult.size.height.toFloat(),
                    waveY = (rand.nextFloat() - 0.5f) * 12f * density.density,
                    fontFamily = chosenFontFamily,
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    fontSizeSp = fontSizeSp,
                    anchors = KineticWordAnchors(Offset.Zero, Offset.Zero, Offset.Zero, 0f),
                    layoutResult = layoutResult,
                )
            }

            val baseWordGap = 52f * density.density
            val wrappedLines = mutableListOf<MutableList<KineticWordNode>>()
            wrappedLines.add(mutableListOf())
            var currentLineWidth = 0f

            for (node in measuredWords) {
                val extraGap = if (node.gapAfter) 38f * density.density else 0f
                val lastLine = wrappedLines.last()
                if (lastLine.isNotEmpty() && currentLineWidth + node.width > maxLineW && wrappedLines.size < 2) {
                    wrappedLines.add(mutableListOf(node))
                    currentLineWidth = node.width + baseWordGap + extraGap
                } else {
                    lastLine.add(node)
                    currentLineWidth += node.width + baseWordGap + extraGap
                }
            }

            val phraseNodes = mutableListOf<KineticWordNode>()
            for (lIdx in wrappedLines.indices) {
                val lineNodes = wrappedLines[lIdx]
                if (lIdx > 0) {
                    cursorY += (108f + rand.nextFloat() * 20f) * vScale * density.density
                }

                var totalLineWidth = 0f
                for (nIdx in lineNodes.indices) {
                    val n = lineNodes[nIdx]
                    totalLineWidth += n.width + if (nIdx < lineNodes.size - 1) baseWordGap + (if (n.gapAfter) 38f * density.density else 0f) else 0f
                }

                val startX = when (alignMode) {
                    0 -> -swingPx - totalLineWidth * 0.15f
                    2 -> swingPx - totalLineWidth * 0.85f
                    else -> -totalLineWidth * 0.5f
                }

                var curX = startX
                for (node in lineNodes) {
                    val wordY = cursorY + node.waveY
                    val placedNode = node.copy(
                        x = curX,
                        y = wordY,
                        anchors = KineticWordAnchors(
                            entryPt = Offset(curX, wordY),
                            exitPt = Offset(curX + node.width, wordY),
                            centerPt = Offset(curX + node.width * 0.5f, wordY),
                            baselineY = wordY + node.height * 0.34f,
                        ),
                    )
                    phraseNodes.add(placedNode)
                    newAllWords.add(placedNode)
                    curX += node.width + baseWordGap + (if (node.gapAfter) 38f * density.density else 0f)
                }
            }

            if (phraseNodes.isNotEmpty()) {
                newBlocks.add(
                    KineticBlock.PhraseBlock(
                        startMs = phraseNodes.first().startMs,
                        endMs = phraseNodes.last().endMs,
                        words = phraseNodes,
                    ),
                )
            }
        }

        layoutBlocks = newBlocks
        allWords = newAllWords
    }

    var frameTimeSec by remember { mutableFloatStateOf(0f) }
    var currentPlaybackMs by remember { mutableLongStateOf(0L) }
    var smoothPlaybackMs by remember { mutableFloatStateOf(0f) }
    var lastRawPositionMs by remember { mutableLongStateOf(-1L) }
    val scratchDiamondPath = remember { Path() }
    val performance = LocalPerformanceProfile.current
    // LOW tier drops the cinematic clock to ~30 fps; the glass material is untouched.
    val frameIntervalNanos = if (performance.isLow) 33_000_000L else 0L

    // The clock only runs while playing. Paused, it settles one frame and stops, so a paused
    // player costs no per-frame camera/starfield work.
    LaunchedEffect(currentIsPlaying) {
        var prevNanos = 0L
        var lastEmitNanos = 0L
        var firstFrame = true
        while (true) {
            withFrameNanos { nowNanos ->
                if (prevNanos == 0L) prevNanos = nowNanos
                val dt = min(0.05f, ((nowNanos - prevNanos) / 1_000_000_000f))
                prevNanos = nowNanos
                if (frameIntervalNanos > 0L && nowNanos - lastEmitNanos < frameIntervalNanos) {
                    return@withFrameNanos
                }
                lastEmitNanos = nowNanos

                val rawMs = currentPositionProvider()
                // positionProvider is already frame-interpolated at display refresh rate;
                // track it directly without artificial lag.
                smoothPlaybackMs = rawMs.toFloat()
                if (currentIsPlaying) {
                    frameTimeSec += dt
                }
                val nowMs = smoothPlaybackMs.toLong()
                currentPlaybackMs = nowMs

                var focusX = camX
                var focusY = camY
                var baseZoom = 0.88f

                val activeInst = layoutBlocks.find {
                    it is KineticBlock.InstrumentalBlock && nowMs >= it.startMs && nowMs <= it.endMs
                } as? KineticBlock.InstrumentalBlock

                if (activeInst != null) {
                    focusX = activeInst.cx
                    focusY = activeInst.cy
                    baseZoom = 0.92f
                } else if (allWords.isNotEmpty()) {
                    val candidate = activeWordIndex(allWords, nowMs)
                    val activeIdx = if (candidate >= 0 && nowMs <= allWords[candidate].endMs) candidate else -1
                    val activeWord = if (activeIdx >= 0) {
                        allWords[activeIdx]
                    } else {
                        allWords.find { it.startMs > nowMs } ?: allWords.last()
                    }

                    val targetWordX = activeWord.x + activeWord.width * 0.5f
                    val targetWordY = activeWord.y

                    if (activeIdx >= 0 && activeIdx < allWords.lastIndex) {
                        val nextWord = allWords[activeIdx + 1]
                        val remainMs = activeWord.endMs - nowMs
                        if (remainMs in 0..240L) {
                            val blend = ((240L - remainMs).toFloat() / 240f).coerceIn(0f, 1f)
                            val nextWordX = nextWord.x + nextWord.width * 0.5f
                            focusX = targetWordX + (nextWordX - targetWordX) * blend
                            focusY = targetWordY + (nextWord.y - targetWordY) * blend
                        } else {
                            focusX = targetWordX
                            focusY = targetWordY
                        }
                    } else {
                        focusX = targetWordX
                        focusY = targetWordY
                    }

                    val fitZoom = min(1.02f, (widthPx * 0.72f) / max(220f * density.density, activeWord.width + 90f * density.density))
                    baseZoom = if (activeWord.isHero) min(0.86f, fitZoom) else min(0.96f, fitZoom)
                }

                smoothCommonZoom += (settings.commonZoom - smoothCommonZoom) * min(1f, dt * 8f)
                val targetZoom = baseZoom * smoothCommonZoom

                val travelDist = kotlin.math.hypot(focusX - camX, focusY - camY)
                val leapBoost = (travelDist / (140f * density.density)).coerceIn(0f, 3.5f)
                val dynamicOmega = (5.2f * settings.cameraGlide * (1f + leapBoost)).coerceIn(4.8f, 16.5f)

                val omega = dynamicOmega
                val f = 1f + 2f * dt * omega
                val oo = omega * omega
                val hoo = dt * oo
                val hhoo = dt * hoo
                val detInv = 1f / (f + hhoo)

                camX = (f * camX + dt * camVx + hhoo * focusX) * detInv
                camVx = (camVx + hoo * (focusX - camX)) * detInv

                camY = (f * camY + dt * camVy + hhoo * focusY) * detInv
                camVy = (camVy + hoo * (focusY - camY)) * detInv

                camZoom = (f * camZoom + dt * camVz + hhoo * targetZoom) * detInv
                camVz = (camVz + hoo * (targetZoom - camZoom)) * detInv
            }
            if (firstFrame) {
                firstFrame = false
                // Paused: one settle frame has been drawn, so stop the clock entirely.
                if (!currentIsPlaying) return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val curWords = allWords
                    val cX = camX
                    val cY = camY
                    val cZoom = camZoom
                    val wPx = widthPx
                    val hPx = heightPx

                    val originX = if (isLandscape) wPx * 0.68f else wPx * 0.50f
                    val originY = if (isLandscape) hPx * 0.50f else hPx * 0.52f
                    val worldX = (tapOffset.x - originX) / cZoom + cX
                    val worldY = (tapOffset.y - originY) / cZoom + cY

                    val tappedWord = curWords.find { word ->
                        val tapPadding = 24f * density.density
                        worldX >= (word.x - tapPadding) &&
                            worldX <= (word.x + word.width + tapPadding) &&
                            worldY >= (word.y - word.height * 0.6f - tapPadding) &&
                            worldY <= (word.y + word.height * 0.6f + tapPadding)
                    }

                    if (tappedWord != null) {
                        onSeekTo(tappedWord.startMs)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            widthPx = size.width
            heightPx = size.height

            val w = size.width
            val h = size.height
            val nowMs = currentPlaybackMs
            val tSec = frameTimeSec
            val maxDim = max(w, h)

            // 1. Deep dark base for high-contrast kinetic typography
            drawRect(Color(0xFF050508), size = size)

            // 2. Animated multi-color palette radial glows across the middle of the screen
            val pal = settings.palette
        // LOW tier removes secondary glow and thins the starfield; the lyrics themselves stay.
        val glowFactor = if (performance.glowEnabled) settings.glowIntensity else 0f
        val starDensity = settings.sparkleDensity * if (performance.ambientMotionEnabled) 1f else 0.35f
            val drift1X = w * (0.28f + 0.16f * cos(tSec * 0.25f))
            val drift1Y = h * (0.34f + 0.12f * sin(tSec * 0.30f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(artPalette.vibrant.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(drift1X, drift1Y),
                    radius = maxDim * 0.62f,
                ),
                radius = maxDim * 0.62f,
                center = Offset(drift1X, drift1Y),
            )

            val drift2X = w * (0.74f + 0.14f * sin(tSec * 0.22f))
            val drift2Y = h * (0.64f + 0.10f * cos(tSec * 0.27f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(artPalette.lightVibrant.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(drift2X, drift2Y),
                    radius = maxDim * 0.58f,
                ),
                radius = maxDim * 0.58f,
                center = Offset(drift2X, drift2Y),
            )

            val ambientColor = Color(pal.ambientColorArgb)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ambientColor.copy(alpha = (ambientColor.alpha * 1.8f).coerceAtMost(0.45f)), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = maxDim * 0.55f,
                ),
                radius = maxDim * 0.55f,
                center = Offset(w * 0.5f, h * 0.5f),
            )

            // 3. 3-Layer Parallax Floating Starfield / Sparkles
            val starCount = (stars.size * starDensity.coerceIn(0f, 1f)).toInt()
            if (starCount > 0) {
                val activeInst = layoutBlocks.any {
                    it is KineticBlock.InstrumentalBlock && nowMs >= it.startMs && nowMs <= it.endMs
                }
                val boost = if (activeInst) 1.35f else 1.0f

                for (sIdx in 0 until starCount) {
                    val s = stars[sIdx]
                    val sx = ((s.x - camX * s.parallax + tSec * s.driftX) % w + w) % w
                    val sy = ((s.y - camY * s.parallax + tSec * s.driftY) % h + h) % h

                    val twinkle = (0.35f + 0.65f * (0.5f + 0.5f * sin(tSec * s.speed + s.phase))) * boost
                    val alpha = min(1f, twinkle * if (s.layer == 2) 0.85f else 0.65f)

                    if (s.layer == 2) {
                        val r = s.size * density.density
                        scratchDiamondPath.rewind()
                        scratchDiamondPath.moveTo(sx, sy - r * 1.9f)
                        scratchDiamondPath.lineTo(sx + r * 0.45f, sy - r * 0.45f)
                        scratchDiamondPath.lineTo(sx + r * 1.9f, sy)
                        scratchDiamondPath.lineTo(sx + r * 0.45f, sy + r * 0.45f)
                        scratchDiamondPath.lineTo(sx, sy + r * 1.9f)
                        scratchDiamondPath.lineTo(sx - r * 0.45f, sy + r * 0.45f)
                        scratchDiamondPath.lineTo(sx - r * 1.9f, sy)
                        scratchDiamondPath.lineTo(sx - r * 0.45f, sy - r * 0.45f)
                        scratchDiamondPath.close()
                        drawPath(scratchDiamondPath, color = Color(pal.midColorArgb).copy(alpha = alpha))
                    } else {
                        drawCircle(
                            color = Color.White.copy(alpha = alpha),
                            radius = s.size * density.density * 0.5f,
                            center = Offset(sx, sy),
                        )
                    }
                }
            }

            // 4. World Camera Transform with Viewport Culling
            val viewHalfH = (h * 0.65f) / max(0.2f, camZoom)
            val minVisibleY = camY - viewHalfH
            val maxVisibleY = camY + viewHalfH

            val originX = if (isLandscape) w * 0.68f else w * 0.50f
            val originY = if (isLandscape) h * 0.50f else h * 0.54f
            translate(left = originX, top = originY) {
                scale(scale = camZoom, pivot = Offset.Zero) {
                    translate(left = -camX, top = -camY) {
                        for (block in layoutBlocks) {
                            when (block) {
                                is KineticBlock.InstrumentalBlock -> {
                                    if (block.cy in (minVisibleY - 120f)..(maxVisibleY + 120f)) {
                                        drawInstrumentalDots(block, nowMs, tSec, pal, density.density)
                                    }
                                }
                                is KineticBlock.PhraseBlock -> {
                                    val isBlockActive = nowMs in block.startMs..block.endMs
                                    val isBlockPast = nowMs > block.endMs
                                    for (word in block.words) {
                                        if (word.y in (minVisibleY - 150f)..(maxVisibleY + 150f)) {
                                            drawWordSinglePass(
                                                word = word,
                                                nowMs = nowMs,
                                                isParentBlockActive = isBlockActive,
                                                isParentBlockPast = isBlockPast,
                                                pal = pal,
                                                settings = settings,
                                                density = density.density,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dual-line in the cinematic engine: the romanized words stay the moving hero, and the
        // original line is pinned above the controls so both are readable.
        if (displayMode == LyricsDisplayMode.DUAL_LINE) {
            val activeLineIndex by remember(syncedLines) {
                derivedStateOf { findActiveLyricIndex(syncedLines, currentPlaybackMs) }
            }
            val originalText = syncedLines.getOrNull(activeLineIndex)?.text
            if (!originalText.isNullOrBlank()) {
                Text(
                    text = originalText,
                    color = Color(settings.baseColor.colorArgb).copy(alpha = 0.62f),
                    fontFamily = chosenFontFamily,
                    fontSize = 14.sp,
                    fontWeight = if (settings.isBold) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 150.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawInstrumentalDots(
    blk: KineticBlock.InstrumentalBlock,
    nowMs: Long,
    tSec: Float,
    pal: LyricsGlowPalette,
    density: Float,
) {
    if (nowMs < blk.startMs - 800L || nowMs > blk.endMs + 500L) return
    val active = nowMs in blk.startMs..blk.endMs
    val p = if (active) {
        ((nowMs - blk.startMs).toFloat() / max(1f, (blk.endMs - blk.startMs).toFloat())).coerceIn(0f, 1f)
    } else {
        0f
    }

    val fadeAlpha = when {
        nowMs < blk.startMs -> ((nowMs - (blk.startMs - 800L)).toFloat() / 800f).coerceIn(0f, 1f)
        nowMs > blk.endMs -> (1f - ((nowMs - blk.endMs).toFloat() / 500f)).coerceIn(0f, 1f)
        else -> 1f
    }

    translate(left = blk.cx, top = blk.cy) {
        val spacing = 36f * density
        for (i in 0 until 3) {
            val dx = (i - 1) * spacing
            val wave = if (active) sin(tSec * 4.2f - i * 0.9f) * 8f * density else 0f
            val lit = active && p >= (i + 0.12f) / 3f
            val radius = (if (lit) 9.5f else 7.5f) * density

            val dotColor = if (lit) {
                Color(pal.litColorArgb).copy(alpha = fadeAlpha)
            } else {
                Color.White.copy(alpha = 0.28f * fadeAlpha)
            }

            if (lit) {
                drawCircle(
                    color = Color(pal.glowColorArgb).copy(alpha = 0.55f * fadeAlpha),
                    radius = radius * 1.8f,
                    center = Offset(dx, wave),
                )
            }

            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(dx, wave),
            )
        }
    }
}

private fun DrawScope.drawWordSinglePass(
    word: KineticWordNode,
    nowMs: Long,
    isParentBlockActive: Boolean,
    isParentBlockPast: Boolean,
    pal: LyricsGlowPalette,
    settings: LyricsSettings,
    density: Float,
) {
    val isPast = if (!isParentBlockActive && !isParentBlockPast) false else (isParentBlockPast || nowMs >= word.endMs)
    val isFuture = !isParentBlockPast && (!isParentBlockActive || nowMs < word.startMs)
    val isActive = isParentBlockActive && !isPast && !isFuture

    val progress = if (isPast) {
        1f
    } else if (isFuture) {
        0f
    } else {
        ((nowMs - word.startMs).toFloat() / max(1f, (word.endMs - word.startMs).toFloat())).coerceIn(0f, 1f)
    }

    val baseAlpha = when {
        isActive -> 1.0f
        isParentBlockActive && isPast -> 0.88f
        isPast -> settings.inactiveOpacity
        else -> (settings.inactiveOpacity * 0.45f).coerceIn(0.08f, 1f)
    }
    val popScale = if (isActive) (1f + 0.055f * settings.bounce * sin(progress * PI.toFloat())) else 1.0f
    val liftY = if (isActive) (-5f * settings.bounce * sin(progress * PI.toFloat()) * density) else 0f

    val wordCenterX = word.x + word.width * 0.5f
    val wordCenterY = word.y + liftY

    val baseColor = Color(settings.baseColor.colorArgb)

    translate(left = wordCenterX, top = wordCenterY) {
        scale(scale = popScale, pivot = Offset.Zero) {
            val leftX = -word.width * 0.5f
            val rightX = word.width * 0.5f

            if (isActive) {
                val litColor = Color(pal.litColorArgb)
                val midColor = Color(pal.midColorArgb)
                val unlitColor = baseColor.copy(alpha = 0.26f)
                val glowHaloColor = if (settings.glowIntensity > 0.02f) Color(pal.glowColorArgb) else Color.Transparent
                val glowBlur = 18f * (0.4f + 0.6f * sin(progress * PI.toFloat())) * density * settings.glowIntensity

                val p = progress.coerceIn(0f, 1f)
                val sweepBrush = when {
                    p <= 0.005f -> Brush.horizontalGradient(listOf(unlitColor, unlitColor), startX = leftX, endX = rightX)
                    p >= 0.995f -> Brush.horizontalGradient(listOf(litColor, litColor), startX = leftX, endX = rightX)
                    else -> {
                        val halfFeather = 0.08f
                        val s0 = (p - halfFeather).coerceIn(0.001f, 0.996f)
                        val s1 = p.coerceIn(s0 + 0.001f, 0.998f)
                        val s2 = (p + halfFeather).coerceIn(s1 + 0.001f, 0.999f)

                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to litColor,
                                s0 to litColor,
                                s1 to midColor,
                                s2 to unlitColor,
                                1.0f to unlitColor,
                            ),
                            startX = leftX,
                            endX = rightX,
                        )
                    }
                }

                drawText(
                    textLayoutResult = word.layoutResult,
                    brush = sweepBrush,
                    topLeft = Offset(leftX, -word.height * 0.5f),
                    shadow = if (settings.glowIntensity > 0.02f) {
                        Shadow(
                            color = glowHaloColor,
                            blurRadius = glowBlur,
                            offset = Offset.Zero,
                        )
                    } else {
                        null
                    },
                )
            } else {
                drawText(
                    textLayoutResult = word.layoutResult,
                    color = baseColor.copy(alpha = baseAlpha),
                    topLeft = Offset(leftX, -word.height * 0.5f),
                )
            }
        }
    }
}

/** Last word whose start is at or before [nowMs]; O(log N) for the sorted word list. */
private fun activeWordIndex(words: List<KineticWordNode>, nowMs: Long): Int {
    var low = 0
    var high = words.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (words[mid].startMs <= nowMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
