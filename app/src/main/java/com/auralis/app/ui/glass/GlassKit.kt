package com.auralis.app.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Brush
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
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalGlassOptics
import com.auralis.app.ui.theme.Auralis
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
    val extras = Auralis.extras
    val glass = LocalDockGlassStyle.current
    // Cards share the dock material pipeline at Medium strength.
    val elevation = 10.dp * glass.shadowScale
    Box(
        modifier
            .then(
                if (elevation > 0.dp) Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color(0xFF0F0B1E).copy(alpha = 0.45f),
                ) else Modifier,
            )
            .clip(shape)
            .glass(
                hazeState = null,
                shape = shape,
                level = GlassLevel.MEDIUM,
                style = glass,
                fallbackSurface = extras.glass,
            )
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
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
                    .clip(shape)
                    .glass(
                        hazeState = null,
                        shape = shape,
                        level = GlassLevel.STRONG,
                        style = glass,
                        fallbackSurface = GlassTokens.Surface,
                    )
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
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .background(GlassTokens.SurfaceSheen)
            .border(1.dp, GlassTokens.BorderBrush, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val violet = GlassTokens.Violet
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(violet.copy(alpha = 0.28f), GlassTokens.Ice.copy(alpha = 0.18f)))
                } else {
                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f)))
                },
            )
            .border(
                1.dp,
                if (selected) Brush.linearGradient(listOf(violet.copy(alpha = 0.75f), GlassTokens.Ice.copy(alpha = 0.50f)))
                else GlassTokens.BorderBrush,
                CircleShape,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) GlassTokens.Silver else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Filled Neo gradient button with subtle glow shadow. */
@Composable
fun GlassPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .shadow(12.dp, CircleShape, clip = false, ambientColor = GlassTokens.Violet.copy(alpha = 0.35f), spotColor = GlassTokens.Violet.copy(alpha = 0.50f))
            .clip(CircleShape)
            .background(
                if (enabled) GlassTokens.AccentGradient
                else Brush.linearGradient(listOf(GlassTokens.Violet.copy(alpha = 0.35f), GlassTokens.Violet.copy(alpha = 0.35f))),
            )
            .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = GlassTokens.OnAccent, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = GlassTokens.OnAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Outlined translucent pill button with specular glass edge. */
@Composable
fun GlassGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .background(GlassTokens.SurfaceSheen)
            .border(1.dp, GlassTokens.BorderBrush, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = leading,
        trailingIcon = if (value.isNotEmpty()) {
            { GlassIconAction(Icons.Rounded.Close, "Clear", { onValueChange("") }, modifier = Modifier.size(36.dp)) }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.09f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            focusedBorderColor = GlassTokens.Violet.copy(alpha = 0.70f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
            cursorColor = GlassTokens.Violet,
            focusedTextColor = GlassTokens.Silver,
            unfocusedTextColor = GlassTokens.Silver,
        ),
        modifier = modifier,
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
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, GlassTokens.BorderBrush, CircleShape),
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
 * White waveform seek bar:
 * symmetric rounded bars on the dark glass backdrop, bright played bars with a faint glow,
 * and dim unplayed bars. The waveform is generated once per track and sampled to the measured
 * bar count, so it never changes shape on recomposition or rotation.
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
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(x - barWidth * 0.5f, top - 2.dp.toPx()),
                    size = Size(barWidth * 2f, height + 4.dp.toPx()),
                    cornerRadius = CornerRadius(barWidth),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            } else {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.30f),
                    topLeft = Offset(x, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}
