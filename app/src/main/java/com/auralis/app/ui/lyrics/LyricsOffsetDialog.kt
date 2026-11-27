package com.auralis.app.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Lyrics timing offset dialog with coarse (±500 ms) and fine (±50 ms) steppers.
 */
@Composable
fun LyricsOffsetDialog(
    currentOffsetMs: Int,
    onAdjustOffset: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF012141E))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Lyrics Sync Offset",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    ),
                )

                Spacer(modifier = Modifier.height(8.dp))

                val sign = if (currentOffsetMs > 0) "+" else ""
                Text(
                    text = "$sign$currentOffsetMs ms",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (currentOffsetMs != 0) Color(0xFF5EEAD4) else Color.White,
                    ),
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OffsetStepPill("-50 ms") { onAdjustOffset(-50) }
                    Spacer(modifier = Modifier.width(12.dp))
                    OffsetStepPill("+50 ms") { onAdjustOffset(50) }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OffsetStepPill("-500 ms") { onAdjustOffset(-500) }
                    Spacer(modifier = Modifier.width(12.dp))
                    OffsetStepPill("+500 ms") { onAdjustOffset(500) }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFFF6B6B)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onReset() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    OffsetStepPill("Done", highlighted = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun OffsetStepPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) Color.Black else Color.White,
            ),
        )
    }
}
