package com.auralis.app.ui.settings

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.app.data.lyrics.LyricsProviderCatalog
import com.auralis.app.data.metadata.MetadataProviderCatalog
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.domain.model.ReplayGainMode
import com.auralis.app.domain.model.StreamQuality
import com.auralis.app.domain.model.StreamSourceMode
import com.auralis.app.domain.model.ThemeMode
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.components.PlayerBackgroundSelector
import com.auralis.app.ui.components.ThemeStyleSelector
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.theme.Accents
import com.auralis.app.ui.theme.GlassTokens
import kotlin.math.roundToInt

@Composable
private fun qualityValue(q: StreamQuality): String =
    if (q == StreamQuality.BEST) "Best" else "${q.label} · ~${q.approxKbps} kbps"

private fun humanBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    else -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
}

/** The dispatcher rendered by AuralisRoot for each Settings sub-page destination. */
@Composable
fun SettingsSubScreen(
    destination: Destination,
    settings: AppSettings,
    sources: List<MusicSource>,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    when (destination) {
        Destination.SettingsPlayback -> PlaybackSettingsPage(settings, actions, onBack)
        Destination.SettingsMetadataLyrics -> MetadataLyricsSettingsPage(settings, actions, onBack)
        Destination.SettingsDownloads -> DownloadsSettingsPage(settings, actions, onBack)
        Destination.SettingsYouTubeMusic -> YouTubeMusicSettingsPage(settings, actions, onBack)
        Destination.SettingsAppearance -> AppearanceSettingsPage(settings, actions, onBack)
        Destination.SettingsPerformance -> PerformanceSettingsPage(settings, actions, onBack)
        Destination.SettingsStorage -> StorageSettingsPage(settings, sources, actions, onBack)
        Destination.SettingsAbout -> AboutSettingsPage(actions, onBack)
        else -> PlaybackSettingsPage(settings, actions, onBack)
    }
}

@Composable
private fun StreamQualityRow(
    title: String,
    quality: StreamQuality,
    onPick: (StreamQuality) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingsValueRow(icon = Icons.Rounded.Wifi, title = title, value = qualityValue(quality)) { open = true }
    if (open) {
        ChoiceDialog(
            title = title,
            helper = "What the player streams over the network.",
            options = StreamQuality.entries.map { it to qualityValue(it) },
            selected = quality,
            onSelect = { onPick(it) },
            onDismiss = { open = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun PlaybackSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    SettingsPage("Playback & Quality", onBack) {
        item { SettingsSectionLabel("Streaming") }
        item {
            SettingsGroupCard {
                StreamQualityRow("Wi-Fi streaming", settings.wifiStreamQuality) { q -> actions.onUpdate { it.copy(wifiStreamQuality = q) } }
                SettingsDivider()
                StreamQualityRow("Mobile data streaming", settings.mobileStreamQuality) { q -> actions.onUpdate { it.copy(mobileStreamQuality = q) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Sync, "Data saver", "Stream Low on cellular and stop prefetching there", settings.dataSaverEnabled) { on ->
                    actions.onUpdate { it.copy(dataSaverEnabled = on) }
                }
                SettingsDivider()
                var open by remember { mutableStateOf(false) }
                SettingsValueRow(
                    Icons.Rounded.SwapHoriz,
                    "Stream source",
                    value = when (settings.streamSource) {
                        StreamSourceMode.AUTO -> "Auto"
                        StreamSourceMode.NEWPIPE -> "NewPipeExtractor only"
                        StreamSourceMode.INNERTUBE -> "InnerTube only"
                    },
                    summary = "Auto picks the fastest resolver",
                ) { open = true }
                if (open) {
                    ChoiceDialog(
                        title = "Stream source",
                        helper = "Auto races both resolvers and keeps the first result.",
                        options = listOf(
                            StreamSourceMode.AUTO to "Auto (fastest)",
                            StreamSourceMode.NEWPIPE to "NewPipeExtractor only",
                            StreamSourceMode.INNERTUBE to "InnerTube only",
                        ),
                        selected = settings.streamSource,
                        onSelect = { picked -> actions.onUpdate { it.copy(streamSource = picked) } },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        item { SettingsHelperText("Quality uses the closest audio-only stream at or below the target; Best uses the highest available. Downloads use the Wi-Fi quality.") }

        item { SettingsSectionLabel("Playback") }
        item {
            SettingsGroupCard {
                SettingsRow(Icons.Rounded.Equalizer, "Equalizer", summary = "Presets, bands, bass boost and virtualizer", onClick = actions.onOpenEqualizer)
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Equalizer, "Equalizer enabled", "Apply the selected preset", settings.eqEnabled) { on -> actions.onUpdate { it.copy(eqEnabled = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.AutoAwesome, "Bass boost", "Adds low-end weight (${settings.bassDb.roundToInt()} dB)", settings.bassBoostEnabled) { on -> actions.onUpdate { it.copy(bassBoostEnabled = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.AutoAwesome, "Virtualizer", "Widens the stereo image", settings.virtualizerEnabled) { on -> actions.onUpdate { it.copy(virtualizerEnabled = on) } }
            }
        }
        item {
            SettingsGroupCard {
                SliderSetting("Playback speed", "%.2f×".format(settings.speed), settings.speed, 0.5f..2f, 0) { v -> actions.onUpdate { it.copy(speed = v) } }
                SettingsDivider()
                SliderSetting("Balance", balanceLabel(settings.balance), settings.balance, -1f..1f, 0) { v -> actions.onUpdate { it.copy(balance = v) } }
                SettingsDivider()
                SliderSetting("Crossfade", "${settings.crossfadeSeconds}s", settings.crossfadeSeconds.toFloat(), 0f..12f, 11) { v -> actions.onUpdate { it.copy(crossfadeSeconds = v.roundToInt()) } }
            }
        }
        item { SettingsSectionLabel("Replay gain") }
        item {
            SettingsGroupCard {
                LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReplayGainMode.entries.size) { index ->
                        val mode = ReplayGainMode.entries[index]
                        FilterChip(selected = settings.replayGain == mode, onClick = { actions.onUpdate { it.copy(replayGain = mode) } }, label = { Text(label(mode.name)) }, shape = CircleShape)
                    }
                }
            }
        }
        item { SettingsSectionLabel("Controls") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(Icons.Rounded.Sync, "Resume on reconnect", "Continue after headphones or a car kit reconnect", settings.resumeOnOutputReconnect) { on -> actions.onUpdate { it.copy(resumeOnOutputReconnect = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Speed, "Keep screen on in car", "Prevents the display sleeping while driving", settings.carKeepScreenOn) { on -> actions.onUpdate { it.copy(carKeepScreenOn = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.SwapHoriz, "Swipe gestures", "Swipe artwork to change tracks", settings.gestureSwipeEnabled) { on -> actions.onUpdate { it.copy(gestureSwipeEnabled = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.SwapHoriz, "Seek gestures", "Horizontal drag to seek", settings.gestureSeekEnabled) { on -> actions.onUpdate { it.copy(gestureSeekEnabled = on) } }
            }
        }
    }
}

private fun balanceLabel(balance: Float): String = when {
    balance < -0.02f -> "Left ${(-balance * 100).roundToInt()}%"
    balance > 0.02f -> "Right ${(balance * 100).roundToInt()}%"
    else -> "Centre"
}

@Composable
private fun SliderSetting(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun MetadataLyricsSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    SettingsPage("Metadata & Lyrics", onBack) {
        item { SettingsSectionLabel("Lyrics providers · order = lookup order") }
        item {
            SettingsGroupCard {
                val order = settings.lyricsProviderOrder
                order.forEachIndexed { index, id ->
                    val info = LyricsProviderCatalog.ALL.firstOrNull { it.id == id } ?: return@forEachIndexed
                    if (index > 0) SettingsDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(info.label, style = MaterialTheme.typography.titleSmall)
                            Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { actions.onMoveLyricsProvider(id, -1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { actions.onMoveLyricsProvider(id, 1) }, enabled = index < order.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
                        }
                        Switch(checked = id in settings.lyricsProvidersEnabled, onCheckedChange = { on ->
                            val next = if (on) settings.lyricsProvidersEnabled + id else settings.lyricsProvidersEnabled - id
                            actions.onUpdate { it.copy(lyricsProvidersEnabled = next) }
                            actions.onLyricsSettingsChanged()
                        })
                    }
                }
            }
        }
        item { SettingsHelperText("Marked providers are community or unofficial sources. If every provider is off, the lyrics page shows “Lyrics are off”.") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(Icons.Rounded.Lyrics, "Prefer synced lyrics", "Keep looking for a timestamped result before accepting plain text", settings.preferSyncedLyrics) { on -> actions.onUpdate { it.copy(preferSyncedLyrics = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.LibraryMusic, "Overwrite existing tags", "Auto-fix tags may replace non-empty fields", settings.overwriteExistingTags) { on -> actions.onUpdate { it.copy(overwriteExistingTags = on) } }
                SettingsDivider()
                SettingsRow(Icons.Rounded.Delete, "Clear lyrics & metadata cache", summary = "Also clears cached artist photos", onClick = actions.onClearLyricsCache)
                SettingsDivider()
                SettingsRow(Icons.Rounded.AutoAwesome, "Auto-fix tags", summary = "Batch-fill empty fields for the whole library", onClick = actions.onAutoFixTags)
            }
        }
        item { SettingsSectionLabel("Lyrics display") }
        item {
            SettingsGroupCard {
                SliderSetting("Lyric size", "${settings.lyricSizeSp}sp", settings.lyricSizeSp.toFloat(), 14f..40f, 25) { v -> actions.onUpdate { it.copy(lyricSizeSp = v.roundToInt().coerceIn(14, 40)) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Lyrics, "Auto-scroll lyrics", "Follow the current line during playback", settings.lyricsAutoScroll) { on -> actions.onUpdate { it.copy(lyricsAutoScroll = on) } }
                SettingsDivider()
                SliderSetting("Lyrics offset", if (settings.lyricsOffsetMs > 0) "+${settings.lyricsOffsetMs} ms" else "${settings.lyricsOffsetMs} ms", settings.lyricsOffsetMs.toFloat(), -2000f..2000f, 79) { v -> actions.onUpdate { it.copy(lyricsOffsetMs = (v / 50f).roundToInt() * 50) } }
            }
        }
        item { SettingsSectionLabel("Metadata providers") }
        item {
            SettingsGroupCard {
                MetadataProviderCatalog.ALL.forEachIndexed { index, info ->
                    if (index > 0) SettingsDivider()
                    if (info.id == "LastFm") {
                        var keyOpen by remember { mutableStateOf(false) }
                        SettingsValueRow(Icons.Rounded.LibraryMusic, "Last.fm API key", value = if (settings.lastFmApiKey.isBlank()) "Not set" else "Set", summary = "Genre tags · required for Last.fm") { keyOpen = true }
                        if (keyOpen) {
                            LastFmKeyDialog(current = settings.lastFmApiKey, onSave = { key -> actions.onUpdate { it.copy(lastFmApiKey = key) }; actions.onLyricsSettingsChanged() }, onDismiss = { keyOpen = false })
                        }
                    } else {
                        SettingsSwitchRow(Icons.Rounded.LibraryMusic, info.label, info.description, info.id in settings.metadataProvidersEnabled) { on ->
                            val next = if (on) settings.metadataProvidersEnabled + info.id else settings.metadataProvidersEnabled - info.id
                            actions.onUpdate { it.copy(metadataProvidersEnabled = next) }
                        }
                    }
                }
            }
        }
        item { SettingsSectionLabel("Artist photos") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(Icons.Rounded.Person, "Fetch artist photos", "Show circular artist images where an artist is listed", settings.fetchArtistPhotos) { on -> actions.onUpdate { it.copy(fetchArtistPhotos = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Image, "Use Deezer for artist photos", "Public Deezer search when no YouTube id is known", settings.useDeezerForArtistPhotos) { on -> actions.onUpdate { it.copy(useDeezerForArtistPhotos = on) } }
            }
        }
    }
}

@Composable
private fun LastFmKeyDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Last.fm API key") },
        text = {
            Column {
                Text("Stored encrypted on this device. Enables Last.fm genre tags.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onSave(""); onDismiss() }) { Text("Clear") } },
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun DownloadsSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val location = remember {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.resolve("AURALIS")
        dir?.absolutePath ?: "App storage"
    }
    SettingsPage("Downloads", onBack) {
        item {
            SettingsGroupCard {
                SettingsRow(Icons.Rounded.FolderOpen, "Download location", summary = location)
                SettingsDivider()
                StreamQualityRow("Download quality", settings.wifiStreamQuality) { q -> actions.onUpdate { it.copy(wifiStreamQuality = q) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.Lyrics, "Download lyrics with songs", "Save matching lyrics next to the audio file", settings.downloadLyricsWithSongs) { on -> actions.onUpdate { it.copy(downloadLyricsWithSongs = on) } }
            }
        }
        item { SettingsHelperText("Downloads use the Wi-Fi streaming quality. Local files are never re-encoded.") }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun YouTubeMusicSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    SettingsPage("YouTube Music", onBack) {
        item {
            SettingsGroupCard {
                SettingsSwitchRow(Icons.Rounded.MusicNote, "YouTube Music integration", "Home feed, liked songs and playlists", settings.youtubeIntegration) { on -> actions.onUpdate { it.copy(youtubeIntegration = on) } }
                if (settings.youtubeIntegration) {
                    SettingsDivider()
                    if (actions.youtubeSignedIn) {
                        SettingsRow(Icons.Rounded.Person, actions.youtubeName.ifBlank { "Signed in" }, onClick = actions.onYouTubeSignOut) {
                            Text("Sign out", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        SettingsRow(Icons.Rounded.Login, "Sign in with Google", summary = "Connects your YouTube Music account", onClick = actions.onYouTubeSignIn)
                    }
                }
            }
        }
        if (settings.youtubeIntegration && actions.youtubeSignedIn) {
            item { SettingsSectionLabel("Sync to YouTube") }
            item {
                SettingsGroupCard {
                    SettingsSwitchRow(Icons.Rounded.History, "Sync listening history", "Report plays to your account", settings.youtubeSyncHistory) { on -> actions.onUpdate { it.copy(youtubeSyncHistory = on) } }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Rounded.Favorite, "Sync liked songs", "Mirror likes to your account", settings.youtubeSyncLikes) { on -> actions.onUpdate { it.copy(youtubeSyncLikes = on) } }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Rounded.PlaylistPlay, "Sync playlists", "Mirror local playlists to your account", settings.youtubeSyncPlaylists) { on -> actions.onUpdate { it.copy(youtubeSyncPlaylists = on) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun AppearanceSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    val isGlass = glass()
    SettingsPage("Appearance", onBack) {
        item { SettingsSectionLabel("Theme style") }
        item { SettingsGroupCard { Box(Modifier.padding(12.dp)) { ThemeStyleSelector(selected = settings.themeStyle, onSelect = { s -> actions.onUpdate { it.copy(themeStyle = s) } }) } } }
        item { SettingsSectionLabel("Mode") }
        item {
            SettingsGroupCard {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(selected = settings.theme == mode, onClick = { actions.onUpdate { it.copy(theme = mode) } }, label = { Text(label(mode.name)) }, shape = CircleShape)
                    }
                }
            }
        }
        item { SettingsSectionLabel("Accent") }
        item {
            SettingsGroupCard {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Accents.all.forEach { (name, color) ->
                        val selected = settings.accent == name
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(color)
                                .border(2.dp, if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                                .clickable { actions.onUpdate { it.copy(accent = name) } },
                        )
                    }
                }
            }
        }
        item { SettingsSectionLabel("Player background") }
        item { SettingsGroupCard { Box(Modifier.padding(12.dp)) { PlayerBackgroundSelector(selected = settings.playerBackground, onSelect = { option -> actions.onUpdate { it.copy(playerBackground = option) } }) } } }
        if (isGlass) {
            item { SettingsSectionLabel("Glass") }
            item {
                SettingsGroupCard {
                    SliderSetting("Glass clarity", "${settings.glassClarity}%", settings.glassClarity / 100f, 0f..1f, 0) { v -> actions.onUpdate { it.copy(glassClarity = (v * 100).roundToInt()) } }
                    SettingsDivider()
                    SliderSetting("Dock transparency", "${settings.dockGlassTransparency}%", settings.dockGlassTransparency / 100f, 0f..1f, 0) { v -> actions.onUpdate { it.copy(dockGlassTransparency = (v * 100).roundToInt()) } }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Rounded.AutoAwesome, "Live blur", "Blur the content behind the glass (Android 12+)", settings.dockGlassBlur > 0) { on -> actions.onUpdate { it.copy(dockGlassBlur = if (on) 60 else 0) } }
                    SettingsDivider()
                    SliderSetting("Blur strength", "${settings.dockGlassBlur}%", settings.dockGlassBlur / 100f, 0f..1f, 0) { v -> actions.onUpdate { it.copy(dockGlassBlur = (v * 100).roundToInt()) } }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Rounded.AutoAwesome, "Glass outlines", "1dp specular hairline around glass surfaces", settings.glassOutlines) { on -> actions.onUpdate { it.copy(glassOutlines = on) } }
                }
            }
        }
    }
}



// ---------------------------------------------------------------------------------------------

@Composable
private fun PerformanceSettingsPage(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    val profile = LocalPerformanceProfile.current
    val reason = LocalPerformanceReason.current
    SettingsPage("Performance", onBack) {
        item { SettingsHelperText("Automatic adjusts visual effects for this device. Audio quality is unchanged.") }
        item {
            SettingsGroupCard {
                LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceMode.entries.size) { index ->
                        val mode = PerformanceMode.entries[index]
                        FilterChip(selected = settings.performanceProfile == mode, onClick = { actions.onUpdate { it.copy(performanceProfile = mode) } }, label = { Text(label(mode.name)) }, shape = CircleShape)
                    }
                }
                SettingsDivider()
                SettingsRow(Icons.Rounded.Speed, "Active profile", summary = profile.tier.name.lowercase().replaceFirstChar { it.uppercase() } + (reason?.let { " ($it)" } ?: ""))
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.AutoAwesome, "Reduce blur and effects", "Turn off blur and ambient motion for maximum speed", settings.reduceBlurAndEffects) { on -> actions.onUpdate { it.copy(reduceBlurAndEffects = on) } }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun StorageSettingsPage(settings: AppSettings, sources: List<MusicSource>, actions: SettingsActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val cacheBytes by produceState(initialValue = 0L, context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        }
    }
    var cacheOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    SettingsPage("Storage", onBack) {
        item { SettingsSectionLabel("Cache") }
        item {
            SettingsGroupCard {
                SettingsValueRow(Icons.Rounded.Storage, "Cache size", value = "${settings.cacheSizeMb} MB", summary = "Budget for images and temporary files") { cacheOpen = true }
                if (cacheOpen) {
                    ChoiceDialog(
                        title = "Cache size",
                        helper = "How much disk the app may use for cached artwork and lyrics.",
                        options = listOf(128 to "128 MB", 256 to "256 MB", 512 to "512 MB", 1024 to "1 GB", 0 to "Unlimited"),
                        selected = settings.cacheSizeMb,
                        onSelect = { actions.onUpdate { s -> s.copy(cacheSizeMb = it) } },
                        onDismiss = { cacheOpen = false },
                    )
                }
                SettingsDivider()
                SettingsRow(Icons.Rounded.Delete, "Clear cache", summary = "${humanBytes(cacheBytes)} used", onClick = actions.onClearCache)
            }
        }
        item { SettingsSectionLabel("Downloads") }
        item {
            SettingsGroupCard {
                SettingsRow(Icons.Rounded.Download, "Remove all downloads", summary = "Deletes every downloaded file", onClick = { confirmRemove = true }, trailing = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
        item { SettingsSectionLabel("Library") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(Icons.Rounded.Refresh, "Auto-scan new songs", "Keep the library in sync with storage and folders", settings.autoScanEnabled) { on -> actions.onUpdate { it.copy(autoScanEnabled = on) } }
                SettingsDivider()
                SettingsSwitchRow(Icons.Rounded.FolderOpen, "Include subfolders", "Scan nested folders inside each chosen folder", settings.includeSubfolders) { on -> actions.onUpdate { it.copy(includeSubfolders = on) } }
                SettingsDivider()
                SettingsRow(Icons.Rounded.FolderOpen, "Add folder", onClick = actions.onAddFolder)
                sources.forEach { source ->
                    SettingsDivider()
                    SettingsRow(Icons.Rounded.FolderOpen, source.name, summary = "Tap to remove", onClick = { actions.onRemoveSource(source) })
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Every downloaded song and its sidecar covers will be deleted from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { actions.onRemoveAllDownloads(); confirmRemove = false }) { Text("Remove all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutSettingsPage(actions: SettingsActions, onBack: () -> Unit) {
    SettingsPage("About", onBack) {
        item {
            SettingsGroupCard {
                SettingsRow(Icons.Rounded.Info, "AURALIS ${com.auralis.app.BuildConfig.VERSION_NAME}", summary = "Local-first music player")
                SettingsDivider()
                SettingsRow(Icons.Rounded.Refresh, "Check for updates", summary = updateSubtitle(actions), onClick = actions.onCheckForUpdates)
                SettingsDivider()
                SettingsRow(Icons.Rounded.BugReport, "Logs & diagnostics", summary = "Device checks, app logs and export", onClick = actions.onOpenLogs)
                SettingsDivider()
                SettingsRow(Icons.Rounded.Insights, "AURALIS Wrapped", summary = "Your listening recap", onClick = actions.onOpenWrapped)
                SettingsDivider()
                SettingsRow(Icons.Rounded.Info, "About & privacy", onClick = actions.onOpenPrivacyAbout)
            }
        }
    }
}

private fun updateSubtitle(actions: SettingsActions): String = when {
    actions.updateChecking -> "Checking..."
    actions.updateAvailableVersion != null -> "Version ${actions.updateAvailableVersion} is available"
    !actions.updateStatus.isNullOrBlank() -> actions.updateStatus
    else -> "Check GitHub for a newer release"
}

private fun label(raw: String): String = raw.lowercase().replaceFirstChar { it.uppercase() }
