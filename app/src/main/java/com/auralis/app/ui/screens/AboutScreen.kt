package com.auralis.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.app.BuildConfig
import com.auralis.app.R

private const val PROJECT_GITHUB = "https://github.com/voidXatrix-zudz/Auralis"
private const val GPL_V3_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val INSTAGRAM_ZUDZ = "https://www.instagram.com/zudz.irl?jplx=dXRnZDgwbWl4NXZ1"
private const val INSTAGRAM_VOIDZUDZ = "https://www.instagram.com/voidzudz.exe?hvrq=MXRxOXNkdHVva2FnZw=="
private const val SUPPORT_UPI_ID = "voidxatrix@ptaxis"

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "about_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF050B04)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "AURALIS app logo",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("AURALIS", style = MaterialTheme.typography.headlineSmall)
                    Text("A local-first music player", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }

        item(key = "about_features") {
            AboutSection(title = "What’s inside") {
                AboutBullet(Icons.Rounded.VideoLibrary, "Local library, playlists and downloads")
                AboutBullet(Icons.Rounded.Tune, "Glass themes, dock controls and audio tools")
                AboutBullet(Icons.Rounded.Code, "YouTube Music search, streaming and account history sync")
                AboutBullet(Icons.Rounded.Info, "Multi-provider lyrics with line and word timing")
            }
        }

        item(key = "about_architecture") {
            AboutSection(title = "Built with") {
                Text(
                    "Kotlin · Jetpack Compose · Media3 · Room · Coroutines and Flow",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        item(key = "about_privacy") {
            AboutSection(title = "Privacy") {
                Text(
                    "Scanned audio files stay on your device; AURALIS does not upload them. Online search, streaming, lyrics and artwork features send the required query or track details to their selected services. YouTube account sign-in is optional.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        item(key = "about_socials") {
            AboutSection(title = "Project and socials") {
                AboutLinkRow(Icons.Rounded.Code, "Source code on GitHub", "github.com/voidXatrix-zudz/Auralis") {
                    openExternal(context, PROJECT_GITHUB)
                }
                AboutLinkRow(Icons.Rounded.Person, "Instagram · @zudz.irl", "Open Instagram profile") {
                    openExternal(context, INSTAGRAM_ZUDZ)
                }
                AboutLinkRow(Icons.Rounded.Person, "Instagram · @voidzudz.exe", "Open Instagram profile") {
                    openExternal(context, INSTAGRAM_VOIDZUDZ)
                }
            }
        }

        item(key = "about_support") {
            AboutSection(title = "Support the project") {
                AboutLinkRow(Icons.Rounded.FavoriteBorder, "Support AURALIS with UPI", SUPPORT_UPI_ID) {
                    openUpiChooser(context)
                }
            }
        }

        item(key = "about_license") {
            AboutSection(title = "License") {
                AboutLinkRow(Icons.Rounded.Security, "GNU General Public License v3.0", "View the license") {
                    openExternal(context, GPL_V3_URL)
                }
                Text(
                    "Some bundled components retain their own licenses. The repository includes third-party notices; the glass backdrop keeps its Apache License 2.0 attribution, and Manrope and Sora use the SIL Open Font License 1.1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        item(key = "about_footer") {
            Text(
                "Made with love by Zudz",
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.44f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AboutBullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutLinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open", tint = colors.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
    }
}

private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        if (it is ActivityNotFoundException) return
    }
}

private fun openUpiChooser(context: android.content.Context) {
    val paymentUri = Uri.Builder()
        .scheme("upi")
        .authority("pay")
        .appendQueryParameter("pa", SUPPORT_UPI_ID)
        .appendQueryParameter("pn", "AURALIS Project")
        .appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", "Support AURALIS")
        .build()
    val intent = Intent(Intent.ACTION_VIEW, paymentUri)
    try {
        context.startActivity(Intent.createChooser(intent, "Choose a UPI app"))
    } catch (_: ActivityNotFoundException) {
        // Android displays its standard no-apps message if no UPI app can handle this intent.
    }
}
