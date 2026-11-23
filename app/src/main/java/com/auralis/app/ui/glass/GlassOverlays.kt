package com.auralis.app.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * Tracks how many glass menus are currently open so a single root-level scrim can dim the app
 * behind all of them. Menus live in their own window and cannot draw the scrim themselves.
 */
class GlassOverlayState {
    private var openMenuCount by mutableIntStateOf(0)

    /** How many glass menus are currently open. */
    val openMenus: Int get() = openMenuCount

    internal fun menuOpened() {
        openMenuCount++
    }

    internal fun menuClosed() {
        openMenuCount--
    }
}

val LocalGlassOverlay = staticCompositionLocalOf { GlassOverlayState() }

/**
 * Non-interactive dim drawn above the screen content (and the dock) while a glass menu is open.
 * Tap-outside dismissal stays with the menu popup, so this layer must not consume touches.
 */
@Composable
fun GlassOverlayScrim(modifier: Modifier = Modifier) {
    val overlay = LocalGlassOverlay.current
    if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC && overlay.openMenus > 0) {
        Box(modifier.fillMaxSize().background(GlassTokens.Canvas.copy(alpha = GlassMapping.MENU_BACKDROP_SCRIM)))
    }
}

/**
 * The one menu surface.
 *
 * Material3's [DropdownMenu] still owns anchoring, outside-tap dismissal and back handling; this
 * wrapper only swaps the surface for the glass material and turns on window-level blur-behind
 * (a menu is its own window, so it cannot sample the in-composition Haze layer). Normal keeps the
 * stock menu untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalThemeStyle.current != ThemeStyle.GLASSMORPHIC) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
            content()
        }
        return
    }

    val overlay = LocalGlassOverlay.current
    val perf = LocalDockGlassStyle.current
    DisposableEffect(expanded) {
        if (expanded) overlay.menuOpened()
        onDispose { if (expanded) overlay.menuClosed() }
    }

    val shape = RoundedCornerShape(GlassTokens.RadiusCard)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // Upper bound keeps the long Now Playing menu off the transport controls; Material's own
        // scroll state takes over inside it.
        modifier = modifier.heightIn(max = maxHeight),
        shape = shape,
        containerColor = glassWindowPanelColor(),
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        border = if (perf.outlines) BorderStroke(1.dp, GlassTokens.Cream14) else null,
    ) {
        DialogBackdropBlur()
        // 4dp here + Material's own 12dp item padding = the 16dp row inset.
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            content()
        }
    }
}

/**
 * The one bottom-sheet surface. A sheet is a dialog window, so [DialogBackdropBlur] is the only
 * way Live blur can reach it. Normal keeps the stock sheet untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalThemeStyle.current != ThemeStyle.GLASSMORPHIC) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            content = content,
        )
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = glassWindowPanelColor(),
        contentColor = GlassTokens.Cream,
        tonalElevation = 0.dp,
        scrimColor = GlassTokens.Canvas.copy(alpha = GlassMapping.SHEET_SCRIM),
    ) {
        DialogBackdropBlur()
        content()
    }
}

/**
 * Drop-in glass counterpart of Material3's [AlertDialog] (same slots, same behaviour).
 *
 * Normal delegates to [AlertDialog] with the caller's own properties, so its look is untouched.
 * Glass draws the shared window material: scrim, blurred backdrop on API 31+, black tint,
 * cream hairline and the palette text styles.
 */
@Composable
fun GlassMaterialAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties? = null,
) {
    if (LocalThemeStyle.current != ThemeStyle.GLASSMORPHIC) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties ?: DialogProperties(),
        )
        return
    }

    val shape = RoundedCornerShape(GlassTokens.RadiusCard)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties ?: DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogBackdropBlur()
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassTokens.Canvas.copy(alpha = GlassMapping.DIALOG_SCRIM))
                    .clickable(onClick = onDismissRequest),
            )
            Column(
                modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    // Swallow taps inside the panel so they cannot reach the dismiss scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .shadow(
                        elevation = 24.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.55f),
                        spotColor = Color.Black.copy(alpha = 0.65f),
                    )
                    .glassWindowSurface(shape)
                    .padding(24.dp),
            ) {
                if (icon != null) {
                    Box(Modifier.padding(bottom = 12.dp)) { icon() }
                }
                if (title != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides GlassTokens.Cream,
                        LocalTextStyle provides MaterialTheme.typography.headlineSmall,
                    ) { title() }
                }
                if (text != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    ) {
                        Box(
                            Modifier.padding(
                                top = if (title != null) 8.dp else 0.dp,
                                bottom = 20.dp,
                            ),
                        ) { text() }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
