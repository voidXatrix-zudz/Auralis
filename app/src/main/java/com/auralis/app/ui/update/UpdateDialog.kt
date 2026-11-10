package com.auralis.app.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.auralis.app.data.update.UpdateUiState
import java.util.Locale

/**
 * Global "Update available" dialog. Rendered from the root so it appears over any screen, in
 * both the Normal and the Glassmorphic theme (glass gets a translucent dark card).
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    glass: Boolean,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val release = state.available ?: return
    val secondary = if (glass) Color.White.copy(alpha = 0.68f) else MaterialTheme.colorScheme.onSurfaceVariant
    val progress = state.downloadProgress

    Dialog(onDismissRequest = onLater) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (glass) Color(0xF2181920) else MaterialTheme.colorScheme.surface,
            contentColor = if (glass) Color.White else MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (glass) 0.dp else 4.dp,
            border = if (glass) BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "New version ${release.version}  ·  you have ${state.currentVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
                Text(
                    "APK ${formatSize(release.apkSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Release notes",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 148.dp)
                        .padding(top = 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        release.notes.ifBlank { "No release notes were provided." },
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading… ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancelDownload) { Text("Cancel") }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onLater) { Text("Later", color = secondary) }
                        Spacer(Modifier.size(6.dp))
                        when {
                            state.readyToInstall -> Button(onClick = onInstall) { Text("Install") }
                            release.apkUrl != null -> Button(onClick = onUpdate) { Text("Update") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onSkip, enabled = !state.readyToInstall) {
                            Text("Skip this version", color = secondary)
                        }
                        TextButton(onClick = onOpenPage) {
                            Text(
                                if (state.downloadFailed) "Download failed · Open on GitHub" else "Open on GitHub",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (release.apkUrl == null) {
                        Text(
                            "This release has no APK attached. Open GitHub to download it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "size unknown"
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%d KB", (bytes / 1024L).coerceAtLeast(1L))
}