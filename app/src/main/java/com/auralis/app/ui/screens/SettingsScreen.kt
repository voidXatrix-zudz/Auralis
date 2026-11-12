package com.auralis.app.ui.screens

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassSettingsScreen
import com.auralis.app.ui.components.PlayerBackgroundSelector
import com.auralis.app.ui.components.ThemeStyleSelector
import com.auralis.app.BuildConfig

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.ScreenPadding
import com.auralis.app.ui.components.SectionTitle
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.theme.Accents
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    sources: List<MusicSource>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveSource: (MusicSource) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenWrapped: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    updateChecking: Boolean = false,
    updateStatus: String? = null,
    updateAvailableVersion: String? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    youtubeSignedIn: Boolean = false,
    youtubeName: String = "",
    onYouTubeSignIn: () -> Unit = {},
    onYouTubeSignOut: () -> Unit = {},
    clerkSignedIn: Boolean = false,
    clerkName: String = "",
    clerkEmail: String = "",
    accountImageUrl: String? = null,
    onClerkSignOut: () -> Unit = {},
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
        GlassSettingsScreen(
            settings, sources, onUpdate, onAddFolder, onRemoveSource, onOpenAbout, onOpenEqualizer,
            onOpenWrapped, onOpenBackup, onOpenLogs, modifier, onBack, onCheckForUpdates, updateChecking,
            updateStatus, updateAvailableVersion,
            clerkSignedIn = clerkSignedIn, clerkName = clerkName, clerkEmail = clerkEmail,
            accountImageUrl = accountImageUrl,
            onClerkSignOut = onClerkSignOut,
        )
        return
    }
    var pendingRemoval by remember { mutableStateOf<MusicSource?>(null) }
    val profile = LocalPerformanceProfile.current
    val performanceReason = LocalPerformanceReason.current

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface) }

        // Clerk account (the app-level identity). Hidden on keyless builds where Clerk is not configured.
        if (clerkSignedIn) {
            item { SectionTitle("Account") }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(
                        imageUrl = accountImageUrl,
                        contentDescription = clerkName.ifBlank { "Account" },
                        iconTint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(clerkName.ifBlank { "Signed in" }, style = MaterialTheme.typography.bodyLarge)
                        if (clerkEmail.isNotBlank()) {
                            Text(
                                clerkEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onClerkSignOut) { Text("Sign out") }
                }
            }
        }

        item { SectionTitle("YouTube Music") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YouTube Music integration", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sign in to see your Home feed, liked songs and playlists. Off by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.youtubeIntegration,
                    onCheckedChange = { enabled -> onUpdate { it.copy(youtubeIntegration = enabled) } },
                )
            }
        }
        if (settings.youtubeIntegration) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (youtubeSignedIn) {
                        Text(
                            youtubeName.ifBlank { "Signed in" },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = onYouTubeSignOut) { Text("Sign out") }
                    } else {
                        Text("Not signed in", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onYouTubeSignIn) { Text("Sign in with Google") }
                    }
                }
            }
        }

        item { SectionTitle("Theme style") }
        item { ThemeStyleSelector(selected = settings.themeStyle, onSelect = { s -> onUpdate { it.copy(themeStyle = s) } }) }
        item { SectionTitle("Appearance") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { onUpdate { it.copy(theme = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        shape = CircleShape,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Accents.all.forEach { (name, color) ->
                    val selected = settings.accent == name
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent),
                                shape = CircleShape,
                            )
                            .clickable(role = Role.RadioButton) { onUpdate { it.copy(accent = name) } }
                            .semantics { contentDescription = "$name accent" },
                    )
                }
            }
        }

        item { SectionTitle("Player background") }
        item {
            PlayerBackgroundSelector(
                selected = settings.playerBackground,
                onSelect = { option -> onUpdate { it.copy(playerBackground = option) } },
            )
        }

        item { SectionTitle("Lyrics") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lyrics, null, modifier = Modifier.size(20.dp))
                    Text(
                        "Lyric size · ${settings.lyricSizeSp}sp",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Slider(
                    value = settings.lyricSizeSp.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(lyricSizeSp = value.roundToInt().coerceIn(14, 40)) }
                    },
                    valueRange = 14f..40f,
                    steps = 25,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-scroll lyrics", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Follow the current line during playback",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.lyricsAutoScroll,
                        onCheckedChange = { on -> onUpdate { it.copy(lyricsAutoScroll = on) } },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(20.dp))
                    Text(
                        "Lyrics offset · ${if (settings.lyricsOffsetMs > 0) "+" else ""}${settings.lyricsOffsetMs} ms",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Slider(
                    value = settings.lyricsOffsetMs.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(lyricsOffsetMs = (value / 50f).roundToInt() * 50) }
                    },
                    valueRange = -2_000f..2_000f,
                    steps = 79,
                )
                Text(
                    "Positive delays lyrics; negative shows them earlier. Applies to every song.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("Performance") }
        item {
            Text(
                "Automatic adjusts visual effects for this device. Audio quality is unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PerformanceMode.entries.size) { index ->
                    val mode = PerformanceMode.entries[index]
                    FilterChip(
                        selected = settings.performanceProfile == mode,
                        onClick = { onUpdate { it.copy(performanceProfile = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        shape = CircleShape,
                    )
                }
            }
            Text(
                text = if (settings.performanceProfile == PerformanceMode.AUTOMATIC) {
                    "Automatic · currently ${profile.tier.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" +
                        (performanceReason?.let { " ($it)" } ?: "")
                } else {
                    "Active profile: ${profile.tier.name.lowercase().replaceFirstChar { c -> c.uppercase() }}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        item { SectionTitle("Library") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-scan new songs", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Keep the library in sync with device storage and added folders",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoScanEnabled,
                    onCheckedChange = { on -> onUpdate { it.copy(autoScanEnabled = on) } },
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include subfolders", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Scan nested folders inside each chosen folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.includeSubfolders,
                    onCheckedChange = { on -> onUpdate { it.copy(includeSubfolders = on) } },
                )
            }
        }
        if (sources.isEmpty()) {
            item {
                Text(
                    "No folders added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(sources.size) { index ->
                val source = sources[index]
                FolderRow(source.name) {
                    IconButton(onClick = { pendingRemoval = source }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove ${source.name}")
                    }
                }
            }
        }
        item {
            androidx.compose.material3.Button(
                onClick = onAddFolder,
                shape = CircleShape,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add folder", modifier = Modifier.padding(start = 8.dp))
            }
        }

        item { SectionTitle("Tools") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolRow("Equalizer", "Presets, bands, bass boost and virtualizer", Icons.Rounded.Equalizer, onOpenEqualizer)
                ToolRow("AURALIS Wrapped", "Your listening recap and top tracks", Icons.Rounded.Insights, onOpenWrapped)
                ToolRow("Backup & restore", "Versioned JSON export and restore", Icons.Rounded.CloudUpload, onOpenBackup)
                ToolRow("Logs & diagnostics", "Device checks, app logs and export", Icons.Rounded.BugReport, onOpenLogs)
                ToolRow("Check for updates", updateSubtitle(updateChecking, updateStatus, updateAvailableVersion), Icons.Rounded.Refresh, onCheckForUpdates)
            }
        }

        item {
            TextButton(onClick = onOpenAbout, modifier = Modifier.padding(top = 24.dp)) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("About & privacy", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Text(
                "AURALIS ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }
    }
    }

    pendingRemoval?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove folder?") },
            text = { Text("“${source.name}” will be removed from your library. Files on your device are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveSource(source)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

private fun updateSubtitle(checking: Boolean, status: String?, version: String?): String = when {
    checking -> "Checking..."
    version != null -> "Version $version is available"
    !status.isNullOrBlank() -> status
    else -> "Check GitHub for a newer release"
}

@Composable
private fun ToolRow(label: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
