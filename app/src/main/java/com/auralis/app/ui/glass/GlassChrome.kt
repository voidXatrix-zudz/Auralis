package com.auralis.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.theme.GlassTokens

/** Wordmark on the left (or a back button on pushed screens). The dock owns Settings now. */
@Composable
fun GlassTopBar(showBack: Boolean, onBack: () -> Unit, collapseProgress: Float = 0f) {
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.ui.unit.lerp(64.dp, 56.dp, collapseProgress)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        } else {
            Text(
                "AURALIS",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = androidx.compose.ui.unit.lerp(18.sp, 16.sp, collapseProgress),
                    letterSpacing = androidx.compose.ui.unit.lerp(6.sp, 3.sp, collapseProgress),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
                color = GlassTokens.Silver,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .graphicsLayer {
                        val scale = 1f - 0.15f * collapseProgress
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        Box(Modifier.weight(1f))
    }
}

private data class GlassNavItem(val tab: Tab, val label: String, val icon: ImageVector)

private val GlassNavItems = listOf(
    GlassNavItem(Tab.HOME, "Home", Icons.Rounded.Home),
    GlassNavItem(Tab.SEARCH, "Search", Icons.Rounded.Search),
    GlassNavItem(Tab.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
)

/** Floating glass pill; the active tab gets a luminous violet-cyan capsule with specular highlight. */
@Composable
fun GlassNavBar(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        CircleShape,
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GlassNavItems.forEach { item ->
                val active = item.tab == selected
                val tint = if (active) GlassTokens.Violet else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                Brush.horizontalGradient(
                                    listOf(GlassTokens.Violet.copy(alpha = 0.28f), GlassTokens.Ice.copy(alpha = 0.18f)),
                                )
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        )
                        .border(
                            1.dp,
                            if (active) {
                                Brush.linearGradient(
                                    listOf(GlassTokens.Violet.copy(alpha = 0.70f), GlassTokens.Ice.copy(alpha = 0.45f)),
                                )
                            } else {
                                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                            CircleShape,
                        )
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.tab) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(
                        item.label,
                        color = if (active) GlassTokens.Silver else tint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Glass mini-player: floating frosted glass bar with subtle artwork ambience,
 * sleek controls, and glowing play hero button.
 */
@Composable
fun GlassMiniPlayer(
    track: Track,
    playing: Boolean,
    shuffle: Boolean,
    repeat: Int,
    toggle: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    toggleShuffle: () -> Unit,
    cycleRepeat: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(GlassTokens.RadiusLg)
    val tint = track.artColor?.let { Color(it) } ?: GlassTokens.Violet
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 68.dp),
        shape = shape,
        onClick = onOpen,
    ) {
        // Artwork ambience stays on every performance profile.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.28f), Color.Transparent))))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val compact = maxWidth < 370.dp
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassArtwork(track, Modifier.size(48.dp), 14.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(
                        track.title.ifBlank { track.displayName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        track.artist.ifBlank { "Unknown artist" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (!compact) {
                    GlassIconAction(Icons.Rounded.Shuffle, "Shuffle", toggleShuffle, tint = if (shuffle) GlassTokens.Violet else colors.onSurfaceVariant)
                    GlassIconAction(Icons.Rounded.SkipPrevious, "Previous", previous, tint = colors.onSurface)
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .shadow(8.dp, CircleShape, clip = false, ambientColor = GlassTokens.Violet.copy(alpha = 0.35f), spotColor = GlassTokens.Violet.copy(alpha = 0.50f))
                        .clip(CircleShape)
                        .background(GlassTokens.AccentGradient)
                        .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                        .clickable(role = Role.Button, onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = GlassTokens.OnAccent, modifier = Modifier.size(26.dp))
                }
                if (!compact) {
                    GlassIconAction(Icons.Rounded.SkipNext, "Next", next, tint = colors.onSurface)
                    GlassIconAction(Icons.Rounded.Repeat, "Repeat", cycleRepeat, tint = if (repeat != 0) GlassTokens.Violet else colors.onSurfaceVariant)
                }
            }
        }
    }
}
