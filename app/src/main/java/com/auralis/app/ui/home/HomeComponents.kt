package com.auralis.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.app.R
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.components.AccountAvatar
import com.auralis.app.ui.components.AuralisMark
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle

/** Every Home measurement in one place. */
object HomeDimens {
    val ScreenPadding = 14.dp
    val TopBarHeight = 64.dp
    val MarkWidth = 28.dp
    val MarkHeight = 14.dp
    val AvatarSize = 40.dp
    val AvatarRing = 1.5.dp
    val AvatarInnerPad = 3.dp
    val SectionTitleTop = 20.dp
    val SectionTitleBottom = 10.dp
    val TopPickWidth = 280.dp
    val TopPickHeight = 300.dp
    val TopPickRadius = 28.dp
    val CardGap = 12.dp
    val PagePeek = 34.dp
    val PageGap = 12.dp
    val SongRowHeight = 64.dp
    val SongCover = 52.dp
    val SongCoverRadius = 8.dp
    val SongRowGap = 14.dp
    val LibraryTile = 150.dp
    val LibraryTileRadius = 20.dp
    val VideoWidth = 260.dp
    val VideoRadius = 16.dp
    val CompactThumbWidth = 84.dp
    val CompactRadius = 8.dp
    val SquareCard = 180.dp
    val SquareRadius = 16.dp
    val MoreCard = 150.dp
    val ArtistCard = 124.dp
    val ArtistAvatarSize = 112.dp
    val SectionGap = 24.dp
    val ListBottomPadding = 32.dp
    val PageSize = 4
    val CardInnerPad = 16.dp
    val CaptionGap = 8.dp
    val CardMarkWidth = 20.dp
    val CardMarkHeight = 10.dp
    val MiniCardRadius = 18.dp
    val PlaceholderIcon = 32.dp
    val RowEndGap = 8.dp
    val CompactEndGap = 4.dp
    val IconButtonSize = 40.dp
    val RowIconSize = 24.dp
    val PageMinWidth = 240.dp
    val ShowAllGap = 8.dp
    val StateRadius = 20.dp
    val StateCardInner = 18.dp
    val StateCardPadH = 16.dp
    val StateCardPadV = 12.dp
    val StateVerticalPad = 12.dp
    val StateHorizontalPad = 24.dp
    val EmptyVerticalPad = 48.dp
    val EmptyVerticalPadSmall = 40.dp
    val EmptyIconSize = 64.dp
    val EmptyGap = 12.dp
    val EmptyGapLarge = 16.dp
    val ConnectBodyTop = 6.dp
    val ConnectActionTop = 14.dp
    val SkeletonTitleTop = 18.dp
    val SkeletonRowPadV = 10.dp
    val SkeletonBarWidth = 140.dp
    val SkeletonBarWidthShort = 120.dp
    val SkeletonBarHeight = 18.dp
    val SkeletonBarRadius = 9.dp
    val SkeletonLineHeight = 14.dp
    val SkeletonLineRadius = 7.dp
    val SkeletonSubHeight = 12.dp
    val SkeletonSubRadius = 6.dp
    val SkeletonLineGap = 6.dp
    val RingStroke = 3.dp
    val BandWidth = 28.dp
}

/** Every Home text size in one place; the family comes from the app's Material typography. */
object HomeText {
    val SectionTitle: TextUnit = 18.sp
    val ShowAll: TextUnit = 13.sp
    val TopPickCaption: TextUnit = 12.sp
    val TopPickTitle: TextUnit = 20.sp
    val TopPickSubtitle: TextUnit = 13.sp
    val SongTitle: TextUnit = 16.sp
    val SongSubtitle: TextUnit = 13.sp
    val LibraryTitle: TextUnit = 14.sp
    val LibraryCaption: TextUnit = 12.sp
    val VideoTitle: TextUnit = 15.sp
    val VideoCaption: TextUnit = 12.sp
    val SquareTitle: TextUnit = 14.sp
}

/** AURALIS' own Home palette: canvas, placeholders and the muted accent set for flat cards. */
object HomeCardPalette {
    val Canvas = Color(0xFF000000)
    val Placeholder = Color(0xFF1A1A1A)
    val Surface = Color(0xFF141416)
    val SurfaceAlt = Color(0xFF1C1C1E)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF9A9A9A)
    val TextOnArt = Color.White
    val Scrim = Color.Black.copy(alpha = 0.55f)
    val AvatarRing = Color.White.copy(alpha = 0.35f)
    val TintLight = Color.White

    private val accents = listOf(
        Color(0xFF1F3B3A), // deep teal
        Color(0xFF4A3A1E), // warm amber
        Color(0xFF4A2B31), // dusty rose
        Color(0xFF1E3A26), // forest green
        Color(0xFF2A3450), // slate blue
        Color(0xFF4A2C22), // terracotta
        GlassTokens.Indigo, // Midnight Indigo
        Color(0xFF2E2A1C), // muted moss
    )

    val Accents: List<Color> get() = accents

    fun colorFor(seed: Int): Color = accents[((seed % accents.size) + accents.size) % accents.size]
}

/** The only difference between the Normal and Glass Home: text/outline/surface tokens. */
@Immutable
data class HomeStyle(
    val glass: Boolean,
    val text: Color,
    val textDim: Color,
    val outline: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val accent: Color,
)

@Composable
fun rememberHomeStyle(): HomeStyle = if (LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC) {
    HomeStyle(
        glass = true,
        text = GlassTokens.Cream,
        textDim = GlassTokens.Cream65,
        outline = GlassTokens.Cream30,
        surface = HomeCardPalette.Surface,
        surfaceAlt = HomeCardPalette.SurfaceAlt,
        accent = GlassTokens.Cream,
    )
} else {
    HomeStyle(
        glass = false,
        text = HomeCardPalette.TextPrimary,
        textDim = HomeCardPalette.TextSecondary,
        outline = HomeCardPalette.AvatarRing,
        surface = HomeCardPalette.Surface,
        surfaceAlt = HomeCardPalette.SurfaceAlt,
        accent = HomeCardPalette.TextPrimary,
    )
}

/** The scrolling, non-pinned Home top bar: original mark left, round profile avatar right. */
@Composable
fun HomeTopBar(
    avatarUrl: String?,
    accountName: String?,
    style: HomeStyle,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(HomeDimens.TopBarHeight)
            .padding(horizontal = HomeDimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AuralisMark(
            modifier = Modifier.width(HomeDimens.MarkWidth).height(HomeDimens.MarkHeight),
            color = style.text.copy(alpha = 0.9f),
        )
        Box(
            Modifier
                .size(HomeDimens.AvatarSize)
                .border(HomeDimens.AvatarRing, style.outline, CircleShape)
                .padding(HomeDimens.AvatarInnerPad)
                .clip(CircleShape)
                .background(HomeCardPalette.SurfaceAlt)
                .clickable(role = Role.Button, onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            AccountAvatar(
                imageUrl = avatarUrl,
                contentDescription = accountName ?: stringResource(R.string.home_account),
                modifier = Modifier.fillMaxSize(),
                iconTint = style.textDim,
            )
        }
    }
}

/** Plain left-aligned section title with an optional grey "Show all". */
@Composable
fun HomeSectionTitle(
    title: String,
    style: HomeStyle,
    modifier: Modifier = Modifier,
    onShowAll: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HomeDimens.ScreenPadding, end = HomeDimens.ScreenPadding)
            .padding(top = HomeDimens.SectionTitleTop, bottom = HomeDimens.SectionTitleBottom),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = style.text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = HomeText.SectionTitle,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onShowAll != null) {
            Text(
                stringResource(R.string.home_show_all),
                Modifier
                    .clickable(role = Role.Button, onClick = onShowAll)
                    .padding(start = HomeDimens.ShowAllGap),
                color = style.textDim,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = HomeText.ShowAll),
                maxLines = 1,
            )
        }
    }
}

/** Subtle press feedback shared by every tappable Home card. */
@Composable
internal fun rememberPressScale(): Pair<MutableInteractionSource, Float> {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "homePress",
    )
    return interaction to scale
}
