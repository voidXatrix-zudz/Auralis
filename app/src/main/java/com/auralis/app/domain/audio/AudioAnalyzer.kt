package com.auralis.app.domain.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * Result of an offline (file-only) analysis. Every field is measured from decoded samples; none of
 * it comes from the platform audio capture API. The verdict is deliberately cautious: a hi-res
 * container can be verified, but studio authenticity cannot be proven from the samples alone.
 */
data class AudioAnalysisResult(
    val sampleRateHz: Int,
    val channels: Int,
    val bitDepth: Int,
    val durationMs: Long,
    val decodedFrames: Long,
    val peakDb: Float,
    val rmsDb: Float,
    val cutoffHz: Int,
    val nyquistHz: Int,
    val analyzedWindows: Int,
    val startSilenceMs: Long,
    val endSilenceMs: Long,
    val unusedLowBits: Int,
    val suspiciousCutoff: Boolean,
    val possibleUpsampling: Boolean,
    val paddingDetected: Boolean,
    val hiResContainer: Boolean,
    val verdict: String,
)

/**
 * Offline audio analysis using MediaExtractor + MediaCodec and a local FFT.
 *
 * Decoding is bounded (every third window is transformed), so a normal album track finishes in a
 * few seconds without loading the whole file into memory. Coroutine cancellation is honoured in
 * every decode loop, which lets the UI abandon an analysis when the screen closes.
 */
object AudioAnalyzer {
    private const val FFT_SIZE = 4096
    private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
    private const val SILENCE = 0.002f
    private const val WINDOW_STRIDE = 3
    private const val PCM_8 = 3
    private const val PCM_16 = 2
    private const val PCM_FLOAT = 4
    private const val PCM_24 = 21
    private const val PCM_32 = 22

    suspend fun analyze(
        context: Context,
        uri: Uri,
        onProgress: suspend (Float, String) -> Unit,
    ): AudioAnalysisResult = withContext(Dispatchers.IO) {
        onProgress(0f, "Opening file…")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("This file has no decodable audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            val bitDepth = format.getInteger(KEY_BITS_PER_SAMPLE, 16).coerceAtLeast(8)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION, 0L)

            val state = AnalysisState(sampleRate, channels, durationUs)
            val isRaw = mime == "audio/raw" || mime.startsWith("audio/pcm")
            val decoded = if (isRaw) {
                decodeRaw(extractor, format, state, onProgress)
            } else {
                decodeCodec(extractor, format, state, onProgress)
            }
            if (!decoded || state.frames == 0L) error("No audio samples could be decoded")

            onProgress(0.96f, "Summarising spectrum…")
            buildResult(state, sampleRate, channels, bitDepth, durationUs)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeCodec(
        extractor: MediaExtractor,
        format: MediaFormat,
        state: AnalysisState,
        onProgress: suspend (Float, String) -> Unit,
    ): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (_: Exception) {
            error("No decoder is available for this audio format")
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = PCM_16
            var outputChannels = state.channels
            var lastReported = -1

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                        if (buffer == null) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            state.process(buffer, pcmEncoding, outputChannels)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        state.presentationUs = info.presentationTimeUs
                        val reported = state.progressPercent()
                        if (reported != lastReported) {
                            lastReported = reported
                            onProgress(0.02f + 0.92f * reported / 100f, "Decoding and measuring… $reported%")
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        pcmEncoding = changed.getInteger(MediaFormat.KEY_PCM_ENCODING, PCM_16)
                        outputChannels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                            .coerceAtLeast(1)
                    }
                }
            }
            return true
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private suspend fun decodeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        state: AnalysisState,
        onProgress: suspend (Float, String) -> Unit,
    ): Boolean {
        val pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, PCM_16)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, state.channels)
            .coerceAtLeast(1)
        val buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        var lastReported = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            state.process(buffer, pcmEncoding, channels)
            state.presentationUs = extractor.sampleTime
            extractor.advance()
            val reported = state.progressPercent()
            if (reported != lastReported) {
                lastReported = reported
                onProgress(0.02f + 0.92f * reported / 100f, "Reading PCM… $reported%")
            }
        }
        return true
    }

    private fun buildResult(
        state: AnalysisState,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        durationUs: Long,
    ): AudioAnalysisResult {
        val nyquist = sampleRate / 2
        val avg = FloatArray(FFT_SIZE / 2 + 1)
        if (state.windows > 0) {
            for (i in avg.indices) avg[i] = state.spectrum[i] / state.windows
        }
        var maxMagnitude = 0f
        for (i in 1 until avg.size) if (avg[i] > maxMagnitude) maxMagnitude = avg[i]
        var cutoffHz = nyquist
        if (maxMagnitude > 0f) {
            val threshold = maxMagnitude * 0.01f
            for (i in avg.lastIndex downTo 1) {
                if (avg[i] >= threshold) {
                    cutoffHz = (i.toDouble() * sampleRate / FFT_SIZE).toInt().coerceIn(0, nyquist)
                    break
                }
            }
        }

        val peakDb = if (state.peak > 0f) 20f * log10(state.peak.toDouble()).toFloat() else -120f
        val rms = if (state.frames > 0) kotlin.math.sqrt(state.sumSquares / state.frames) else 0.0
        val rmsDb = if (rms > 0.0) 20f * log10(rms).toFloat() else -120f
        val totalDurationMs = if (durationUs > 0) durationUs / 1000 else
            (state.frames * 1000L / sampleRate.coerceAtLeast(1))
        val startSilenceMs = if (state.firstNonSilent >= 0)
            state.firstNonSilent * 1000L / sampleRate.coerceAtLeast(1) else totalDurationMs
        val endSilenceMs = if (state.lastNonSilent >= 0)
            (state.frames - 1 - state.lastNonSilent) * 1000L / sampleRate.coerceAtLeast(1) else totalDurationMs

        val longEnough = state.frames >= sampleRate * 5L
        val suspiciousCutoff = longEnough && cutoffHz < nyquist * 0.90
        val possibleUpsampling = longEnough && sampleRate > 48_000 && cutoffHz < sampleRate * 0.46
        val paddingDetected = startSilenceMs > 1_500L || endSilenceMs > 1_500L
        val unusedLowBits = if (state.lowBitsOr == 0) 0 else
            Integer.numberOfTrailingZeros(state.lowBitsOr).coerceIn(0, 15)
        val hiResContainer = sampleRate > 48_000 || bitDepth > 16
        val verdict = when {
            !hiResContainer -> "Not a hi-res container. It may still sound excellent, but its format is standard-resolution."
            possibleUpsampling || suspiciousCutoff ->
                "Hi-res container, but the measured bandwidth suggests upsampling or a lossy origin. Authenticity cannot be guaranteed."
            else ->
                "Hi-res container with full-bandwidth content. The format is genuine, but studio authenticity cannot be guaranteed from samples alone."
        }

        return AudioAnalysisResult(
            sampleRateHz = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            durationMs = totalDurationMs,
            decodedFrames = state.frames,
            peakDb = peakDb,
            rmsDb = rmsDb,
            cutoffHz = cutoffHz,
            nyquistHz = nyquist,
            analyzedWindows = state.windows,
            startSilenceMs = startSilenceMs,
            endSilenceMs = endSilenceMs,
            unusedLowBits = unusedLowBits,
            suspiciousCutoff = suspiciousCutoff,
            possibleUpsampling = possibleUpsampling,
            paddingDetected = paddingDetected,
            hiResContainer = hiResContainer,
            verdict = verdict,
        )
    }

    private class AnalysisState(
        val sampleRate: Int,
        val channels: Int,
        private val durationUs: Long,
    ) {
        val spectrum = FloatArray(FFT_SIZE / 2 + 1)
        private val mono = FloatArray(FFT_SIZE)
        private var monoFill = 0
        private var windowCounter = 0
        var windows = 0
        var frames = 0L
        var peak = 0f
        var sumSquares = 0.0
        var lowBitsOr = 0
        var firstNonSilent = -1L
        var lastNonSilent = -1L
        var presentationUs = 0L

        fun progressPercent(): Int {
            if (durationUs <= 0L) return 0
            return (presentationUs * 100L / durationUs).toInt().coerceIn(0, 100)
        }

        fun process(buffer: ByteBuffer, encoding: Int, channelCount: Int) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val channels = channelCount.coerceAtLeast(1)
            val bytesPerSample = when (encoding) {
                PCM_8 -> 1
                PCM_FLOAT, PCM_32 -> 4
                PCM_24 -> 3
                else -> 2
            }
            val frameBytes = bytesPerSample * channels
            if (frameBytes <= 0) return
            val framesInBuffer = buffer.remaining() / frameBytes
            for (frame in 0 until framesInBuffer) {
                var monoSum = 0f
                for (channel in 0 until channels) {
                    val value = readSample(buffer, encoding)
                    monoSum += value.first
                    if (value.second != 0) lowBitsOr = lowBitsOr or value.second
                }
                val mixed = monoSum / channels
                val absolute = abs(mixed)
                if (absolute > peak) peak = absolute
                sumSquares += mixed.toDouble() * mixed
                if (absolute > SILENCE) {
                    if (firstNonSilent < 0) firstNonSilent = frames
                    lastNonSilent = frames
                }
                frames++
                mono[monoFill++] = mixed
                if (monoFill == FFT_SIZE) {
                    if (windowCounter % WINDOW_STRIDE == 0) addWindow()
                    windowCounter++
                    monoFill = 0
                }
            }
        }

        /** Returns the normalized sample and its integer value (0 for float/8-bit samples). */
        private fun readSample(buffer: ByteBuffer, encoding: Int): Pair<Float, Int> = when (encoding) {
            PCM_8 -> {
                val value = buffer.get().toInt() and 0xFF
                ((value - 128) / 128f) to 0
            }
            PCM_FLOAT -> buffer.float to 0
            PCM_24 -> {
                val b0 = buffer.get().toInt() and 0xFF
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt() and 0xFF
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                (value / 8_388_608f) to (value and 0xFFFFFF)
            }
            PCM_32 -> {
                val value = buffer.int
                (value / 2_147_483_648f) to (value and 0xFFFF)
            }
            else -> {
                val value = buffer.short.toInt()
                (value / 32_768f) to (value and 0xFFFF)
            }
        }

        private fun addWindow() {
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
                re[i] = (mono[i] * window).toFloat()
            }
            fft(re, im)
            for (k in 0..FFT_SIZE / 2) spectrum[k] += hypot(re[k].toDouble(), im[k].toDouble()).toFloat()
            windows++
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                val half = length / 2
                for (k in 0 until half) {
                    val evenRe = re[start + k]
                    val evenIm = im[start + k]
                    val oddRe = re[start + k + half]
                    val oddIm = im[start + k + half]
                    val vRe = oddRe * curRe - oddIm * curIm
                    val vIm = oddRe * curIm + oddIm * curRe
                    re[start + k] = evenRe + vRe
                    im[start + k] = evenIm + vIm
                    re[start + k + half] = evenRe - vRe
                    im[start + k + half] = evenIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
