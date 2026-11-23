package com.auralis.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.app.R
import com.auralis.app.data.youtube.YtCard
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.components.ArtistAvatar
import com.auralis.app.ui.components.ArtworkImage
import com.auralis.app.ui.components.AuralisMark

/** Big discovery card: artwork or a flat AURALIS colour field with a generic geometric shape. */
@Composable
fun TopPickCard(
    pick: HomeTopPick,
    style: HomeStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(HomeDimens.TopPickRadius)
    val (interaction, scale) = rememberPressScale()
    val base = HomeCardPalette.colorFor(pick.accentSeed)
    Box(
        modifier
            .width(HomeDimens.TopPickWidth)
            .height(HomeDimens.TopPickHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(base)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        when {
            pick.track != null -> ArtworkImage(
                track = pick.track,
                artUri = pick.imageUrl,
                shape = RectangleShape,
                modifier = Modifier.fillMaxSize(),
            )
            !pick.imageUrl.isNullOrBlank() -> AsyncImage(
                model = pick.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(HomeCardPalette.Placeholder),
            )
            else -> MoodArtwork(seed = pick.accentSeed, base = base, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to HomeCardPalette.Scrim,
                ),
            ),
        )
        AuralisMark(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .width(20.dp)
                .height(10.dp),
            color = HomeCardPalette.TextOnArt.copy(alpha = 0.4f),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            if (pick.caption.isNotBlank()) {
                Text(
                    pick.caption,
                    color = HomeCardPalette.TextOnArt.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = HomeText.TopPickCaption),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                pick.title,
                color = HomeCardPalette.TextOnArt,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = HomeText.TopPickTitle,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pick.subtitle.isNotBlank()) {
                Text(
                    pick.subtitle,
                    color = HomeCardPalette.TextOnArt.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.TopPickSubtitle),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Square "From your library" tile with a 2x2 mosaic when four covers exist. */
@Composable
fun LibraryTile(tile: LibraryTileData, style: HomeStyle, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(HomeDimens.LibraryTileRadius)
    val (interaction, scale) = rememberPressScale()
    Column(
        modifier
            .width(HomeDimens.LibraryTile)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = tile.onClick),
    ) {
        Box(
            Modifier
                .size(HomeDimens.LibraryTile)
                .clip(shape)
                .background(HomeCardPalette.Placeholder),
        ) {
            when {
                tile.covers.size >= 4 -> LibraryMosaic(tile.covers)
                tile.covers.isNotEmpty() -> ArtworkImage(
                    track = tile.covers.first(),
                    shape = shape,
                    modifier = Modifier.fillMaxSize(),
                )
                !tile.coverUrl.isNullOrBlank() -> AsyncImage(
                    model = tile.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = style.textDim,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }
        }
        Text(
            tile.title,
            Modifier.padding(top = 8.dp),
            color = style.text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = HomeText.LibraryTitle,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tile.caption.isNotBlank()) {
            Text(
                tile.caption,
                color = style.textDim,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.LibraryCaption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryMosaic(covers: List<Track>) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            ArtworkImage(covers[0], Modifier.weight(1f).fillMaxHeight(), RectangleShape)
            ArtworkImage(covers[1], Modifier.weight(1f).fillMaxHeight(), RectangleShape)
        }
        Row(Modifier.weight(1f)) {
            ArtworkImage(covers[2], Modifier.weight(1f).fillMaxHeight(), RectangleShape)
            ArtworkImage(covers[3], Modifier.weight(1f).fillMaxHeight(), RectangleShape)
        }
    }
}

/** 16:9 music-video card. Tapping plays the audio stream through the normal path. */
@Composable
fun VideoCard(card: YtCard, style: HomeStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (interaction, scale) = rememberPressScale()
    Column(
        modifier
            .width(HomeDimens.VideoWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(HomeDimens.VideoRadius))
                .background(HomeCardPalette.Placeholder),
        ) {
            if (!card.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            card.title,
            Modifier.padding(top = 8.dp),
            color = style.text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = HomeText.VideoTitle,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.subtitle.isNotBlank()) {
            Text(
                card.subtitle,
                color = style.textDim,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.VideoCaption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Large square album/single card. */
@Composable
fun SquareArtCard(card: YtCard, style: HomeStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (interaction, scale) = rememberPressScale()
    Column(
        modifier
            .width(HomeDimens.SquareCard)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(HomeDimens.SquareCard)
                .clip(RoundedCornerShape(HomeDimens.SquareRadius))
                .background(HomeCardPalette.Placeholder),
        ) {
            if (!card.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = card.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            card.title,
            Modifier.padding(top = 8.dp),
            color = style.text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = HomeText.SquareTitle,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.subtitle.isNotBlank()) {
            Text(
                card.subtitle,
                color = style.textDim,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.LibraryCaption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Circular artist card, kept from the existing Home and only restyled. */
@Composable
fun HomeArtistCard(card: YtCard, style: HomeStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(HomeDimens.ArtistCard)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(
            name = card.title,
            browseId = card.browseId,
            imageUrl = card.thumbnailUrl,
            size = HomeDimens.ArtistAvatarSize,
        )
        Text(
            card.title,
            Modifier.padding(top = 8.dp),
            color = style.text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = HomeText.LibraryTitle),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.subtitle.isNotBlank()) {
            Text(
                card.subtitle,
                color = style.textDim,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = HomeText.LibraryCaption),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The "More" card that pages a YouTube shelf in, unchanged behaviour. */
@Composable
fun HomeMoreCard(style: HomeStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(HomeDimens.MoreCard)
            .height(HomeDimens.MoreCard)
            .clip(RoundedCornerShape(18.dp))
            .background(style.surfaceAlt)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = style.accent)
        Text(
            stringResource(R.string.home_more),
            color = style.textDim,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
