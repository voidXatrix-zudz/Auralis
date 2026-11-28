package com.auralis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.AppSettings
import com.auralis.app.domain.model.MusicSource
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.glass.SettingsLiquidGlass
import com.auralis.app.ui.glass.playerGlass
import com.auralis.app.ui.glass.GlassMaterialAlertDialog
import com.auralis.app.ui.glass.GlassPanel
import com.auralis.app.ui.glass.GlassSearchField
import com.auralis.app.ui.nav.Destination
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.LocalDockBottomInset
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.theme.auralisSwitchColors

/** Callbacks every Settings sub-page needs; grouped so the signature stays manageable. */
data class SettingsActions(
    val onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    val onAddFolder: () -> Unit,
    val onRemoveSource: (MusicSource) -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onOpenWrapped: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onOpenPrivacyAbout: () -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onYouTubeSignIn: () -> Unit,
    val onYouTubeSignOut: () -> Unit,
    /** Clears the stored session and browser cookies, then opens the account chooser. */
    val onYouTubeDifferentAccount: () -> Unit = {},
    val onClearCache: () -> Unit,
    val onClearLyricsCache: () -> Unit,
    val onRemoveAllDownloads: () -> Unit,
    val onAutoFixTags: () -> Unit,
    val onMoveLyricsProvider: (String, Int) -> Unit,
    val onResetLyricsProviders: () -> Unit,
    val onLyricsSettingsChanged: () -> Unit,
    val updateChecking: Boolean,
    val updateStatus: String?,
    val updateAvailableVersion: String?,
    val youtubeSignedIn: Boolean,
    val youtubeName: String,
    /** True when a stored session was rejected (401/403) and a re-login is needed. */
    val youtubeExpired: Boolean = false,
    /** Decrypt/Keystore failure type when the stored blob exists but cannot be read. */
    val youtubeReadError: String? = null,
    /** Last successful sign-in/refresh/rotation; shown on the expired row as an age. */
    val youtubeLastRefreshedAt: Long = 0L,
    /** Re-checks an expired session when the Settings page opens (respects the 30-min backoff). */
    val onYouTubeHeal: () -> Unit = {},
    /** Settings → "Test YouTube connection": account_menu, then playlists, then liked songs. */
    val onYouTubeTest: () -> Unit = {},
    val youTubeTestRunning: Boolean = false,
    /** One non-secret line per endpoint, e.g. "liked_playlists  OK  HTTP 200  parsed: 42". */
    val youTubeTestLines: List<String> = emptyList(),
    /** Live sync: on/off, current engine state, last successful pass and the manual "Sync now". */
    val liveSyncEnabled: Boolean = true,
    val liveSyncState: com.auralis.app.data.youtube.LiveSyncState = com.auralis.app.data.youtube.LiveSyncState.Disabled,
    val liveSyncLastSyncedAt: Long = 0L,
    val onToggleLiveSync: (Boolean) -> Unit = {},
    val onSyncLiveNow: () -> Unit = {},
    /** Outbound reports plus inbound `FEmusic_history` pulls, for the "History sync" status line. */
    val playbackReporting: com.auralis.app.data.youtube.PlaybackReportingState =
        com.auralis.app.data.youtube.PlaybackReportingState(),
    /** the three glass controls write through GlassConfigRepository only. */
    val onDockTransparencyChange: (Int) -> Unit = {},
    val onGlassClarityChange: (Int) -> Unit = {},
    val onPreviewDockTransparency: (Int) -> Unit = {},
    val onPreviewGlassClarity: (Int) -> Unit = {},
    val onClearGlassPreview: () -> Unit = {},
    val onGlassOutlinesChange: (Boolean) -> Unit = {},
)

// Shared, theme-aware primitives (Normal + Glass).

@Composable
internal fun glass(): Boolean = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC

@Composable
fun SettingsGroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (glass()) {
        GlassPanel(modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusCard)) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        color = if (glass()) GlassTokens.SilverMuted else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsHelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(icon, title, summary = summary) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = auralisSwitchColors())
    }
}

@Composable
fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    SettingsRow(icon, title, summary = summary, onClick = onClick) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/** A radio-list choice dialog used by value rows (no separate route needed). */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    helper: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassMaterialAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (!helper.isNullOrBlank()) {
                    Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The back chevron every Settings page draws. In Glass it is the dock's circle material: the
 * page's [SettingsLiquidGlass] layer is what it blurs and refracts. Normal keeps the stock button.
 */
@Composable
internal fun SettingsBackButton(onBack: () -> Unit) {
    if (glass()) {
        Box(
            Modifier
                .size(48.dp)
                .playerGlass(CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    } else {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }
}

// Collapsing page chrome.

@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional content pinned between the page title and the scrolling list. Pages that need an
     * always-visible control/preview (Appearance) pass this; every other page keeps the original
     * layout where the title itself scrolls with the list.
     */
    pinnedHeader: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val isGlass = glass()
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36 }
    }
    val bottomInset = if (isGlass) LocalDockBottomInset.current else 0.dp
    val titleRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsBackButton(onBack)
            Text(
                title,
                style = if (collapsed) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (isGlass) GlassTokens.Silver else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    Box(modifier.fillMaxSize()) {
        if (pinnedHeader == null) {
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp + bottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { titleRow() }
                content()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    titleRow()
                    pinnedHeader()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

// Hub (main Settings page).

private data class SettingsCategory(val label: String, val summary: String, val icon: ImageVector, val destination: Destination, val group: Int)

@Composable
fun SettingsHubScreen(
    settings: AppSettings,
    sources: List<MusicSource>,
    actions: SettingsActions,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveSource: (MusicSource) -> Unit,
    clerkSignedIn: Boolean,
    clerkName: String,
    clerkEmail: String,
    accountImageUrl: String?,
    onClerkSignOut: () -> Unit,
) {
    // Settings records its own backdrop layer (see SettingsLiquidGlass), so the cards, the back
    // button and the search pill below get the same liquid glass as the dock.
    SettingsLiquidGlass(settings.playerBackground) {
        SettingsHubContent(
            settings = settings,
            sources = sources,
            actions = actions,
            onNavigate = onNavigate,
            onBack = onBack,
            onAddFolder = onAddFolder,
            onRemoveSource = onRemoveSource,
            clerkSignedIn = clerkSignedIn,
            clerkName = clerkName,
            clerkEmail = clerkEmail,
            accountImageUrl = accountImageUrl,
            onClerkSignOut = onClerkSignOut,
        )
    }
}

@Composable
private fun SettingsHubContent(
    settings: AppSettings,
    sources: List<MusicSource>,
    actions: SettingsActions,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveSource: (MusicSource) -> Unit,
    clerkSignedIn: Boolean,
    clerkName: String,
    clerkEmail: String,
    accountImageUrl: String?,
    onClerkSignOut: () -> Unit,
) {
    val isGlass = glass()
    var query by remember { mutableStateOf("") }
    var normalSearchOpen by remember { mutableStateOf(false) }
    val categories = remember(isGlass) {
        listOf(
            SettingsCategory("History", "Songs you played", Icons.Rounded.History, Destination.History, 1),
            SettingsCategory("Playback & Quality", "Streaming quality, data saver, gestures", Icons.Rounded.PlayArrow, Destination.SettingsPlayback, 1),
            SettingsCategory("Metadata & Lyrics", "Providers, tag fixes, lyrics display", Icons.Rounded.Lyrics, Destination.SettingsMetadataLyrics, 1),
            SettingsCategory("Downloads", "Location, quality and lyrics", Icons.Rounded.Download, Destination.SettingsDownloads, 1),
            SettingsCategory("YouTube Music", "Account and sync", Icons.Rounded.MusicNote, Destination.SettingsYouTubeMusic, 2),
            // Glass has no accent or light/dark choice, so its searchable summary must not advertise
            // either; typing "accent" or "mode" then finds nothing while Glass is selected.
            SettingsCategory(
                "Appearance",
                if (isGlass) "Theme and player background" else "Theme, accent and playback background",
                Icons.Rounded.Palette,
                Destination.SettingsAppearance,
                2,
            ),
            SettingsCategory("Performance", "Visual effects and battery", Icons.Rounded.Speed, Destination.SettingsPerformance, 2),
            SettingsCategory("Storage", "Cache, downloads and library folders", Icons.Rounded.Storage, Destination.SettingsStorage, 2),
            SettingsCategory("Backup", "Export and restore your library", Icons.Rounded.Backup, Destination.Backup, 3),
            SettingsCategory("About", "Version, updates and diagnostics", Icons.Rounded.Info, Destination.SettingsAbout, 3),
        )
    }
    val filtered = remember(query, categories) {
        val q = query.trim()
        if (q.isBlank()) categories else categories.filter {
            it.label.contains(q, ignoreCase = true) || it.summary.contains(q, ignoreCase = true)
        }
    }

    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36 }
    }
    val bottomInset = if (isGlass) LocalDockBottomInset.current else 0.dp

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsBackButton(onBack)
                Text(
                    "Settings",
                    style = if (collapsed) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isGlass) GlassTokens.Silver else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                if (!isGlass) {
                    IconButton(onClick = {
                        normalSearchOpen = !normalSearchOpen
                        if (!normalSearchOpen) query = ""
                    }) {
                        Icon(Icons.Rounded.Search, "Search settings")
                    }
                }
            }
        }
        // Search: the glass theme keeps its pill; Normal shows a field only after the search icon.
        if (isGlass) {
            item {
                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search settings",
                    leading = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                    liquid = true,
                )
            }
        } else if (normalSearchOpen) {
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search settings") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Account area.
        item {
            if (isGlass) {
                GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(GlassTokens.RadiusCard)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(
                            imageUrl = if (clerkSignedIn) accountImageUrl else null,
                            contentDescription = if (clerkSignedIn) clerkName.ifBlank { "Account" } else null,
                            iconTint = GlassTokens.Silver,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.6f), GlassTokens.Ice.copy(alpha = 0.42f))))
                                .border(1.dp, GlassTokens.BorderBrush, CircleShape),
                        )
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            if (clerkSignedIn) {
                                Text(clerkName.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium)
                                Text(clerkEmail.ifBlank { "Signed in to AURALIS" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Local listener", style = MaterialTheme.typography.titleMedium)
                                Text("Offline library · ${sources.size} folder${if (sources.size == 1) "" else "s"} linked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (clerkSignedIn) TextButton(onClick = onClerkSignOut) { Text("Sign out") }
                    }
                }
            } else {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(
                            imageUrl = if (clerkSignedIn) accountImageUrl else null,
                            contentDescription = if (clerkSignedIn) clerkName.ifBlank { "Account" } else null,
                            iconTint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                if (clerkSignedIn) clerkName.ifBlank { "Signed in" } else "Local listener",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (clerkSignedIn && clerkEmail.isNotBlank()) {
                                Text(clerkEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (!clerkSignedIn) {
                                Text("Offline library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (clerkSignedIn) TextButton(onClick = onClerkSignOut) { Text("Sign out") }
                    }
                }
            }
        }

        item { SettingsSectionLabel("Settings") }
        (1..3).forEach { group ->
            val groupItems = filtered.filter { it.group == group }
            if (groupItems.isNotEmpty()) {
                item {
                    SettingsGroupCard {
                        groupItems.forEachIndexed { index, category ->
                            if (index > 0) SettingsDivider()
                            SettingsRow(category.icon, category.label, summary = category.summary, onClick = { onNavigate(category.destination) })
                        }
                    }
                }
            }
        }
    }
}
