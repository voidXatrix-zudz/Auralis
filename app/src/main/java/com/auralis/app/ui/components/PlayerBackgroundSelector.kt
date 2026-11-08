package com.auralis.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.auralis.app.R
import com.auralis.app.domain.model.PlayerBackground

/**
 * The drawable backing each choice, or null for [PlayerBackground.NONE] (the existing look).
 * Kept private so the resource names live in one place.
 */
private fun PlayerBackground.drawableRes(): Int? = when (this) {
    PlayerBackground.NONE -> null
    PlayerBackground.PHOTO_1 -> R.drawable.bg_player_1
    PlayerBackground.PHOTO_2 -> R.drawable.bg_player_2
}

private fun PlayerBackground.label(): String = when (this) {
    PlayerBackground.NONE -> "None"
    PlayerBackground.PHOTO_1 -> "Photo 1"
    PlayerBackground.PHOTO_2 -> "Photo 2"
}

/**
 * Three rounded preview thumbnails (None, Photo 1, Photo 2) with the selected option outlined in the
 * theme accent. Colours come from [MaterialTheme], so it reads correctly in both themes.
 */
@Composable
fun PlayerBackgroundSelector(
    selected: PlayerBackground,
    onSelect: (PlayerBackground) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerBackground.entries.forEach { option ->
            PlayerBackgroundOption(option, option == selected, { onSelect(option) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerBackgroundOption(
    option: PlayerBackground,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(0.72f)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(
                border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
                shape = shape,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = option.label() },
        contentAlignment = Alignment.Center,
    ) {
        val res = option.drawableRes()
        if (res == null) {
            Icon(Icons.Rounded.Block, contentDescription = null, tint = scheme.onSurfaceVariant)
        } else {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Draws the chosen photo edge to edge behind the player with a soft vertical scrim so text, controls
 * and the waveform stay readable. Does nothing for [PlayerBackground.NONE]. The painter is resolved
 * through [painterResource], which caches the decoded bitmap, so the image is decoded once and only
 * while the Now Playing screen is on screen (no startup cost, no launch flash).
 */
@Composable
fun PlayerBackgroundImage(background: PlayerBackground, modifier: Modifier = Modifier) {
    val res = background.drawableRes() ?: return
    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )
    }
}
