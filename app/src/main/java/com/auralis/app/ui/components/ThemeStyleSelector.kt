package com.auralis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.auralis.app.domain.model.ThemeStyle

/** Normal / Glassmorphic picker with a static preview of each style (previews use fixed colours). */
@Composable
fun ThemeStyleSelector(
    selected: ThemeStyle,
    onSelect: (ThemeStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeStyle.entries.forEach { style ->
            StyleCard(style, style == selected, { onSelect(style) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StyleCard(style: ThemeStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val glass = style == ThemeStyle.GLASSMORPHIC
    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val previewShape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(previewShape)
                .background(
                    if (glass) Brush.linearGradient(listOf(Color(0xFF06070C), Color(0xFF2B2346), Color(0xFF14323F)))
                    else Brush.linearGradient(listOf(Color(0xFF2A2D31), Color(0xFF2A2D31))),
                ),
        ) {
            if (glass) {
                val panel = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.78f)
                        .height(44.dp)
                        .clip(panel)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), panel),
                )
            } else {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(0.9f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF3B3F45)))
                    Box(Modifier.fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF3B3F45)))
                    Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF4FA3A5)))
                }
            }
        }
        Text(
            if (glass) "Glassmorphic" else "Normal",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        Text(
            if (glass) "Frosted glass, artwork ambience" else "Your existing look",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
