package com.auralis.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.auralis.app.R

/**
 * The one AURALIS wordmark.
 *
 * Every header and the login screen draw the logo through this composable so the artwork, the
 * `Fit` scaling and the accessibility label stay identical. Callers pass only the size / position
 * modifier that their own top bar needs; they never re-declare the drawable.
 */
@Composable
fun AuralisLogo(
    modifier: Modifier = Modifier,
    contentDescription: String = "AURALIS",
) {
    Image(
        painter = painterResource(R.drawable.ic_logo_header),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}