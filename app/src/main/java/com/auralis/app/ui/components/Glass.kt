package com.auralis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.auralis.app.ui.glass.GlassKind
import com.auralis.app.ui.glass.glassSurface as applyGlassSurface

/**
 * Legacy shape-only helper. All glass now routes through the single
 * `Modifier.glassSurface(kind, ...)` pipeline; a caller that only knows a shape gets a Surface
 * (never the dock) so the two controls stay correctly scoped.
 */
@Composable
fun Modifier.glassSurface(shape: Shape): Modifier = this.applyGlassSurface(GlassKind.Surface, shape)