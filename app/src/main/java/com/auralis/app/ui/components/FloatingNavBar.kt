package com.auralis.app.ui.components

import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.LocalThemeStyle
import com.auralis.app.ui.glass.GlassNavBar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.theme.Auralis

private data class NavItem(val tab: Tab, val label: String, val icon: ImageVector)

private val NavItems = listOf(
    NavItem(Tab.HOME, "Home", Icons.Rounded.Home),
    NavItem(Tab.SEARCH, "Search", Icons.Rounded.Search),
    NavItem(Tab.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
)

/**
 * Floating pill navigation with a glass background. [selected] is null while a pushed screen
 * (Settings, About, ...) is on top, so no tab is highlighted, matching the web reference.
 * The caller supplies system-bar insets through [modifier].
 */
@Composable
fun FloatingNavBar(
    selected: Tab?,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) { GlassNavBar(selected, onSelect, modifier); return }
    val primary = MaterialTheme.colorScheme.primary
    val muted = Auralis.extras.mutedForeground
    // Same colour transition timing on every performance profile.
    val colorDuration = 300
    val pill = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .glassSurface(pill)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavItems.forEach { item ->
            val active = item.tab == selected
            val background by animateColorAsState(
                targetValue = if (active) primary.copy(alpha = 0.15f) else Color.Transparent,
                animationSpec = tween(colorDuration),
                label = "navBackground",
            )
            val content by animateColorAsState(
                targetValue = if (active) primary else muted,
                animationSpec = tween(colorDuration),
                label = "navContent",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.tab) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(item.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                Text(item.label, color = content, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
