package com.auralis.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auralis.app.core.formatDuration
import com.auralis.app.domain.audio.AudioAnalysisResult
import com.auralis.app.domain.audio.AudioAnalyzer
import com.auralis.app.domain.audio.isHiRes
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ScreenPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dedicated offline analysis screen. It only runs when the user taps Check, so opening the screen
 * costs nothing. All measurements come from decoded samples, never from live audio capture.
 */
@Composable
fun AudioAnalysisScreen(track: Track, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember(track.id) { mutableStateOf(false) }
    var progress by remember(track.id) { mutableFloatStateOf(0f) }
    var status by remember(track.id) { mutableStateOf("") }
    var result by remember(track.id) { mutableStateOf<AudioAnalysisResult?>(null) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }

    fun runAnalysis() {
        if (running) return
        running = true
        result = null
        error = null
        progress = 0f
        status = "Preparing…"
        scope.launch {
            try {
                val analysis = AudioAnalyzer.analyze(context, Uri.parse(track.uri)) { value, message ->
                    withContext(Dispatchers.Main.immediate) {
                        progress = value
                        status = message
                    }
                }
                result = analysis
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.localizedMessage ?: "The file could not be analysed"
            } finally {
                running = false
            }
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Audio quality analysis", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${track.title.ifBlank { track.displayName }} · ${formatDuration(track.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Analytics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            "Hi-Res Authenticity",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (track.isHiRes) {
                            "This file advertises a hi-res container. Check it offline to see whether the decoded bandwidth and bit usage support that claim."
                        } else {
                            "This file is standard-resolution. The check still measures bandwidth, peaks and bit usage."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = ::runAnalysis,
                        enabled = !running,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (result == null) "Check" else "Check again")
                    }
                }
            }
        }

        if (running) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        status.ifBlank { "Analysing…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }

        result?.let { value ->
            item { AnalysisCard("Peak level", listOf(
                "Peak" to String.format(Locale.US, "%.1f dBFS", value.peakDb),
                "RMS" to String.format(Locale.US, "%.1f dBFS", value.rmsDb),
            )) }
            item { AnalysisCard("Frequency spectrum", listOf(
                "Measured cutoff" to formatHz(value.cutoffHz),
                "Nyquist limit" to formatHz(value.nyquistHz),
                "FFT windows" to value.analyzedWindows.toString(),
                "Decoded frames" to value.decodedFrames.toString(),
            )) }
            item { AnalysisCard("Bandwidth checks", listOf(
                "Suspicious cutoff" to if (value.suspiciousCutoff) {
                    "Yes — energy ends well below Nyquist"
                } else "Not detected",
                "Upsampling" to if (value.possibleUpsampling) {
                    "Possible — bandwidth is low for this container rate"
                } else "Not detected",
                "Padding" to if (value.paddingDetected) {
                    "Possible — start ${value.startSilenceMs} ms, end ${value.endSilenceMs} ms of silence"
                } else "Not detected",
                "Unused low bits" to if (value.unusedLowBits >= 2) {
                    "${value.unusedLowBits} bits always zero"
                } else "None detected",
            )) }
            item { AnalysisCard("Authenticity verdict", listOf("Result" to value.verdict)) }
            item {
                Text(
                    "Offline analysis measures the decoded samples only. It can suggest upsampling, padding or a lossy origin, but it cannot prove or guarantee studio authenticity.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(title: String, rows: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1.3f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun formatHz(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000.0).replace(".0 kHz", " kHz")
    else "$hz Hz"
