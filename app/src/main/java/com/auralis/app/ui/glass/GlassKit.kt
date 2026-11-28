package com.auralis.app.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.motion.pressScale
import com.auralis.app.ui.theme.GlassTokens
import kotlin.random.Random

/**
 * Premium Neo-style frosted glass panel:
 * Translucent smoked core, directional specular refraction border, soft drop shadow,
 * and top-to-bottom optical sheen.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassTokens.RadiusLg),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalDockGlassStyle.current
    // Cards share the dock material pipeline at Medium strength.
    val elevation = 10.dp * glass.shadowScale
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressScale(pressSource, 0.975f) else Modifier)
            .then(
                if (elevation > 0.dp) Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                ) else Modifier,
            )
            .clip(shape)
            .glassSurface(
                kind = GlassKind.Surface,
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = pressSource, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/**
 * Smoked-glass confirmation dialog with pill buttons. Dialogs live in their own window, so they
 * cannot sample the app backdrop; they use the same tint/outline optics instead of Haze.
 */
@Composable
fun GlassDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
) {
    val glass = LocalDockGlassStyle.current
    val shape = RoundedCornerShape(GlassTokens.RadiusCard)
    val enter = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 320f))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogBackdropBlur()
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassTokens.Canvas.copy(alpha = GlassMapping.DIALOG_SCRIM))
                    .clickable(onClick = onDismiss),
            )
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        scaleX = enter.value
                        scaleY = enter.value
                        alpha = ((enter.value - 0.92f) / 0.08f).coerceIn(0f, 1f)
                    }
                    .shadow(
                        elevation = 24.dp * glass.shadowScale,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.55f),
                        spotColor = Color.Black.copy(alpha = 0.65f),
                    )
                    // Swallow taps inside the panel so they cannot reach the dismiss scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .glassWindowSurface(shape)
                    .padding(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = GlassTokens.Silver)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassGhostButton(dismissLabel, onDismiss, Modifier.weight(1f))
                    GlassPrimaryButton(confirmLabel, onConfirm, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Glass dialog used for text prompts (connect/confirm). Dialogs live in their own window, so the
 * shared [DialogBackdropBlur] blurs the app behind on API 31+, a strong black scrim removes the
 * busy content from under the text, and [glassWindowSurface] adds the palette fill and hairline.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String = "Cancel",
    onDismiss: () -> Unit = onDismissRequest,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogBackdropBlur()
        Box(Modifier.fillMaxSize()) {
            // Scrim: tap-outside dismisses, and the app content is dimmed even below API 31.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(GlassTokens.Canvas.copy(alpha = GlassMapping.DIALOG_SCRIM))
                    .clickable(onClick = onDismissRequest),
            )
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    // Swallow taps inside the panel so they cannot reach the dismiss scrim.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .glassWindowSurface(RoundedCornerShape(GlassTokens.RadiusCard))
                    .padding(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = GlassTokens.Silver)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    if (extraLabel != null && onExtra != null) {
                        TextButton(onClick = onExtra) { Text(extraLabel) }
                    }
                    TextButton(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

/** Round translucent icon button with specular border. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    /** Small accent dot, used by the Settings icon when a release update is available. */
    badge: Boolean = false,
    /**
     * True when the button sits directly on the page backdrop (a header control): it then uses the
     * real backdrop glass (blur, lens, rim). Leave false inside a glass card, which is already frosted.
     */
    blur: Boolean = false,
) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(pressSource, 0.90f)
            .clip(CircleShape)
            .then(if (blur) Modifier.glassSurface(GlassKind.Surface, CircleShape) else Modifier.glassTint(CircleShape))
            .clickable(interactionSource = pressSource, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(size * 0.46f))
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 9.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(GlassTokens.Violet),
            )
        }
    }
}

/** Borderless icon button with a 48dp target, for dense rows. */
@Composable
fun GlassIconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GlassTokens.Cream55,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Chips placed straight on the app backdrop blur it; chips nested inside a glass card keep the
    // tint-only material so they do not re-sample the raw backdrop through the parent's frost.
    blur: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .then(
                when {
                    // Selected chips invert: solid cream fill with indigo text.
                    selected -> Modifier.background(GlassTokens.Cream)
                    blur -> Modifier.glassSurface(GlassKind.Surface, CircleShape)
                    else -> Modifier.glassTint(CircleShape)
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) GlassTokens.Indigo else GlassTokens.Cream65,
        )
    }
}

/**
 * Primary button: solid Vanilla Cream fill with Midnight Indigo text, a plain black shadow.
 */
@Composable
fun GlassPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(pressSource, 0.95f)
            .shadow(8.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f))
            .clip(CircleShape)
            .background(if (enabled) GlassTokens.Cream else GlassTokens.Cream20)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = GlassTokens.Indigo, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = GlassTokens.Indigo,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Outlined translucent pill button with specular glass edge. */
@Composable
fun GlassGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, blur: Boolean = false) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(pressSource, 0.95f)
            .clip(CircleShape)
            .then(if (blur) Modifier.glassSurface(GlassKind.Surface, CircleShape) else Modifier.glassTint(CircleShape))
            .clickable(interactionSource = pressSource, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

@Composable
fun GlassHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small uppercase section label with elegant tracking. */
@Composable
fun GlassSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 22.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    /**
     * Opt-in liquid pill: when a backdrop is available (Settings provides one) the field is drawn
     * with the dock material instead of a flat cream fill. Without a backdrop it is unchanged.
     */
    liquid: Boolean = false,
) {
    // Fill and hairline follow Glass clarity; no nested Haze effect inside text fields.
    val settings = LocalGlassSettings.current
    val fill = maxOf(GlassMapping.surfaceFillAlpha(settings.glassClarity), GlassMapping.NESTED_MIN_FILL)
    val hairline = GlassMapping.borderAlpha(settings.glassClarity)
    val liquidActive = liquid && LocalAppBackdrop.current != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = GlassTokens.Cream40) },
        leadingIcon = leading,
        trailingIcon = if (value.isNotEmpty()) {
            { GlassIconAction(Icons.Rounded.Close, "Clear", { onValueChange("") }, modifier = Modifier.size(36.dp)) }
        } else null,
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { onSearch?.invoke() },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = if (liquidActive) Color.Transparent else GlassTokens.Cream.copy(alpha = (fill + 0.04f).coerceAtMost(0.44f)),
            unfocusedContainerColor = if (liquidActive) Color.Transparent else GlassTokens.Cream.copy(alpha = fill),
            focusedBorderColor = if (liquidActive) GlassTokens.Cream.copy(alpha = 0.30f) else GlassTokens.Cream.copy(alpha = (hairline + 0.04f).coerceAtMost(0.30f)),
            unfocusedBorderColor = if (liquidActive) Color.Transparent else GlassTokens.Cream.copy(alpha = hairline),
            cursorColor = GlassTokens.Cream,
            focusedTextColor = GlassTokens.Cream,
            unfocusedTextColor = GlassTokens.Cream,
        ),
        modifier = if (liquidActive) modifier.auralisGlass(CircleShape, GlassRole.Dock) else modifier,
    )
}

/** Artwork with glass-style corners and specular border. */
@Composable
fun GlassArtwork(track: Track? = null, modifier: Modifier = Modifier, radius: Dp = 18.dp, artUri: Any? = null) {
    ArtworkImage(track, modifier.clip(RoundedCornerShape(radius)).border(1.dp, GlassTokens.BorderBrush, RoundedCornerShape(radius)), shape = RoundedCornerShape(radius), artUri = artUri)
}

@Composable
fun GlassEmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .glassTint(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Album, null, tint = GlassTokens.Violet, modifier = Modifier.size(42.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        action()
    }
}

/**
 * Cream waveform seek bar: symmetric rounded bars on the black backdrop. Played bars are solid
 * cream, unplayed bars cream at 20%, matching every other progress control.
 */
@Composable
fun GlassWaveSeek(
    seed: String,
    progress: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fixed sample pool (192 values) smoothed with a small kernel plus a few tall peaks.
    val samples = remember(seed) {
        val random = Random(seed.hashCode())
        val count = 192
        val raw = FloatArray(count) { random.nextFloat() }
        val smooth = FloatArray(count) { i ->
            val a = raw[(i - 1 + count) % count]
            val b = raw[i]
            val c = raw[(i + 1) % count]
            (a + b * 2f + c) / 4f
        }
        repeat(5) {
            val center = random.nextInt(count)
            val reach = 3 + random.nextInt(5)
            val gain = 0.45f + random.nextFloat() * 0.55f
            for (offset in -reach..reach) {
                val index = (center + offset + count) % count
                val falloff = 1f - kotlin.math.abs(offset).toFloat() / (reach + 1)
                smooth[index] = (smooth[index] + gain * falloff).coerceAtMost(1f)
            }
        }
        smooth
    }
    val clamped = progress.coerceIn(0f, 1f)
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    var touchX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        scrub((offset.x / width).coerceIn(0f, 1f))
                        end()
                    }
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        touchX = offset.x
                        val width = size.width.toFloat()
                        if (width > 0f) scrub((offset.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        touchX = null
                        end()
                    },
                    onDragCancel = {
                        touchX = null
                        end()
                    },
                ) { change, _ ->
                    change.consume()
                    touchX = change.position.x
                    val width = size.width.toFloat()
                    if (width > 0f) scrub((change.position.x / width).coerceIn(0f, 1f))
                }
            },
    ) {
        val barWidth = 3.5.dp.toPx()
        val gap = 3.dp.toPx()
        val slot = barWidth + gap
        val count = (((size.width + gap) / slot).toInt()).coerceAtLeast(1)
        val usedWidth = count * slot - gap
        val startX = ((size.width - usedWidth) / 2f).coerceAtLeast(0f)
        val midY = size.height / 2f
        val minHeight = 6.dp.toPx()
        val maxHeight = 44.dp.toPx().coerceAtMost(size.height - 2.dp.toPx())
        val reach = slot * 3f
        val touch = touchX

        for (i in 0 until count) {
            val t = if (count <= 1) 0f else i / (count - 1).toFloat()
            val sample = samples[(t * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)]
            var height = minHeight + (maxHeight - minHeight) * sample
            val centerFraction = (i + 0.5f) / count
            // The bar under the playhead is the thumb: slightly taller than its neighbours.
            if (kotlin.math.abs(centerFraction - clamped) <= 0.5f / count) {
                height = (height * 1.18f).coerceAtMost(maxHeight)
            }
            // Bars near the finger swell a little while scrubbing.
            if (touch != null) {
                val dx = kotlin.math.abs(startX + i * slot + slot / 2f - touch)
                if (dx < reach) {
                    height = (height * (1f + 0.22f * (1f - dx / reach))).coerceAtMost(maxHeight)
                }
            }
            val x = startX + i * slot + (slot - barWidth) / 2f
            val top = midY - height / 2f
            if (centerFraction <= clamped) {
                // Faint bloom behind the played bars.
                drawRoundRect(
                    color = GlassTokens.Cream14,
                    topLeft = Offset(x - barWidth * 0.5f, top - 2.dp.toPx()),
                    size = Size(barWidth * 2f, height + 4.dp.toPx()),
                    cornerRadius = CornerRadius(barWidth),
                )
                drawRoundRect(
                    color = GlassTokens.Cream,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            } else {
                drawRoundRect(
                    color = GlassTokens.Cream20,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}
