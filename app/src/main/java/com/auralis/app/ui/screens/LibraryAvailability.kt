package com.auralis.app.ui.screens

/**
 * True only when the Library genuinely has nothing to show.
 *
 * A connected and signed-in YouTube Music account always counts as content: its tiles and playlists
 * must stay on screen even when local audio access was denied and the local library is empty. A
 * failed playlist fetch is also not "empty" — the Library shows the retry row instead.
 *
 * Pure so both the default and the Glass Library share one rule and it can be unit-tested.
 */
fun isLibraryEmpty(
    youtubeEnabled: Boolean,
    youtubeSignedIn: Boolean,
    localTracks: Int,
    playlists: Int,
    youtubePlaylists: Int,
    downloads: Int,
    youtubeLiked: Int,
    spotifyImports: Int,
    youtubeError: String?,
): Boolean {
    if (youtubeEnabled && youtubeSignedIn) return false
    if (youtubeError != null) return false
    return localTracks == 0 && playlists == 0 && youtubePlaylists == 0 &&
        downloads == 0 && youtubeLiked == 0 && spotifyImports == 0
}