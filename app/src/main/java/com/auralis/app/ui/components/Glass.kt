package com.auralis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.auralis.app.ui.theme.Auralis

/**
 * Floating "glass" panel: translucent fill, hairline highlight border and a soft drop shadow.
 * Compose has no built-in backdrop blur, so the fill is kept fairly opaque to stay legible over
 * scrolling content.
 */
@Composable
fun Modifier.glassSurface(shape: Shape): Modifier {
    val extras = Auralis.extras
    // Same shadow depth on every performance profile.
    val elevation = 16.dp
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.35f),
            spotColor = Color.Black.copy(alpha = 0.55f),
        )
        .clip(shape)
        .background(extras.glass)
        .border(1.dp, extras.glassBorder, shape)
}
