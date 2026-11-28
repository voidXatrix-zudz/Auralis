package com.auralis.app.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.domain.model.Lyrics
import com.auralis.app.domain.model.LyricsBaseColor
import com.auralis.app.domain.model.LyricsDisplayMode
import com.auralis.app.domain.model.LyricsFontChoice
import com.auralis.app.domain.model.LyricsGlowPalette
import com.auralis.app.domain.model.LyricsProviderChoice
import com.auralis.app.domain.model.LyricsSettings
import kotlin.math.roundToInt

/**
 * Advanced Lyrics Settings bottom sheet ported from Node Player.
 * Contains all 14 fonts with live previews, text size/bold, base colors, glow palette & bloom,
 * inactive opacity, kinetic motion controls, display mode, romanization, preferred provider,
 * and timing offset trigger. (Classic vs Cinematic toggle is intentionally kept at top-level
 * menus and Main Settings.)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LyricsSettingsBottomSheet(
    settings: LyricsSettings,
    onUpdateSettings: (LyricsSettings) -> Unit,
    onDismissRequest: () -> Unit,
    onShuffleLayout: () -> Unit = {},
    onOpenOffsetControls: (() -> Unit)? = null,
    onSearchAgain: (() -> Unit)? = null,
    currentLyrics: Lyrics? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = MaterialTheme.colorScheme.primary
    val fontResolver = LocalLyricsFontResolver.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xF0121217),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxSheetHeight = maxHeight * 0.72f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Advanced Lyrics Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 1. Typography & 14 Custom Fonts
                SectionHeader("TYPOGRAPHY & FONT (14 STYLES)")
                Text(
                    text = "Papyrus, Minecraft, Patrick Hand & 11 popular styles",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LyricsFontChoice.entries.forEach { font ->
                        val isSelected = settings.fontChoice == font
                        val previewFontFamily = fontResolver(font)

                        Box(
                            modifier = Modifier
                                .width(164.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f),
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable { onUpdateSettings(settings.copy(fontChoice = font)) }
                                .padding(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = font.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.90f),
                                        maxLines = 1,
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = accent,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = font.subtitle,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.50f),
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "The rhythm of music",
                                    fontFamily = previewFontFamily,
                                    fontSize = 15.sp,
                                    fontWeight = if (settings.isBold) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color(settings.baseColor.colorArgb) else Color.White.copy(alpha = 0.75f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 2. Text Size & Bold
                SettingSliderRow(
                    label = "Lyrics Text Size",
                    valueText = "%.0f%%".format(settings.fontSizeScale * 100f),
                    value = settings.fontSizeScale,
                    valueRange = 0.70f..1.60f,
                    steps = 17,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(fontSizeScale = (it * 20f).roundToInt() / 20f)) },
                )

                SheetToggleRow(
                    title = "Bold Letterforms",
                    subtitle = "Enhance contrast and weight across backdrop",
                    checked = settings.isBold,
                    accent = accent,
                    onCheckedChange = { onUpdateSettings(settings.copy(isBold = it)) },
                )

                Spacer(Modifier.height(14.dp))

                // 3. Base Lyrics Color
                SectionHeader("BASE LYRICS COLOR")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LyricsBaseColor.entries.forEach { col ->
                        val isSelected = settings.baseColor == col
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(col.colorArgb) else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { onUpdateSettings(settings.copy(baseColor = col)) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(col.colorArgb))
                                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (col == LyricsBaseColor.PURE_WHITE || col == LyricsBaseColor.WARM_CREAM) Color.Black else Color.White),
                                        )
                                    }
                                }
                                Text(
                                    text = col.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 4. Glow Palette & Bloom
                SectionHeader("GLOW PALETTE & BLOOM")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    LyricsGlowPalette.entries.forEach { p ->
                        val isSelected = settings.palette == p
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isSelected) Color(p.midColorArgb).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(p.litColorArgb) else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(999.dp),
                                )
                                .clickable { onUpdateSettings(settings.copy(palette = p)) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(p.litColorArgb)),
                                )
                                Text(
                                    text = p.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                SettingSliderRow(
                    label = "Glow Bloom & Intensity",
                    valueText = if (settings.glowIntensity <= 0.05f) "Off (Crisp)" else "%.2fx".format(settings.glowIntensity),
                    value = settings.glowIntensity,
                    valueRange = 0.00f..2.00f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(glowIntensity = it)) },
                )

                SettingSliderRow(
                    label = "Inactive Line Opacity",
                    valueText = "%.0f%%".format(settings.inactiveOpacity * 100f),
                    value = settings.inactiveOpacity,
                    valueRange = 0.15f..0.75f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(inactiveOpacity = it)) },
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 8.dp))

                // 5. Kinetic & Viewport Parameters
                SectionHeader("MOTION & VIEWPORT")
                SettingSliderRow(
                    label = "Common Zoom",
                    valueText = "%.2fx".format(settings.commonZoom),
                    value = settings.commonZoom,
                    valueRange = 0.60f..1.80f,
                    steps = 23,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(commonZoom = (it * 20f).roundToInt() / 20f)) },
                )
                SettingSliderRow(
                    label = "Line & Stanza Spacing",
                    valueText = "%.2fx".format(settings.verticalSpacing),
                    value = settings.verticalSpacing,
                    valueRange = 0.70f..1.80f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(verticalSpacing = it)) },
                )
                SettingSliderRow(
                    label = "Camera Glide Responsiveness",
                    valueText = "%.2f".format(settings.cameraGlide),
                    value = settings.cameraGlide,
                    valueRange = 0.30f..1.80f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(cameraGlide = it)) },
                )
                SettingSliderRow(
                    label = "Word Bounce & Pop",
                    valueText = "%.2f".format(settings.bounce),
                    value = settings.bounce,
                    valueRange = 0.00f..1.80f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(bounce = it)) },
                )
                SettingSliderRow(
                    label = "Horizontal Choreography Swing",
                    valueText = "%.2f".format(settings.lrSwing),
                    value = settings.lrSwing,
                    valueRange = 0.00f..1.50f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(lrSwing = it)) },
                )
                SettingSliderRow(
                    label = "Sparkle & Starfield Density",
                    valueText = "%.0f%%".format(settings.sparkleDensity * 100f),
                    value = settings.sparkleDensity,
                    valueRange = 0.00f..1.00f,
                    accent = accent,
                    onValueChange = { onUpdateSettings(settings.copy(sparkleDensity = it)) },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable { onShuffleLayout() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("Shuffle Kinetic Layout", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 6. Display Mode & Romanization
                SectionHeader("DISPLAY MODE & LANGUAGE")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LyricsDisplayMode.entries.forEach { mode ->
                        val isSelected = settings.lyricsDisplayMode == mode
                        val label = when (mode) {
                            LyricsDisplayMode.ORIGINAL_ONLY -> "Original"
                            LyricsDisplayMode.DUAL_LINE -> "Dual-Line"
                            LyricsDisplayMode.ROMANIZED_ONLY -> "Romanized"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) Color.White else Color.Transparent)
                                .clickable { onUpdateSettings(settings.copy(lyricsDisplayMode = mode)) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val romanizeSupported = android.os.Build.VERSION.SDK_INT >= 29
                SheetToggleRow(
                    title = "Romanize Foreign Lyrics",
                    subtitle = if (romanizeSupported) {
                        "Transliterate Korean, Japanese, Chinese, Hindi, Cyrillic"
                    } else {
                        "Requires Android 10 (API 29) or higher"
                    },
                    checked = settings.romanizeForeignLyrics && romanizeSupported,
                    accent = accent,
                    enabled = romanizeSupported,
                    onCheckedChange = { if (romanizeSupported) onUpdateSettings(settings.copy(romanizeForeignLyrics = it)) },
                )

                Spacer(Modifier.height(14.dp))

                // 7. Preferred Provider
                SectionHeader("PREFERRED LYRICS PROVIDER")
                val activeSource = currentLyrics?.provider?.lowercase() ?: currentLyrics?.source?.name?.lowercase() ?: ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LyricsProviderChoice.entries.forEach { provider ->
                        val isSelected = settings.preferredProvider == provider
                        val isFound = currentLyrics != null && (!currentLyrics.synced.isNullOrEmpty() || !currentLyrics.plain.isNullOrBlank()) &&
                            (provider == LyricsProviderChoice.AUTO || activeSource.contains(provider.id))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable {
                                    onUpdateSettings(settings.copy(preferredProvider = provider))
                                    onSearchAgain?.invoke()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = provider.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.Check else if (isFound) Icons.Rounded.MusicNote else Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = if (isSelected) accent else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = if (provider.isSyllableCapable) "Syllable" else "Line",
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 8. Timing Offset & Search Actions
                if (onOpenOffsetControls != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDismissRequest()
                                onOpenOffsetControls()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Timer, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                        Text("Adjust Per-Track Sync Offset (± ms)", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
                if (onSearchAgain != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDismissRequest()
                                onSearchAgain()
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                        Text("Search & Refetch Lyrics", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SheetToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f))
            Text(text = subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f))
            Text(text = valueText, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}
