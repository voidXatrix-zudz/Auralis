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
import com.auralis.app.ui.glass.GlassLevel
import com.auralis.app.ui.glass.glass
import com.auralis.app.ui.theme.Auralis

/**
 * Legacy glass helper kept for existing call sites; now delegates to the single app glass
 * pipeline at Light strength.
 */
@Composable
fun Modifier.glassSurface(shape: Shape): Modifier =
    this.glass(hazeState = null, shape = shape, level = GlassLevel.LIGHT)
