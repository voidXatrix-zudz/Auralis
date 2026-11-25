package com.auralis.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.BuildConfig
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.components.AuralisLogo
import com.auralis.app.ui.glass.rememberReduceMotion
import com.auralis.app.ui.theme.AuralisMotion
import com.auralis.app.ui.theme.GlassTokens
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * One-time first-run welcome screen.
 *
 * Shown once per install, immediately before the sign-in gate, so a brand-new user is greeted
 * before being asked to authenticate. It is deliberately self-contained (it renders before the app
 * shell exists, so it does not depend on the dock/glass composition locals) and only borrows the
 * active theme's accent, exactly like [LoginScreen]. Tapping "Get started" records the visit (see
 * AppSettings.welcomeSeen) and the login screen then takes over.
 */
private val WelcomeBackdrop = Brush.verticalGradient(
    0f to Color(0xFF020306),
    0.45f to Color(0xFF090B12),
    1f to Color(0xFF020306),
)

/** Glass paints a flat black field; Normal keeps the original AMOLED backdrop (matches LoginScreen). */
private fun welcomeBackdrop(glass: Boolean): Brush =
    if (glass) Brush.linearGradient(listOf(GlassTokens.Canvas, GlassTokens.Canvas)) else WelcomeBackdrop

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val accent = if (glass) GlassTokens.Cream else MaterialTheme.colorScheme.primary
    // Glass uses cream text/steps; Normal keeps the original whites and accent (matches LoginScreen).
    val onBg = if (glass) GlassTokens.Cream else Color.White
    val onBgMuted = if (glass) GlassTokens.Cream65 else Color.White.copy(alpha = 0.62f)
    val onBgSubtle = if (glass) GlassTokens.Cream40 else Color.White.copy(alpha = 0.38f)

    // Entrance is a calm fade + rise; it snaps when the system asks for reduced motion.
    val reduceMotion = rememberReduceMotion()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val hero by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 900,
            easing = AuralisMotion.Creamy,
        ),
        label = "welcomeHero",
    )
    val cta by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 760,
            delayMillis = if (reduceMotion) 0 else 380,
            easing = AuralisMotion.Creamy,
        ),
        label = "welcomeCta",
    )
    // Slow ambient breath on the accent glow, so the idle screen still feels alive. Off with
    // reduced motion; 6s each way so it is never distracting.
    val glowPulse = if (reduceMotion || glass) {
        null
    } else {
        rememberInfiniteTransition(label = "welcomeGlow").animateFloat(
            initialValue = 0.68f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = AuralisMotion.Smooth),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "welcomeGlowPulse",
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(welcomeBackdrop(glass)),
    ) {
        // Soft accent glow behind the wordmark — pure decoration, no interaction.
        if (!glass) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glowPulse?.value ?: 1f }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = hero
                    translationY = (1f - hero) * 48f
                },
            ) {
                AuralisLogo(modifier = Modifier.height(72.dp))
                Spacer(Modifier.height(28.dp))
                Text(
                    "Music, reimagined.",
                    style = MaterialTheme.typography.displaySmall,
                    color = onBg,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Lossless playback. Synced lyrics.\nA liquid-glass interface.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = onBgMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(40.dp))
                WelcomeFeature("Lossless FLAC, 16 and 24-bit", accent, onBgSubtle)
                Spacer(Modifier.height(14.dp))
                WelcomeFeature("Lyrics that follow every line", accent, onBgSubtle)
                Spacer(Modifier.height(14.dp))
                WelcomeFeature("Private by design, offline first", accent, onBgSubtle)
            }

            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = cta
                        translationY = (1f - cta) * 32f
                    },
            ) {
                Button(
                    onClick = onGetStarted,
                    shape = RoundedCornerShape(16.dp),
                    colors = if (glass) {
                        ButtonDefaults.buttonColors(
                            containerColor = GlassTokens.Cream,
                            contentColor = GlassTokens.Indigo,
                        )
                    } else {
                        ButtonDefaults.buttonColors(containerColor = accent)
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text("Get started", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "AURALIS · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = onBgSubtle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One minimal feature line: a small accent dot followed by a short claim. */
@Composable
private fun WelcomeFeature(text: String, accent: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}
