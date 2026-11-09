package com.auralis.app.ui.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as dpLerp
import androidx.compose.ui.unit.sp
import com.auralis.app.BuildConfig
import com.auralis.app.domain.model.Track
import com.auralis.app.ui.glass.DockGlassStyle
import com.auralis.app.ui.glass.LocalDockGlassStyle
import com.auralis.app.ui.glass.GlassLevel
import com.auralis.app.ui.glass.glass
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.nav.Tab
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalGlassOptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ONE dock, shared by BOTH themes. Look is injected through [DockStyle] and the glass
 * sliders; layout, icons, morph behaviour and every animation are shared so the themes
 * cannot drift apart.
 */
enum class DockStyle { SOLID, GLASS }

/** Public dock geometry, also used by the root screen to reserve the scrolling list's bottom space. */
object DockMetrics {
    val SideMargin: Dp = 12.dp
    val BottomMargin: Dp = 12.dp
    /** Glass-only floating geometry: ~12dp edge padding like the reference dock. */
    val GlassSideMargin: Dp = 14.dp
    val GlassBottomMargin: Dp = 8.dp
    val ExpandedHeight: Dp = 114.dp      // 48dp mini pill + 8dp gap + 58dp nav pill
    val ExpandedNavOnlyHeight: Dp = 58.dp // no track: the nav pill alone
    val CollapsedHeight: Dp = 56.dp

    /** Visible dock height for the current morph progress. */
    fun height(hasTrack: Boolean, progress: Float): Dp =
        dpLerp(if (hasTrack) ExpandedHeight else ExpandedNavOnlyHeight, CollapsedHeight, progress.coerceIn(0f, 1f))
}

/**
 * Extra bottom space glass screens must reserve inside their scrolling lists so the last item can
 * scroll clear of the floating dock. The dock overlays the content edge-to-edge, so this padding
 * lives in the list's contentPadding (not on the list container).
 */
val LocalDockBottomInset = compositionLocalOf { 0.dp }

// Shared dock spring: dampingRatio ~0.85, medium-low stiffness for the ~300-350ms morph.
private val DockSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 320f)

// Thin specular rim: bright top-left catch, faint middle, soft bottom light.
private val DockBorderBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.35f),
    0.5f to Color.White.copy(alpha = 0.08f),
    1f to Color.White.copy(alpha = 0.20f),
)

private val MiniHeightExpanded = 48.dp
private val MiniHeightCollapsed = 56.dp
private val NavHeight = 58.dp
private val RowHeight = 56.dp
private val CircleSize = 56.dp
private val EdgeGap = 8.dp

private fun lerpF(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * Scroll-driven dock morph. Attach the state itself to an ancestor of every scrolling screen
 * via Modifier.nestedScroll. ONE continuous progress value [progress] (0f expanded -> 1f
 * collapsed) is driven by a single spring Animatable; the dock never state-swaps or jumps.
 * Triggers: collapse after ~40dp of downward scroll; expand after ~6dp of upward scroll
 * (hysteresis prevents flicker).
 *
 * @param animationScope MUST be a composition-bound scope (rememberCoroutineScope) so
 * `animateTo` runs on the composition's MonotonicFrameClock.
 */
class DockScrollState(density: Density, animationScope: CoroutineScope, private val reduceMotion: Boolean = false) : NestedScrollConnection, Density by density {

    private val animationScope: CoroutineScope = animationScope
    private val morph = Animatable(0f)

    /** 0f = fully expanded, 1f = fully collapsed. Spring-animated; safe to read in composition. */
    val progress: Float get() = morph.value

    /** Kept part of the public API; callers may disable scrolling triggers. */
    var canCollapse: Boolean = true

    val collapsed: Boolean get() = morph.targetValue >= 0.5f

    /** Force expansion (for example when a pushed screen replaces the tab content). */
    fun expand() {
        if (morph.targetValue != 0f) animateTo(0f)
    }

    private fun setTarget(target: Float) {
        if (morph.targetValue != target) animateTo(target)
    }

    private fun animateTo(target: Float) {
        animationScope.launch {
            // Respect the system "reduce animations" setting: snap instead of springing.
            if (reduceMotion) morph.snapTo(target) else morph.animateTo(target, DockSpring)
        }
    }

    private var downAccumulator = 0f
    private var upAccumulator = 0f

    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
        // Compose reports negative y while the user scrolls the content down (finger moves up).
        val dy = consumed.y
        if (dy < 0f) {
            upAccumulator = 0f
            if (canCollapse && morph.targetValue < 0.5f) {
                downAccumulator += -dy
                if (downAccumulator >= 14.dp.toPx()) {
                    downAccumulator = 0f
                    setTarget(1f)
                }
            } else {
                downAccumulator = 0f
            }
        } else if (dy > 0f) {
            downAccumulator = 0f
            if (morph.targetValue > 0.5f) {
                upAccumulator += dy
                if (upAccumulator >= 2.dp.toPx()) {
                    upAccumulator = 0f
                    setTarget(0f)
                }
            } else {
                upAccumulator = 0f
            }
        }
        return androidx.compose.ui.geometry.Offset.Zero
    }
}

@Composable
fun rememberDockScrollState(): DockScrollState {
    val density = LocalDensity.current
    // Composition-bound scope: the morph spring needs the composition's MonotonicFrameClock.
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    return remember(density, reduceMotion) { DockScrollState(density, scope, reduceMotion) }
}

private data class DockNavItem(val tab: Tab, val label: String, val icon: ImageVector)

/** Glass order is deliberately Home, Library, Equalizer, Search (Settings lives on Home). */
private val GlassDockNavItems = listOf(
    DockNavItem(Tab.HOME, "Home", Icons.Rounded.Home),
    DockNavItem(Tab.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    DockNavItem(Tab.EQUALIZER, "Equalizer", Icons.Rounded.Equalizer),
    DockNavItem(Tab.SEARCH, "Search", Icons.Rounded.Search),
)

/** The Normal dock is untouched: it keeps its original five slots, including Settings. */
private val SolidDockNavItems = listOf(
    DockNavItem(Tab.HOME, "Home", Icons.Rounded.Home),
    DockNavItem(Tab.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    DockNavItem(Tab.SEARCH, "Search", Icons.Rounded.Search),
    DockNavItem(Tab.EQUALIZER, "Equalizer", Icons.Rounded.Equalizer),
    DockNavItem(Tab.SETTINGS, "Settings", Icons.Rounded.Settings),
)

/**
 * Floating dock with two continuous states:
 *  - p = 0: mini-player pill above a five-slot navigation pill.
 *  - p = 1: one row with a Home circle, the mini pill and a Search circle.
 *
 * Geometry lives in ONE custom [Layout] with exactly four children: navPill, homeCircle,
 * searchCircle and miniPill. Every position/size is derived from p and from the real width
 * supplied by the parent constraints, so the dock works from 360dp phones to tablets and in
 * landscape. No child swaps in or out; all four are always present and the invisible layer is
 * disabled for clicks.
 *
 * @param progress lambda returning the 0f..1f morph value, already spring-animated by
 *   [DockScrollState]; read inside the dock so only the dock recomposes per frame.
 * @param glass resolved tint/blur/performance style from [LocalDockGlassStyle]; ignored on SOLID.
 */
@Composable
fun AppDock(
    style: DockStyle,
    progress: () -> Float,
    hasTrack: Boolean,
    selected: Tab?,
    track: Track?,
    playing: Boolean,
    onSelectTab: (Tab) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onExpand: () -> Unit = {},
) {
    val glass = LocalDockGlassStyle.current
    if (style == DockStyle.GLASS) {
        GlassDock(
            progress = progress,
            hasTrack = hasTrack,
            selected = selected,
            track = track,
            playing = playing,
            onSelectTab = onSelectTab,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onOpenNowPlaying = onOpenNowPlaying,
            modifier = modifier,
            hazeState = hazeState,
            onExpand = onExpand,
            glass = glass,
        )
        return
    }
    // Reading the spring here keeps the per-frame morph work inside the dock instead of
    // recomposing the whole app shell.
    val p = progress().coerceIn(0f, 1f)
    // Debug-only proof of exactly what the dock received (tint, effective blur, caps).
    LaunchedEffect(glass) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "AuralisDock",
                "tint=${glass.tintAlpha} blur=${glass.blurDp}dp enabled=${glass.blurEnabled} " +
                    "refraction=${glass.refractionEnabled} downsample=${glass.downsample} " +
                    "reason=${glass.limitedReason}",
            )
        }
    }

    // Phase values are pure functions of p, so expanding is the exact mirror of collapsing.
    val navProgress = (p / 0.5f).coerceIn(0f, 1f)
    val navAlpha = 1f - navProgress
    val navScale = 1f - 0.1f * navProgress
    val labelAlpha = (1f - p / 0.35f).coerceIn(0f, 1f)
    val circleIn = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
    val circleScale = 0.6f + 0.4f * circleIn
    val nextAlpha = (1f - p * 2f).coerceIn(0f, 1f)

    val dockHeight = DockMetrics.height(hasTrack, p)
    val showsMini = hasTrack && track != null
    // Glass floats with wider gutters; the Normal dock keeps its original 12dp geometry.
    val sideMargin = if (style == DockStyle.GLASS) DockMetrics.GlassSideMargin else DockMetrics.SideMargin

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(dockHeight)
            .padding(horizontal = sideMargin),
        content = {
            // Order matters for z-order: the mini pill is drawn last so it travels over the
            // nav pill during the morph. Exactly four children.
            DockNavPill(
                style = style,
                hazeState = hazeState,
                glass = glass,
                selected = selected,
                labelAlpha = labelAlpha,
                clickable = p < 0.5f,
                onSelectTab = onSelectTab,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = navAlpha
                        scaleX = navScale
                        scaleY = navScale
                    },
            )
            DockEdgeCircle(
                style = style,
                hazeState = hazeState,
                glass = glass,
                icon = Icons.Rounded.Home,
                description = "Home",
                tint = if (style == DockStyle.GLASS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                alpha = circleIn,
                scale = circleScale,
                // Inclusive so there is never a frame where both the nav pill and the circles
                // are non-interactive while the dock is settling.
                enabled = p >= 0.5f,
                onClick = { onSelectTab(Tab.HOME); onExpand() },
            )
            DockEdgeCircle(
                style = style,
                hazeState = hazeState,
                glass = glass,
                icon = Icons.Rounded.Search,
                description = "Search",
                tint = if (style == DockStyle.GLASS) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                alpha = circleIn,
                scale = circleScale,
                // Inclusive so there is never a frame where both the nav pill and the circles
                // are non-interactive while the dock is settling.
                enabled = p >= 0.5f,
                onClick = { onSelectTab(Tab.SEARCH); onExpand() },
            )
            DockMiniPill(
                style = style,
                hazeState = hazeState,
                glass = glass,
                track = track,
                playing = playing,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpen = onOpenNowPlaying,
                nextAlpha = nextAlpha,
                nextProgress = (p * 2f).coerceIn(0f, 1f),
            )
        },
    ) { measurables, constraints ->
        val fullWidth = constraints.maxWidth
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity }
            ?: DockMetrics.ExpandedHeight.roundToPx()
        val circlePx = CircleSize.roundToPx()
        val gapPx = EdgeGap.roundToPx()

        // Collapsed row: [Home circle] gap [mini pill] gap [Search circle].
        val miniCollapsedWidth = (fullWidth - 2 * (circlePx + gapPx)).coerceAtLeast(0)
        val miniWidth = if (showsMini) {
            lerpF(fullWidth.toFloat(), miniCollapsedWidth.toFloat(), p).roundToInt().coerceIn(0, fullWidth)
        } else 0
        val miniHeight = if (showsMini) {
            lerpF(MiniHeightExpanded.toPx(), MiniHeightCollapsed.toPx(), p).roundToInt().coerceAtLeast(0)
        } else 0
        val miniX = lerpF(0f, (circlePx + gapPx).toFloat(), p).roundToInt()
        // The dock's own height shrinks toward the bottom row, so placing the mini pill at the
        // top of the shrinking layout is what makes it visibly travel down into the row.
        val miniY = 0

        val navWidth = if (showsMini) miniWidth else fullWidth
        val navPxHeight = lerpF(NavHeight.toPx(), RowHeight.toPx(), p).roundToInt()
        val navX = if (showsMini) miniX else 0
        // The nav pill stays anchored to the bottom while the mini pill slides over it.
        val navY = height - navPxHeight
        val circleY = height - circlePx

        val navPlaceable = measurables[0].measure(Constraints.fixed(navWidth.coerceAtLeast(0), navPxHeight.coerceAtLeast(0)))
        val homePlaceable = measurables[1].measure(Constraints.fixed(circlePx, circlePx))
        val searchPlaceable = measurables[2].measure(Constraints.fixed(circlePx, circlePx))
        val miniPlaceable = measurables[3].measure(Constraints.fixed(miniWidth, miniHeight))

        layout(fullWidth, height) {
            navPlaceable.placeRelative(navX, navY)
            homePlaceable.placeRelative(0, circleY)
            searchPlaceable.placeRelative(fullWidth - circlePx, circleY)
            miniPlaceable.placeRelative(miniX, miniY)
        }
    }
}

/** Four equal-width tab slots with 11sp labels and one sliding spring capsule behind the selection. */
@Composable
private fun DockNavPill(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    selected: Tab?,
    labelAlpha: Float,
    clickable: Boolean,
    onSelectTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val unselected = if (style == DockStyle.GLASS) Color.White.copy(alpha = 0.92f) else colors.onSurfaceVariant
    val capsuleColor = if (style == DockStyle.GLASS) {
        Color.White.copy(alpha = 0.16f)
    } else {
        colors.primary.copy(alpha = 0.16f)
    }
    val glow = if (style == DockStyle.GLASS) GlassTokens.Violet else colors.primary
    val pillShape = RoundedCornerShape(50)
    val items = if (style == DockStyle.GLASS) GlassDockNavItems else SolidDockNavItems
    val reduceMotion = rememberReduceMotion()
    val slideSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.62f, stiffness = 260f)
    val tintSpec: androidx.compose.animation.core.AnimationSpec<Color> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.75f, stiffness = 520f)

    Box(
        modifier
            .dockSurface(
                style = style,
                hazeState = hazeState,
                glass = glass,
                shape = pillShape,
                cornerRadius = 32.dp,
                refraction = true,
            )
            .padding(5.dp),
    ) {
        if (style == DockStyle.SOLID) {
            // Normal theme: original five slots and the original per-slot fading capsule.
            Row(Modifier.fillMaxSize()) {
                items.forEach { item ->
                    val active = item.tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    val tint by animateColorAsState(
                        targetValue = if (active) accent else unselected,
                        animationSpec = tween(180),
                        label = "dockSlotTint",
                    )
                    val capsule by animateFloatAsState(
                        targetValue = if (active) 1f else 0f,
                        animationSpec = tween(180),
                        label = "dockCapsule",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .then(
                                if (clickable) {
                                    Modifier.clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        role = Role.Tab,
                                        onClick = { onSelectTab(item.tab) },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                .graphicsLayer { alpha = capsule }
                                .clip(CircleShape)
                                .background(capsuleColor),
                        )
                        DockSlotContent(item, active, tint, labelAlpha)
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val slotWidth = maxWidth / items.size
                val selectedIndex = items.indexOfFirst { it.tab == selected }
                val slide by animateFloatAsState(
                    targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
                    animationSpec = slideSpec,
                    label = "dockCapsuleSlide",
                )
                val capsuleAlpha by animateFloatAsState(
                    targetValue = if (selectedIndex >= 0) 1f else 0f,
                    animationSpec = slideSpec,
                    label = "dockCapsuleAlpha",
                )
                if (capsuleAlpha > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(slotWidth)
                            .offset(x = slotWidth * slide)
                            .graphicsLayer { alpha = capsuleAlpha }
                            .padding(3.dp)
                            .shadow(
                                elevation = 10.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = glow.copy(alpha = 0.30f),
                                spotColor = glow.copy(alpha = 0.45f),
                            )
                            .clip(CircleShape)
                            .background(capsuleColor)
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.34f), glow.copy(alpha = 0.16f)),
                                ),
                                CircleShape,
                            ),
                    )
                }
                Row(Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        val active = item.tab == selected
                        val interaction = remember { MutableInteractionSource() }
                        val tint by animateColorAsState(
                            targetValue = if (active) accent else unselected,
                            animationSpec = tintSpec,
                            label = "dockSlotTint",
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                // Only the live layer owns a pointer handler. A disabled clickable
                                // still occupies the hit-test path, which can swallow taps from the
                                // layer below while the dock is morphing.
                                .then(
                                    if (clickable) {
                                        Modifier.clickable(
                                            interactionSource = interaction,
                                            indication = null,
                                            role = Role.Tab,
                                            onClick = { onSelectTab(item.tab) },
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            DockSlotContent(item, active, tint, labelAlpha)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockSlotContent(item: DockNavItem, active: Boolean, tint: Color, labelAlpha: Float) {
    val isEdgeItem = item.tab == Tab.HOME || item.tab == Tab.SEARCH
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = if (active) "${item.label} (selected)" else item.label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { alpha = if (isEdgeItem) 1f else labelAlpha },
        )
        Text(
            text = item.label,
            color = tint,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
        )
    }
}

/** Circular Home/Search target used by the collapsed row. */
@Composable
private fun DockEdgeCircle(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    icon: ImageVector,
    description: String,
    tint: Color,
    alpha: Float,
    scale: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // While the circle is faded out (expanded dock) it must not sit on top of the Home/Search
    // slots as a transparent hit layer, so the pointer handler is only attached when visible.
    val interactive = enabled && alpha > 0.01f
    Box(
        Modifier
            .size(CircleSize)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .dockControlCircle(
                style = style,
                hazeState = hazeState,
                glass = glass,
                solidFill = MaterialTheme.colorScheme.surface,
            )
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * Mini-player pill. It is always composed (one of the four layout children) so the layout never
 * changes its child count; with no loaded track it measures to 0x0 and accepts no clicks.
 */
@Composable
private fun DockMiniPill(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    track: Track?,
    playing: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    nextAlpha: Float,
    nextProgress: Float,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val nextWidth = dpLerp(40.dp, 0.dp, nextProgress)

    Box(
        Modifier
            .fillMaxSize()
            .dockSurface(
                style = style,
                hazeState = hazeState,
                glass = glass,
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = track != null,
                role = Role.Button,
                onClick = onOpen,
            ),
    ) {
        if (track == null) return@Box

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                track = track,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp),
            ) {
                Text(
                    text = track.title.ifBlank { track.displayName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (style == DockStyle.GLASS) Color.White else colors.onSurface,
                )
                Text(
                    text = track.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = if (style == DockStyle.GLASS) DockArtist else colors.onSurfaceVariant,
                )
            }
            DockPlayButton(
                style = style,
                hazeState = hazeState,
                glass = glass,
                playing = playing,
                onToggle = onTogglePlay,
            )
            Box(
                Modifier
                    .width(nextWidth)
                    // Match the row height so the skip button keeps a tall, forgiving hit area
                    // while its circle stays 40dp and its width collapses with the morph.
                    .fillMaxHeight()
                    .clipToBounds()
                    .graphicsLayer { alpha = nextAlpha },
                contentAlignment = Alignment.Center,
            ) {
                if (nextAlpha > 0.01f) {
                    DockNextButton(
                        style = style,
                        hazeState = hazeState,
                        glass = glass,
                        onClick = onNext,
                    )
                }
            }
        }
    }
}

/** Play/pause: small glass circle in GLASS, accent-filled circle in SOLID. */
@Composable
private fun DockPlayButton(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    playing: Boolean,
    onToggle: () -> Unit,
) {
    // The visual circle stays 40dp, but the hit area fills the mini pill's height. Taps that
    // land just above/below the icon then toggle playback instead of falling through to the
    // pill's "open now playing" action.
    Box(
        Modifier
            .width(40.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .dockControlCircle(
                    style = style,
                    hazeState = hazeState,
                    glass = glass,
                    solidFill = MaterialTheme.colorScheme.primary,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = if (style == DockStyle.GLASS) Color.White else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Next: visible only in the expanded dock (alpha 1 -> 0, width 40dp -> 0dp as p grows). */
@Composable
private fun DockNextButton(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    onClick: () -> Unit,
) {
    // Same treatment as the play button: keep the 40dp circle but use the pill's full height
    // as the touch target so the skip action is easy to hit.
    Box(
        Modifier
            .width(40.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .dockControlCircle(
                    style = style,
                    hazeState = hazeState,
                    glass = glass,
                    solidFill = MaterialTheme.colorScheme.primary,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Next",
                tint = if (style == DockStyle.GLASS) Color.White else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Dock surface:
 * - GLASS: liquid glass (Haze backdrop blur + AGSL lens refraction on 33+, tint + hairline below).
 * - SOLID: opaque surface from the Normal palette, same silhouette and shadow, no blur.
 */
@Composable
private fun Modifier.dockSurface(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    shape: Shape,
    solidFill: Color? = null,
    cornerRadius: Dp = 32.dp,
    refraction: Boolean = true,
): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    if (style == DockStyle.SOLID) {
        val fill = solidFill ?: surface
        return this
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(fill)
    }

    val optics = LocalGlassOptics.current
    return this
        .shadow(
            elevation = 14.dp * glass.shadowScale,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.40f),
            spotColor = Color.Black.copy(alpha = 0.60f),
        )
        .clip(shape)
        .glass(
            hazeState = hazeState,
            shape = shape,
            level = GlassLevel.STRONG,
            refraction = refraction && glass.refractionEnabled,
            style = glass,
            fallbackSurface = GlassTokens.Surface,
        )
}

/** Small circular control: white ~15% glass in GLASS, opaque palette fill in SOLID. */
@Composable
private fun Modifier.dockControlCircle(
    style: DockStyle,
    hazeState: HazeState?,
    glass: DockGlassStyle,
    solidFill: Color,
): Modifier {
    val whiteAlpha = (0.12f + 0.06f * glass.tintAlpha).coerceIn(0f, 0.18f)
    if (style == DockStyle.SOLID) {
        return this
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.30f),
                spotColor = Color.Black.copy(alpha = 0.40f),
            )
            .clip(CircleShape)
            .background(solidFill)
    }

    val optics = LocalGlassOptics.current
    return this
        .shadow(
            elevation = 6.dp * glass.shadowScale,
            shape = CircleShape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.30f),
            spotColor = Color.Black.copy(alpha = 0.45f),
        )
        .clip(CircleShape)
        .glass(
            hazeState = hazeState,
            shape = CircleShape,
            level = GlassLevel.LIGHT,
            refraction = false,
            style = glass,
            fallbackSurface = GlassTokens.Surface,
        )
}

// ---------------------------------------------------------------------------------------------
// Glassmorphic (Mornye-style) dock. Normal theme keeps the shared legacy layout above untouched.
// ---------------------------------------------------------------------------------------------

private val DockPink = Color(0xFFFF4D79)
private val DockMuted = Color(0xFFC9C9D1)
private val DockArtist = Color(0xFFB9B9C4)

private val GlassDockItems = listOf(
    DockNavItem(Tab.HOME, "Home", Icons.Rounded.Home),
    DockNavItem(Tab.LIBRARY, "Your Library", Icons.Rounded.LibraryMusic),
)

private fun Tab.dockIcon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Rounded.Home
    Tab.LIBRARY -> Icons.Rounded.LibraryMusic
    Tab.SEARCH -> Icons.Rounded.Search
    Tab.EQUALIZER -> Icons.Rounded.Equalizer
    Tab.SETTINGS -> Icons.Rounded.Settings
}

@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 480f),
        label = "dockPress",
    )
    return scale
}

/** Floating liquid-glass dock: mini-player pill on top, Home/Library pill + Search/Equalizer circles below. */
@Composable
private fun GlassDock(
    progress: () -> Float,
    hasTrack: Boolean,
    selected: Tab?,
    track: Track?,
    playing: Boolean,
    onSelectTab: (Tab) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier,
    hazeState: HazeState?,
    onExpand: () -> Unit,
    glass: DockGlassStyle,
) {
    val p = progress().coerceIn(0f, 1f)
    val dockHeight = DockMetrics.height(hasTrack, p)
    // The dock look is fixed to the reference: smoky 40-55% tint and a 20-30dp blur.
    val surfaceGlass = glass.copy(
        tintAlpha = glass.tintAlpha.coerceIn(0.40f, 0.55f),
        blurDp = if (glass.blurEnabled) glass.blurDp.coerceIn(20f, 30f) else glass.blurDp,
    )
    val navAlpha = (1f - p / 0.55f).coerceIn(0f, 1f)
    val navScale = 1f - 0.08f * (1f - navAlpha)
    val circleIn = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val activeTab = selected ?: Tab.HOME
    val showsMini = hasTrack && track != null

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(dockHeight)
            .padding(horizontal = DockMetrics.GlassSideMargin),
        content = {
            // Places/draws in this order: nav pill, search, equalizer, active circle, then mini
            // pill last so it slides over the fading circles.
            GlassNavPill(
                glass = surfaceGlass,
                hazeState = hazeState,
                selected = selected,
                enabled = p < 0.5f,
                onSelectTab = onSelectTab,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = navAlpha
                        scaleX = navScale
                        scaleY = navScale
                    },
            )
            GlassCircleButton(
                icon = Icons.Rounded.Search,
                description = "Search",
                tint = Color.White,
                alpha = 1f,
                enabled = true,
                onClick = { onSelectTab(Tab.SEARCH) },
                glass = surfaceGlass,
                hazeState = hazeState,
            )
            GlassCircleButton(
                icon = Icons.Rounded.Equalizer,
                description = "Equalizer",
                tint = Color.White,
                alpha = navAlpha,
                enabled = p < 0.5f,
                onClick = { onSelectTab(Tab.EQUALIZER) },
                glass = surfaceGlass,
                hazeState = hazeState,
            )
            GlassCircleButton(
                icon = activeTab.dockIcon(),
                description = "Expand dock",
                tint = DockPink,
                alpha = circleIn,
                enabled = p >= 0.5f,
                onClick = onExpand,
                glass = surfaceGlass,
                hazeState = hazeState,
            )
            DockMiniPill(
                style = DockStyle.GLASS,
                hazeState = hazeState,
                glass = surfaceGlass,
                track = track,
                playing = playing,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpen = onOpenNowPlaying,
                nextAlpha = (1f - p * 1.6f).coerceIn(0f, 1f),
                nextProgress = (p * 2f).coerceIn(0f, 1f),
            )
        },
    ) { measurables, constraints ->
        val fullWidth = constraints.maxWidth
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity }
            ?: DockMetrics.ExpandedHeight.roundToPx()
        val circlePx = CircleSize.roundToPx()
        val gapPx = EdgeGap.roundToPx()
        val miniExpanded = MiniHeightExpanded.toPx()
        val miniCollapsed = MiniHeightCollapsed.toPx()

        val miniWidth = if (showsMini) {
            lerpF(fullWidth.toFloat(), (fullWidth - 2 * (circlePx + gapPx)).toFloat(), p).roundToInt().coerceIn(0, fullWidth)
        } else 0
        val miniHeight = if (showsMini) {
            lerpF(miniExpanded, miniCollapsed, p).roundToInt().coerceAtLeast(0)
        } else 0
        val miniX = if (showsMini) lerpF(0f, (circlePx + gapPx).toFloat(), p).roundToInt() else 0
        val miniY = 0

        val navWidth = lerpF((fullWidth - 2 * (circlePx + gapPx)).toFloat(), circlePx.toFloat(), p).roundToInt().coerceAtLeast(0)
        val navHeight = lerpF(NavHeight.toPx(), RowHeight.toPx(), p).roundToInt().coerceAtLeast(0)
        val navY = height - navHeight
        val circleY = navY + (navHeight - circlePx) / 2
        val searchX = fullWidth - circlePx
        val eqX = fullWidth - 2 * circlePx - gapPx
        val activeX = 0

        val navPlaceable = measurables[0].measure(Constraints.fixed(navWidth, navHeight))
        val searchPlaceable = measurables[1].measure(Constraints.fixed(circlePx, circlePx))
        val eqPlaceable = measurables[2].measure(Constraints.fixed(circlePx, circlePx))
        val activePlaceable = measurables[3].measure(Constraints.fixed(circlePx, circlePx))
        val miniPlaceable = measurables[4].measure(Constraints.fixed(miniWidth, miniHeight))

        layout(fullWidth, height) {
            navPlaceable.placeRelative(0, navY)
            searchPlaceable.placeRelative(searchX, circleY)
            eqPlaceable.placeRelative(eqX, circleY)
            activePlaceable.placeRelative(activeX, circleY)
            miniPlaceable.placeRelative(miniX, miniY)
        }
    }
}

/** Two glass tab slots and one sliding lens capsule painted in the dock accent. */
@Composable
private fun GlassNavPill(
    glass: DockGlassStyle,
    hazeState: HazeState?,
    selected: Tab?,
    enabled: Boolean,
    onSelectTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(50)
    val selectedIndex = GlassDockItems.indexOfFirst { it.tab == selected }
    val reduceMotion = rememberReduceMotion()
    val slideSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 300f)

    Box(
        modifier
            .dockSurface(
                style = DockStyle.GLASS,
                hazeState = hazeState,
                glass = glass,
                shape = pillShape,
                cornerRadius = 32.dp,
                refraction = true,
            )
            .padding(5.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val slot = maxWidth / GlassDockItems.size
            val slide by animateFloatAsState(
                targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
                animationSpec = slideSpec,
                label = "glassLensSlide",
            )
            val targetIndex = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f
            // 5-8% stretch in the direction of travel, decaying as the lens lands.
            val stretch = 1f + 0.07f * abs(slide - targetIndex).coerceIn(0f, 1f)
            if (selectedIndex >= 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(slot)
                        .offset(x = slot * slide)
                        .graphicsLayer { scaleX = stretch }
                        .padding(3.dp)
                        .shadow(
                            elevation = 10.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = DockPink.copy(alpha = 0.22f),
                            spotColor = DockPink.copy(alpha = 0.35f),
                        )
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.13f))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.05f)),
                            ),
                            CircleShape,
                        ),
                )
            }
            Row(Modifier.fillMaxSize()) {
                GlassDockItems.forEach { item ->
                    val active = item.tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    val press = pressScale(interaction)
                    val tint by animateColorAsState(
                        targetValue = if (active) DockPink else DockMuted,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 520f),
                        label = "glassSlotTint",
                    )
                    val contentScale by animateFloatAsState(
                        targetValue = if (active) 1f else 0.90f,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        label = "glassSlotScale",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .then(
                                if (enabled) {
                                    Modifier.clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        role = Role.Tab,
                                        onClick = { onSelectTab(item.tab) },
                                    )
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            modifier = Modifier.graphicsLayer {
                                scaleX = press * contentScale
                                scaleY = press * contentScale
                            },
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = if (active) "${item.label} (selected)" else item.label,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = item.label,
                                color = tint,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Circular glass button with press spring and a bubble-like inner highlight. */
@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    alpha: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    glass: DockGlassStyle,
    hazeState: HazeState?,
) {
    val interaction = remember { MutableInteractionSource() }
    val press = pressScale(interaction)
    val interactive = enabled && alpha > 0.01f
    Box(
        Modifier
            .size(CircleSize)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = press
                scaleY = press
            }
            .dockControlCircle(
                style = DockStyle.GLASS,
                hazeState = hazeState,
                glass = glass,
                solidFill = MaterialTheme.colorScheme.surface,
            )
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val glow = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.32f, size.height * 0.24f),
                        radius = size.minDimension * 0.78f,
                    )
                    onDrawBehind { drawRect(glow) }
                },
        )
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

