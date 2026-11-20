package com.auralis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.performance.VisualPerformanceTier
import com.auralis.app.ui.theme.GlassTokens

/**
 * App-wide background.
 *
 * NORMAL reproduces the original solid background exactly. GLASSMORPHIC draws a flat Midnight
 * Indigo field: the two-colour palette forbids gradients and artwork-derived ambient pools, and
 * every glass surface is a Vanilla Cream tint on top of this one colour.
 */
@Composable
fun AuralisBackdrop(
    themeStyle: ThemeStyle,
    artColor: Int?,
    tier: VisualPerformanceTier,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = if (themeStyle == ThemeStyle.GLASSMORPHIC) GlassTokens.Indigo else MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(base), content = content)
}