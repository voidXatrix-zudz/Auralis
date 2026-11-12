package com.auralis.app.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.auralis.app.domain.model.Track

/** Download state + actions for the current composition, so every row/menu knows what is offline. */
data class DownloadActions(
    val downloadedIds: Set<String> = emptySet(),
    val onDownload: (Track) -> Unit = {},
    val onDeleteDownload: (Track) -> Unit = {},
)

val LocalDownloadActions = compositionLocalOf { DownloadActions() }
