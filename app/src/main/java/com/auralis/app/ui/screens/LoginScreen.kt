package com.auralis.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.auralis.app.R
import com.auralis.app.domain.model.ThemeStyle
import com.auralis.app.ui.AuthUiState
import com.auralis.app.ui.theme.LocalThemeStyle

/**
 * AURALIS sign-in gate.
 *
 * Deliberately self-contained (no dependency on the dock/glass composition locals, because this
 * screen renders *before* the app shell exists): it paints its own premium AMOLED surface and only
 * borrows the active theme's accent colour, so it looks intentional under both themes.
 *
 * Sign-in itself is handled entirely by Clerk (see [com.auralis.app.data.auth.ClerkAuthRepository]);
 * this screen is just the presentation.
 */
@Composable
fun LoginScreen(
    auth: AuthUiState,
    onGoogleSignIn: () -> Unit,
    onRequestCode: (String) -> Unit,
    onVerifyCode: (String) -> Unit,
    onEditEmail: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC
    val accent = MaterialTheme.colorScheme.primary
    var email by rememberSaveable { mutableStateOf(auth.email) }
    var code by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val codeFocus = remember { FocusRequester() }

    // When the code step appears, move focus straight to the code field.
    LaunchedEffect(auth.awaitingCode) {
        if (auth.awaitingCode) runCatching { codeFocus.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF020306),
                    0.45f to Color(0xFF090B12),
                    1f to Color(0xFF020306),
                ),
            ),
    ) {
        // Soft accent glow behind the wordmark — pure decoration, no interaction.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(24.dp))
            Image(
                painter = painterResource(R.drawable.ic_logo_header),
                contentDescription = "AURALIS",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Music, reimagined",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.62f),
            )
            Spacer(Modifier.height(40.dp))

            if (auth.initializing) {
                CircularProgressIndicator(color = accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Preparing your session…",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(40.dp))
                return@Column
            }

            AuthCard(glass = glass) {                Text(
                    "Sign in to continue",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sync your profile and keep your listening history across devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(24.dp))

                // Google (via Clerk OAuth).
                Button(
                    onClick = { focus.clearFocus(); onGoogleSignIn() },
                    enabled = !auth.busy,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1F1F1F),
                        disabledContainerColor = Color.White.copy(alpha = 0.4f),
                        disabledContentColor = Color(0xFF1F1F1F),
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    GoogleGlyph(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                    Text(
                        "  or  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                }
                Spacer(Modifier.height(20.dp))

                if (!auth.awaitingCode) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; if (auth.error != null) onClearError() },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !auth.busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focus.clearFocus()
                            onRequestCode(email)
                        }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { focus.clearFocus(); onRequestCode(email) },
                        enabled = !auth.busy && email.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (auth.busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Email me a sign-in code", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text(
                        "Enter the code we sent to",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Text(auth.email, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6); if (auth.error != null) onClearError() },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        enabled = !auth.busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focus.clearFocus()
                            onVerifyCode(code)
                        }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { focus.clearFocus(); onVerifyCode(code) },
                        enabled = !auth.busy && code.length >= 4,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (auth.busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Verify and sign in", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { code = ""; onEditEmail() }, enabled = !auth.busy) {
                        Text("Use a different email", color = Color.White.copy(alpha = 0.7f))
                    }
                }

                if (auth.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        auth.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Secured by Clerk · Your password never touches AURALIS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuthCard(glass: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (glass) Color.White.copy(alpha = 0.06f) else Color(0xFF101319))
            .border(1.dp, Color.White.copy(alpha = if (glass) 0.14f else 0.07f), shape)
            .padding(24.dp),
    ) { Column(Modifier.fillMaxWidth()) { content() } }
}

/**
 * The Google "G" painted from four arcs. Material icons ship no brand marks, and a vector asset
 * would be another file to keep in sync, so this stays a couple of dozen lines of Canvas.
 */
@Composable
private fun GoogleGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.24f
        val inset = stroke / 2f
        val topLeft = Offset(inset, inset)
        val arcSize = Size(s - inset * 2f, s - inset * 2f)
        val arc = Stroke(width = stroke)
        drawArc(Color(0xFFEA4335), 216f, 108f, false, topLeft, arcSize, style = arc)
        drawArc(Color(0xFFFBBC05), 144f, 72f, false, topLeft, arcSize, style = arc)
        drawArc(Color(0xFF34A853), 72f, 72f, false, topLeft, arcSize, style = arc)
        drawArc(Color(0xFF4285F4), 324f, 108f, false, topLeft, arcSize, style = arc)
        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(s / 2f, s / 2f - stroke / 2f),
            size = Size(s / 2f - inset + stroke / 2f, stroke),
        )
    }
}
