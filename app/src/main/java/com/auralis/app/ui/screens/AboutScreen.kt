package com.auralis.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.app.BuildConfig
import com.auralis.app.ui.theme.Auralis

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("About AURALIS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "All your music stays on this device. AURALIS has no account, no server and never uploads your files. " +
                "Your library index, playlists and settings are stored locally.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Audio is played through Android's audio pipeline. AURALIS does not claim bit-perfect or high-resolution " +
                "output unless your device and connected hardware support it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Auralis.extras.mutedForeground,
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelLarge,
            color = Auralis.extras.mutedForeground,
        )
        Text(
            "Typefaces: Manrope and Sora, licensed under the SIL Open Font License 1.1.",
            style = MaterialTheme.typography.bodySmall,
            color = Auralis.extras.mutedForeground,
        )
    }
}
