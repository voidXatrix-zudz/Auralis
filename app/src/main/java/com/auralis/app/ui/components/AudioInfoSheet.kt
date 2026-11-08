package com.auralis.app.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.core.formatDuration
import com.auralis.app.core.formatFileSize
import com.auralis.app.domain.audio.isHiRes
import com.auralis.app.domain.audio.isLossless
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.ReplayGainMode
import com.auralis.app.domain.model.Track
import com.auralis.app.playback.AudioEffectsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class AudioInfoRow(val label: String, val value: String)

data class AudioInfoSection(val title: String, val rows: List<AudioInfoRow>)

data class AudioInfoReport(
    val title: String,
    val header: String,
    val sections: List<AudioInfoSection>,
) {
    fun asPlainText(): String = buildString {
        appendLine("AURALIS audio info")
        appendLine(title)
        appendLine(header)
        sections.forEach { section ->
            appendLine()
            appendLine(section.title)
            section.rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
        }
    }.trimEnd()
}

private const val UNKNOWN = "Not reported by Android"
private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

/**
 * Reads file metadata plus the live Android audio path. Everything is best-effort: any API that is
 * missing on the current OS or device falls back to [UNKNOWN] instead of inventing a value.
 */
suspend fun collectAudioInfo(
    context: Context,
    track: Track,
    settings: AppSettings,
): AudioInfoReport = withContext(Dispatchers.IO) {
    val format = readFormat(context, track.uri)
    val sampleRate = track.sampleRateHz ?: format?.int(MediaFormat.KEY_SAMPLE_RATE)
    val bitDepth = track.bitDepth ?: format?.int(KEY_BITS_PER_SAMPLE)
    val channels = track.channels ?: format?.int(MediaFormat.KEY_CHANNEL_COUNT)
    val bitrate = track.bitrateBps ?: format?.int(MediaFormat.KEY_BIT_RATE)
    val durationMs = track.durationMs.takeIf { it > 0 }
        ?: format?.long(MediaFormat.KEY_DURATION)?.div(1000L)
    val mime = track.mime.takeIf { it.isNotBlank() }
        ?: format?.string(MediaFormat.KEY_MIME)
    val lossless = track.isLossless
    val codec = track.codec?.takeIf { it.isNotBlank() } ?: mime
    val folder = folderName(track.relativePath)

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val activeOutput = activeOutputDevice(audioManager)
    val outputRate = propertyInt(audioManager, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
    val bufferFrames = propertyInt(audioManager, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
    val nativeRate = runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }
        .getOrNull()?.takeIf { it > 0 }
    val usb = activeOutput?.takeIf { it.isUsb() } ?: allOutputs(audioManager).firstOrNull { it.isUsb() }
    val decoder = decoderInfo(mime)
    val resampling = when {
        sampleRate == null || outputRate == null -> UNKNOWN
        sampleRate == outputRate -> "No (reported ${formatSampleRate(sampleRate)})"
        else -> "Yes (${formatSampleRate(sampleRate)} → ${formatSampleRate(outputRate)})"
    }
    val compression = compressionPercent(lossless, sampleRate, bitDepth, channels, durationMs, track.sizeBytes)
    val spatializer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
        runCatching { audioManager?.spatializer?.isAvailable }.getOrNull()
    } else null
    val currentVolume = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
    val maxVolume = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
    val effects = AudioEffectsEngine.current()

    val header = listOfNotNull(
        (codec ?: track.ext).uppercase().takeIf { it.isNotBlank() },
        bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
        sampleRate?.takeIf { it > 0 }?.let(::formatSampleRate),
    ).joinToString(" · ").ifBlank { track.displayName }

    AudioInfoReport(
        title = track.displayName,
        header = header,
        sections = listOf(
            AudioInfoSection(
                "Source file",
                listOf(
                    AudioInfoRow("Quality", when {
                        track.isHiRes -> "Hi-Res lossless"
                        lossless -> "Lossless"
                        else -> "Lossy"
                    }),
                    AudioInfoRow("Format", track.ext.uppercase().ifBlank { UNKNOWN }),
                    AudioInfoRow("Codec / MIME", listOfNotNull(codec, mime).distinct().joinToString(" · ").ifBlank { UNKNOWN }),
                    AudioInfoRow("Sample rate", sampleRate?.let(::formatSampleRate) ?: UNKNOWN),
                    AudioInfoRow("Bit depth", bitDepth?.let { "$it-bit" } ?: UNKNOWN),
                    AudioInfoRow("Channels", channels?.let(::channelLabel) ?: UNKNOWN),
                    AudioInfoRow("Bitrate", bitrate?.toLong()?.let(::formatBitrate) ?: UNKNOWN),
                    AudioInfoRow("Duration", durationMs?.let(::formatDuration) ?: UNKNOWN),
                    AudioInfoRow("File size", formatFileSize(track.sizeBytes)),
                    AudioInfoRow("Compression", compression),
                    AudioInfoRow("Folder name", folder),
                ),
            ),
            AudioInfoSection(
                "Signal",
                listOf(
                    AudioInfoRow(
                        "Uncompressed data rate",
                        if (sampleRate != null && bitDepth != null && channels != null) {
                            formatBitrate(sampleRate.toLong() * bitDepth * channels)
                        } else UNKNOWN,
                    ),
                    AudioInfoRow("Nyquist frequency", sampleRate?.let { formatSampleRate(it / 2) } ?: UNKNOWN),
                    AudioInfoRow(
                        "Theoretical dynamic range",
                        bitDepth?.let { String.format(Locale.US, "%.1f dB", 6.02 * it) } ?: UNKNOWN,
                    ),
                ),
            ),
            AudioInfoSection(
                "Loudness",
                listOf(
                    AudioInfoRow("ReplayGain", replayGainLabel(track)),
                    AudioInfoRow(
                        "Volume normalization",
                        if (settings.replayGain == ReplayGainMode.OFF) "Off"
                        else "On (${settings.replayGain.name.lowercase(Locale.US)})",
                    ),
                ),
            ),
            AudioInfoSection(
                "Processing",
                listOf(
                    AudioInfoRow("Player", "AURALIS player (Media3 ExoPlayer)"),
                    AudioInfoRow("Signal path", "ExoPlayer → AudioEffectsEngine → Android AudioTrack"),
                    AudioInfoRow("USB DAC", usb?.let { "Connected (${deviceName(it)})" } ?: "Not connected"),
                    AudioInfoRow("Hi-res source", if (track.isHiRes) "Yes" else "No"),
                    AudioInfoRow(
                        "Decoder",
                        decoder?.let { (name, type) -> "$name ($type)" } ?: UNKNOWN,
                    ),
                    AudioInfoRow(
                        "EQ",
                        when {
                            !settings.eqEnabled -> "Off"
                            effects.equalizerAvailable -> "On"
                            else -> "On (hardware EQ not attached)"
                        },
                    ),
                    AudioInfoRow("Resampling", resampling),
                    AudioInfoRow("Bit-perfect", "No — Android mixes app audio through the system mixer"),
                ),
            ),
            AudioInfoSection(
                "Output",
                listOf(
                    AudioInfoRow("Active device", activeOutput?.let(::deviceName) ?: UNKNOWN),
                    AudioInfoRow("Output sample rate", outputRate?.let(::formatSampleRate) ?: UNKNOWN),
                    AudioInfoRow("System default sample rate", nativeRate?.let(::formatSampleRate) ?: UNKNOWN),
                    AudioInfoRow(
                        "Supported device sample rates",
                        activeOutput?.sampleRates?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { formatSampleRate(it) } ?: UNKNOWN,
                    ),
                    AudioInfoRow(
                        "Supported device formats",
                        activeOutput?.encodings?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { encodingLabel(it) } ?: UNKNOWN,
                    ),
                    AudioInfoRow(
                        "Mixer buffer",
                        if (bufferFrames != null && outputRate != null) {
                            "$bufferFrames frames (${String.format(Locale.US, "%.1f", bufferFrames * 1000.0 / outputRate)} ms)"
                        } else UNKNOWN,
                    ),
                    AudioInfoRow(
                        "Low-latency support",
                        yesNo(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)),
                    ),
                    AudioInfoRow(
                        "Pro audio support",
                        yesNo(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)),
                    ),
                    AudioInfoRow(
                        "Spatializer",
                        when {
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 -> "Not available on this Android version"
                            spatializer == null -> UNKNOWN
                            spatializer -> "Available"
                            else -> "Not available"
                        },
                    ),
                    AudioInfoRow(
                        "Volume",
                        if (currentVolume != null && maxVolume != null) "$currentVolume / $maxVolume" else UNKNOWN,
                    ),
                ),
            ),
            AudioInfoSection(
                "Audio Quality Analysis",
                listOf(
                    AudioInfoRow("Offline analysis", "FFT spectrum, peak level, cutoff, upsampling, padding and unused low bits"),
                    AudioInfoRow("Guarantee", "Authenticity cannot be guaranteed from the samples alone"),
                ),
            ),
        ),
    )
}

private fun readFormat(context: Context, uri: String): MediaFormat? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val index = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.getTrackFormat(index)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun MediaFormat.int(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
private fun MediaFormat.long(key: String): Long? = runCatching { getLong(key) }.getOrNull()
private fun MediaFormat.string(key: String): String? = runCatching { getString(key) }.getOrNull()

private fun propertyInt(manager: AudioManager?, key: String): Int? =
    runCatching { manager?.getProperty(key)?.toIntOrNull() }.getOrNull()

private fun activeOutputDevice(manager: AudioManager?): AudioDeviceInfo? {
    if (manager == null) return null
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            manager.getAudioDevicesForAttributes(attributes).firstOrNull()
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
        }
    }.getOrNull()
}

private fun allOutputs(manager: AudioManager?): List<AudioDeviceInfo> =
    runCatching { manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty() }
        .getOrDefault(emptyList())

private fun AudioDeviceInfo.isUsb(): Boolean =
    type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY

private fun deviceName(device: AudioDeviceInfo): String {
    val name = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    val type = deviceTypeLabel(device.type)
    return if (name != null) "$name · $type" else type
}

private fun deviceTypeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
    AudioDeviceInfo.TYPE_DOCK -> "Dock"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out (analog)"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line out (digital)"
    AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
    AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
    else -> "Output device"
}

private fun encodingLabel(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
    AudioFormat.ENCODING_AC3 -> "AC-3"
    AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
    AudioFormat.ENCODING_DTS -> "DTS"
    AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
    AudioFormat.ENCODING_AAC_LC -> "AAC-LC"
    AudioFormat.ENCODING_AAC_HE_V1 -> "AAC-HE"
    AudioFormat.ENCODING_AAC_HE_V2 -> "AAC-HE v2"
    AudioFormat.ENCODING_AAC_XHE -> "AAC-xHE"
    AudioFormat.ENCODING_OPUS -> "Opus"
    AudioFormat.ENCODING_MP3 -> "MP3"
    else -> "Format $encoding"
}

private fun decoderInfo(mime: String?): Pair<String, String>? {
    if (mime.isNullOrBlank()) return null
    val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull() ?: return null
    val info = list.codecInfos.firstOrNull { codec ->
        !codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    } ?: return null
    val software = info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.")
    return info.name to if (software) "Software" else "Hardware"
}

private fun replayGainLabel(track: Track): String {
    val values = listOfNotNull(
        track.replayGainTrackDb?.let { "Track ${formatDb(it)}" },
        track.replayGainAlbumDb?.let { "Album ${formatDb(it)}" },
    )
    return values.joinToString(" · ").ifBlank { "No ReplayGain tags" }
}

private fun compressionPercent(
    lossless: Boolean,
    sampleRate: Int?,
    bitDepth: Int?,
    channels: Int?,
    durationMs: Long?,
    sizeBytes: Long,
): String {
    if (!lossless) return "Not applicable (lossy)"
    if (sampleRate == null || bitDepth == null || channels == null || durationMs == null ||
        durationMs <= 0 || sizeBytes <= 0
    ) return UNKNOWN
    val uncompressed = sampleRate.toDouble() * (bitDepth / 8.0) * channels * (durationMs / 1000.0)
    if (uncompressed <= 0.0) return UNKNOWN
    val percent = ((1.0 - sizeBytes / uncompressed) * 100.0).coerceIn(0.0, 99.9)
    return String.format(Locale.US, "%.1f%% smaller than PCM", percent)
}

private fun folderName(relativePath: String): String {
    val normalized = relativePath.replace('\\', '/')
    val folder = normalized.substringBeforeLast('/', "").substringAfterLast('/')
    return folder.ifBlank { "Library root" }
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "1 (mono)"
    2 -> "2 (stereo)"
    else -> "$channels channels"
}

private fun formatSampleRate(hz: Int): String =
    if (hz >= 1000) {
        String.format(Locale.US, "%.1f kHz", hz / 1000.0).replace(".0 kHz", " kHz")
    } else "$hz Hz"

private fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(Locale.US, "%.0f kbps", bps / 1000.0)
    else -> "$bps bps"
}

private fun formatDb(db: Float): String = String.format(Locale.US, "%+.1f dB", db)

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioInfoSheet(
    track: Track,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onOpenAnalysis: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember(track.id) { mutableStateOf<AudioInfoReport?>(null) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(track.id, settings.eqEnabled, settings.replayGain) {
        report = null
        error = null
        runCatching { collectAudioInfo(context, track, settings) }
            .onSuccess { report = it }
            .onFailure { error = it.localizedMessage ?: "Could not read audio information" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Audio info", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        report?.header ?: track.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        report?.let {
                            clipboard.setText(AnnotatedString(it.asPlainText()))
                            copied = true
                        }
                    },
                    enabled = report != null,
                ) { Text(if (copied) "Copied" else "Copy report") }
            }
            Spacer(Modifier.height(8.dp))
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                report == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Reading file and device capabilities…",
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val sections = report!!.sections
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(sections) { section -> AudioInfoCard(section) }
                        item {
                            Button(
                                onClick = onOpenAnalysis,
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Open audio quality analysis", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioInfoCard(section: AudioInfoSection) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            section.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        row.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.value,
                        modifier = Modifier.weight(1.15f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}
