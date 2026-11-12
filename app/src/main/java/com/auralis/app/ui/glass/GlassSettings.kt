package com.auralis.app.ui.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.auralis.app.BuildConfig
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.PerformanceMode
import com.auralis.app.domain.model.ReplayGainMode
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.components.PlayerBackgroundSelector
import com.auralis.app.ui.performance.LocalPerformanceProfile
import com.auralis.app.ui.performance.LocalPerformanceReason
import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalGlassOptics
import kotlin.math.roundToInt

/**
 * Glassmorphic Settings: a large title that collapses into a small top bar, a search pill, a
 * profile card and grouped 24dp cards. All controls map to the real AppSettings fields and
 * persist through the same onUpdate callback as the Normal screen.
 */
@Composable
fun GlassSettingsScreen(
    settings: AppSettings,
    sources: List<MusicSource>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveSource: (MusicSource) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier,
    onBack: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    updateChecking: Boolean = false,
    updateStatus: String? = null,
    updateAvailableVersion: String? = null,
    clerkSignedIn: Boolean = false,
    clerkName: String = "",
    clerkEmail: String = "",
    accountImageUrl: String? = null,
    onClerkSignOut: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<MusicSource?>(null) }
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36
        }
    }
    val collapse by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 320f),
        label = "settingsHeader",
    )
    val q = query.trim()

    fun matches(vararg keys: String): Boolean =
        q.isBlank() || keys.any { it.contains(q, ignoreCase = true) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 24.dp + LocalDockBottomInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .graphicsLayer { alpha = 1f - collapse },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = lerp(40.sp, 22.sp, collapse),
                        ),
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.Silver,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .graphicsLayer {
                                val scale = 1f - 0.06f * collapse
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
            item {
                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search settings",
                    leading = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ProfileCard(
                    sourceCount = sources.size,
                    signedIn = clerkSignedIn,
                    name = clerkName,
                    email = clerkEmail,
                    imageUrl = accountImageUrl,
                )
            }

            if (clerkSignedIn && matches("Account", "profile", "sign out", "clerk", "email")) {
                item {
                    SettingsGroup("Account", Icons.Rounded.Person) {
                        InfoNote(
                            clerkName.ifBlank { "Signed in" } +
                                if (clerkEmail.isNotBlank()) " · $clerkEmail" else "",
                        )
                        RowItem(
                            Icons.AutoMirrored.Rounded.Logout,
                            "Sign out",
                            "Return to the AURALIS sign-in screen",
                            onClerkSignOut,
                            showDivider = false,
                        )
                    }
                }
            }

            if (matches("Appearance", "theme", "glass", "clarity", "accent", "performance", "dock")) {
                item {
                    SettingsGroup("Appearance", Icons.Rounded.Palette) {
                        AppearanceSection(settings, onUpdate)
                    }
                }
            }
            if (matches("Playback", "equalizer", "bass", "speed", "balance", "replay", "crossfade", "gesture")) {
                item {
                    SettingsGroup("Playback", Icons.Rounded.Headphones) {
                        PlaybackSection(settings, onUpdate, onOpenEqualizer)
                    }
                }
            }
            if (matches("Lyrics", "Library", "lyric", "scroll", "size", "folder", "songs")) {
                item {
                    SettingsGroup("Lyrics & Library", Icons.Rounded.Lyrics) {
                        LyricsSection(settings, onUpdate, sources)
                    }
                }
            }
            if (matches("Storage", "Download", "folder", "library", "source")) {
                item {
                    SettingsGroup("Storage & Download", Icons.Rounded.Storage) {
                        StorageSection(sources, onAddFolder, pendingRemoval = { pendingRemoval = it })
                    }
                }
            }
            if (matches("Extensions", "equalizer", "wrapped")) {
                item {
                    SettingsGroup("Extensions", Icons.Rounded.Extension) {
                        RowItem(Icons.Rounded.Equalizer, "Equalizer", "Presets, bands, bass boost and virtualizer", onOpenEqualizer)
                        RowItem(Icons.Rounded.Insights, "AURALIS Wrapped", "Your listening recap and top tracks", onOpenWrapped, showDivider = false)
                    }
                }
            }
            if (matches("Files", "Metadata", "App", "version", "folder", "scan", "update")) {
                item {
                    SettingsGroup("Files, Metadata & App", Icons.Rounded.Folder) {
                        RowItem(Icons.Rounded.FolderOpen, "Music folders", "${sources.size} linked folder${if (sources.size == 1) "" else "s"}", null)
                        RowItem(Icons.Rounded.Tune, "Metadata editor", "Available from Now Playing → Edit tags", null)
                        RowItem(Icons.Rounded.Refresh, "Check for updates", updateSubtitle(updateChecking, updateStatus, updateAvailableVersion), onCheckForUpdates)
                        RowItem(Icons.Rounded.AutoAwesome, "AURALIS version", BuildConfig.VERSION_NAME, null, showDivider = false)
                        InfoNote("Tag edits are written on-device. Nothing is uploaded.")
                    }
                }
            }
            if (matches("Backup", "restore", "export")) {
                item {
                    SettingsGroup("Backup", Icons.Rounded.Backup) {
                        RowItem(Icons.Rounded.CloudUpload, "Backup & restore", "Versioned JSON export and restore", onOpenBackup, showDivider = false)
                    }
                }
            }
            if (matches("Logs", "diagnostics", "privacy", "support")) {
                item {
                    SettingsGroup("Logs", Icons.Rounded.BugReport) {
                        RowItem(Icons.Rounded.BugReport, "Logs & diagnostics", "Device checks, app logs and export", onOpenLogs)
                        InfoNote("Diagnostics stay on this device. AURALIS does not upload listening history or crash logs.")
                    }
                }
            }
            if (matches("About", "privacy", "version", "app")) {
                item {
                    SettingsGroup("About", Icons.Rounded.Info) {
                        RowItem(Icons.Rounded.Info, "About & privacy", "Licences, privacy and project credits", onOpenAbout, showDivider = false)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Compact top bar appears once the large title scrolls away.
        if (collapse > 0.01f) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .graphicsLayer { alpha = collapse }
                    .background(GlassTokens.Obsidian.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 40.dp)
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTokens.Silver,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }

    pendingRemoval?.let { source ->
        GlassDialog(
            title = "Remove folder?",
            message = "“${source.name}” will be removed from your library. Files on your device are not touched.",
            confirmLabel = "Remove",
            onConfirm = {
                onRemoveSource(source)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun ProfileCard(
    sourceCount: Int,
    signedIn: Boolean,
    name: String,
    email: String,
    imageUrl: String?,
) {
    GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusCard)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(
                imageUrl = if (signedIn) imageUrl else null,
                contentDescription = if (signedIn) name.ifBlank { "Account" } else null,
                iconTint = GlassTokens.Silver,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.60f), GlassTokens.Ice.copy(alpha = 0.42f))))
                    .border(1.dp, GlassTokens.BorderBrush, CircleShape),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                if (signedIn) {
                    Text(name.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        email.ifBlank { "Signed in to AURALIS" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Local listener", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Offline library · $sourceCount folder${if (sourceCount == 1) "" else "s"} linked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = GlassTokens.SilverMuted, modifier = Modifier.size(15.dp))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = GlassTokens.SilverMuted,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusCard)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val dockGlass = LocalDockGlassStyle.current
    val profile = LocalPerformanceProfile.current
    val performanceReason = LocalPerformanceReason.current
    SegmentedRow(
        icon = Icons.Rounded.Palette,
        title = "Theme style",
        description = "Glassmorphism adds live backdrop blur and refraction",
    ) {
        Segmented(
            options = listOf(ThemeStyle.NORMAL to "Normal", ThemeStyle.GLASSMORPHIC to "Glass"),
            selected = settings.themeStyle,
            onSelect = { style -> onUpdate { it.copy(themeStyle = style) } },
        )
    }
    Divider()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(Icons.Rounded.Image)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Player background", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Full-screen photo behind the player", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PlayerBackgroundSelector(
            selected = settings.playerBackground,
            onSelect = { option -> onUpdate { it.copy(playerBackground = option) } },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Divider()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(Icons.Rounded.Tune)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Glass clarity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(clarityName(settings.glassClarity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${settings.glassClarity}%", style = MaterialTheme.typography.labelMedium, color = GlassTokens.SilverMuted)
        }
        Slider(
            value = settings.glassClarity / 100f,
            onValueChange = { value -> onUpdate { it.copy(glassClarity = (value * 100).roundToInt()) } },
            colors = violetSliderColors(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to "Tinted", 45 to "Frosted", 85 to "Crystal").forEach { (value, label) ->
                GlassChip(label, settings.glassClarity == value, { onUpdate { it.copy(glassClarity = value) } })
            }
        }
    }
    Divider()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(Icons.Rounded.Palette)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Dock transparency", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Tint only. Icons, labels and the capsule stay fully opaque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${settings.dockGlassTransparency}%", style = MaterialTheme.typography.labelMedium, color = GlassTokens.SilverMuted)
        }
        Slider(
            value = settings.dockGlassTransparency / 100f,
            onValueChange = { value -> onUpdate { it.copy(dockGlassTransparency = (value * 100).roundToInt()) } },
            colors = violetSliderColors(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Nearly clear", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Solid tint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Divider()
    val blurSupported = Build.VERSION.SDK_INT >= 31
    ToggleRow(
        icon = Icons.Rounded.AutoAwesome,
        title = "Live blur",
        description = if (blurSupported) "Blur the content scrolling behind the glass" else "Needs Android 12 or newer",
        checked = settings.dockGlassBlur > 0 && blurSupported,
        onCheckedChange = { enabled ->
            if (blurSupported) onUpdate { it.copy(dockGlassBlur = if (enabled) 60 else 0) }
        },
        showDivider = false,
    )
    Column(Modifier.padding(horizontal = 14.dp)) {
        Slider(
            value = settings.dockGlassBlur / 100f,
            onValueChange = { value -> onUpdate { it.copy(dockGlassBlur = (value * 100).roundToInt()) } },
            enabled = blurSupported,
            colors = violetSliderColors(),
            modifier = Modifier.padding(top = 0.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${(settings.dockGlassBlur * 0.4f).roundToInt()}dp chosen · ${dockGlass.blurDp.roundToInt()}dp active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val hint = dockGlass.limitedReason
        if (hint != null) {
            InfoNote(hint)
        }
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        DockGlassPreview(
            tintAlpha = dockGlass.tintAlpha,
            blurDp = dockGlass.blurDp,
            outlines = dockGlass.outlines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Divider()
    ToggleRow(
        icon = Icons.Rounded.AutoAwesome,
        title = "Glass outlines",
        description = "1dp specular hairline around glass surfaces",
        checked = settings.glassOutlines,
        onCheckedChange = { enabled -> onUpdate { it.copy(glassOutlines = enabled) } },
    )
    Divider()
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(Icons.Rounded.Speed)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Performance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Automatic tunes visual effects; audio quality is unchanged",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.padding(top = 10.dp)) {
            Segmented(
                options = PerformanceMode.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                selected = settings.performanceProfile,
                onSelect = { mode -> onUpdate { it.copy(performanceProfile = mode) } },
            )
        }
        // Live result: proves the resolver actually ran and shows what Automatic picked.
        Text(
            text = if (settings.performanceProfile == PerformanceMode.AUTOMATIC) {
                "Automatic · currently ${profile.tier.displayName()}" + (performanceReason?.let { " ($it)" } ?: "")
            } else {
                "Active profile: ${profile.tier.displayName()}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = GlassTokens.Violet,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlaybackSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    var replayOpen by remember { mutableStateOf(false) }
    RowItem(Icons.Rounded.Equalizer, "Equalizer", "Open presets and bands", onOpenEqualizer)
    ToggleRow(
        icon = Icons.Rounded.Equalizer,
        title = "Equalizer enabled",
        description = "Apply the selected preset to playback",
        checked = settings.eqEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(eqEnabled = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Speed,
        title = "Bass boost",
        description = "Adds low-end weight (${settings.bassDb.roundToInt()} dB)",
        checked = settings.bassBoostEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(bassBoostEnabled = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Headphones,
        title = "Virtualizer",
        description = "Widens the stereo image",
        checked = settings.virtualizerEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(virtualizerEnabled = on) } },
    )
    Divider()
    SliderRow(
        icon = Icons.Rounded.Speed,
        title = "Playback speed",
        valueLabel = "${"%.2f".format(settings.speed)}×",
        value = settings.speed,
        range = 0.5f..2f,
        onChange = { value -> onUpdate { it.copy(speed = value) } },
    )
    SliderRow(
        icon = Icons.Rounded.Tune,
        title = "Balance",
        valueLabel = when {
            settings.balance < -0.02f -> "Left ${(-settings.balance * 100).roundToInt()}%"
            settings.balance > 0.02f -> "Right ${(settings.balance * 100).roundToInt()}%"
            else -> "Centre"
        },
        value = settings.balance,
        range = -1f..1f,
        onChange = { value -> onUpdate { it.copy(balance = value) } },
    )
    Divider()
    SettingRow(
        icon = Icons.Rounded.Insights,
        title = "Replay gain",
        description = "Normalise loudness across tracks",
    ) {
        Box {
            GlassChip(
                settings.replayGain.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                selected = false,
                onClick = { replayOpen = true },
            )
            DropdownMenu(replayOpen, { replayOpen = false }) {
                ReplayGainMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        onClick = {
                            onUpdate { it.copy(replayGain = mode) }
                            replayOpen = false
                        },
                    )
                }
            }
        }
    }
    SliderRow(
        icon = Icons.Rounded.Speed,
        title = "Crossfade",
        valueLabel = "${settings.crossfadeSeconds}s",
        value = settings.crossfadeSeconds.toFloat(),
        range = 0f..12f,
        steps = 11,
        onChange = { value -> onUpdate { it.copy(crossfadeSeconds = value.roundToInt()) } },
    )
    Divider()
    ToggleRow(
        icon = Icons.Rounded.Headphones,
        title = "Resume on reconnect",
        description = "Continue after headphones or a car kit reconnect",
        checked = settings.resumeOnOutputReconnect,
        onCheckedChange = { on -> onUpdate { it.copy(resumeOnOutputReconnect = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Speed,
        title = "Keep screen on in car",
        description = "Prevents the display sleeping while driving",
        checked = settings.carKeepScreenOn,
        onCheckedChange = { on -> onUpdate { it.copy(carKeepScreenOn = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Tune,
        title = "Swipe gestures",
        description = "Swipe artwork to change tracks",
        checked = settings.gestureSwipeEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(gestureSwipeEnabled = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Tune,
        title = "Seek gestures",
        description = "Horizontal drag to seek",
        checked = settings.gestureSeekEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(gestureSeekEnabled = on) } },
        showDivider = false,
    )
}

@Composable
private fun LyricsSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, sources: List<MusicSource>) {
    SliderRow(
        icon = Icons.Rounded.Lyrics,
        title = "Lyric size",
        valueLabel = "${settings.lyricSizeSp}sp",
        value = settings.lyricSizeSp.toFloat(),
        range = 14f..40f,
        steps = 25,
        onChange = { value -> onUpdate { it.copy(lyricSizeSp = value.roundToInt()) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Lyrics,
        title = "Auto-scroll lyrics",
        description = "Follow the current line during playback",
        checked = settings.lyricsAutoScroll,
        onCheckedChange = { on -> onUpdate { it.copy(lyricsAutoScroll = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.Storage,
        title = "Auto-scan new songs",
        description = "Keep the library in sync with device storage and added folders",
        checked = settings.autoScanEnabled,
        onCheckedChange = { on -> onUpdate { it.copy(autoScanEnabled = on) } },
    )
    ToggleRow(
        icon = Icons.Rounded.FolderOpen,
        title = "Include subfolders",
        description = "Scan nested folders inside each chosen folder",
        checked = settings.includeSubfolders,
        onCheckedChange = { on -> onUpdate { it.copy(includeSubfolders = on) } },
    )
    SliderRow(
        icon = Icons.Rounded.Speed,
        title = "Lyrics offset",
        valueLabel = when {
            settings.lyricsOffsetMs > 0 -> "+${settings.lyricsOffsetMs} ms"
            else -> "${settings.lyricsOffsetMs} ms"
        },
        value = settings.lyricsOffsetMs.toFloat(),
        range = -2_000f..2_000f,
        steps = 79,
        onChange = { value ->
            onUpdate { it.copy(lyricsOffsetMs = (value / 50f).roundToInt() * 50) }
        },
    )
    SliderRow(
        icon = Icons.Rounded.Lyrics,
        title = "Double-tap seek",
        valueLabel = "${settings.doubleTapSeekSeconds}s",
        value = settings.doubleTapSeekSeconds.toFloat(),
        range = 5f..60f,
        steps = 54,
        onChange = { value -> onUpdate { it.copy(doubleTapSeekSeconds = value.roundToInt()) } },
    )
    Divider()
    RowItem(
        Icons.Rounded.FolderOpen,
        "Library folders",
        "${sources.size} linked · ${if (sources.isEmpty()) "add one below" else "ready to scan"}",
        null,
        showDivider = false,
    )
}

@Composable
private fun StorageSection(sources: List<MusicSource>, onAddFolder: () -> Unit, pendingRemoval: (MusicSource) -> Unit) {
    if (sources.isEmpty()) {
        InfoNote("No music folders yet. Add a folder to build your offline library.")
    } else {
        sources.forEach { source ->
            SettingRow(
                icon = Icons.Rounded.FolderOpen,
                title = source.name,
                description = "Music folder",
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { pendingRemoval(source) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Delete, "Remove ${source.name}", tint = GlassTokens.SilverMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    Divider()
    RowItem(Icons.Rounded.Add, "Add music folder", "Pick a folder with the system picker", onAddFolder, showDivider = false)
}

@Composable
private fun SegmentedRow(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(icon)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.padding(top = 10.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    SettingRow(icon, title, description, showDivider = showDivider) {
        GlassSwitch(checked, onCheckedChange)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String?,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(icon)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
            if (onClick != null) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
        }
        if (showDivider) Divider()
    }
}

@Composable
private fun RowItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: (() -> Unit)?,
    showDivider: Boolean = true,
) {
    SettingRow(icon = icon, title = title, description = description, showDivider = showDivider, onClick = onClick)
}

@Composable
private fun TintedIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.30f), GlassTokens.Ice.copy(alpha = 0.18f))))
            .border(1.dp, GlassTokens.BorderBrush, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = GlassTokens.Silver, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f)),
    )
}

@Composable
private fun InfoNote(text: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, null, tint = GlassTokens.Violet, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = GlassTokens.Silver,
            checkedTrackColor = GlassTokens.Violet,
            checkedBorderColor = GlassTokens.Violet.copy(alpha = 0.55f),
            uncheckedThumbColor = GlassTokens.SilverMuted,
            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.14f),
        ),
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (active) {
                            Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.42f), GlassTokens.Ice.copy(alpha = 0.22f)))
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) GlassTokens.Silver else GlassTokens.SilverMuted,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(icon)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = GlassTokens.SilverMuted)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = violetSliderColors(),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun violetSliderColors() = SliderDefaults.colors(
    thumbColor = GlassTokens.Silver,
    activeTrackColor = GlassTokens.Violet,
    inactiveTrackColor = Color.White.copy(alpha = 0.12f),
)

@Composable
private fun DockGlassPreview(tintAlpha: Float, blurDp: Float, outlines: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.55f), GlassTokens.Ice.copy(alpha = 0.42f), Color(0xFF171A24)))),
    ) {
        // Blurred "artwork" behind the glass: its softness tracks the real effective radius.
        Box(
            Modifier
                .offset(x = 22.dp, y = 16.dp)
                .size(64.dp)
                .blur(blurDp.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f)),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-18).dp, y = (-14).dp)
                .size(44.dp)
                .blur(blurDp.dp)
                .clip(CircleShape)
                .background(GlassTokens.Obsidian.copy(alpha = 0.35f)),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .height(44.dp)
                .shadowSoft()
                .clip(CircleShape)
                .background(GlassTokens.Surface.copy(alpha = tintAlpha.coerceIn(0.04f, 0.88f)))
                .background(GlassTokens.SurfaceSheen)
                .then(if (outlines) Modifier.border(1.dp, GlassTokens.BorderBrush, CircleShape) else Modifier)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = GlassTokens.Silver, modifier = Modifier.size(16.dp))
            Text(
                "Live dock preview",
                style = MaterialTheme.typography.labelMedium,
                color = GlassTokens.Silver,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun VisualPerformanceTier.displayName(): String = when (this) {
    VisualPerformanceTier.HIGH -> "High"
    VisualPerformanceTier.MEDIUM -> "Medium"
    VisualPerformanceTier.LOW -> "Low"
}

private fun Modifier.shadowSoft(): Modifier = this.then(
    Modifier.shadow(
        elevation = 10.dp,
        shape = CircleShape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.40f),
        spotColor = Color.Black.copy(alpha = 0.50f),
    ),
)

private fun clarityName(value: Int): String = when {
    value <= 22 -> "Tinted · smoked glass"
    value <= 58 -> "Frosted · balanced"
    else -> "Crystal · almost clear"
}

private fun updateSubtitle(checking: Boolean, status: String?, version: String?): String = when {
    checking -> "Checking..."
    version != null -> "Version $version is available"
    !status.isNullOrBlank() -> status
    else -> "Check GitHub for a newer release"
}
