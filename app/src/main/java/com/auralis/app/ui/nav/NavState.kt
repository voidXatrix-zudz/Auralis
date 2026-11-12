package com.auralis.app.ui.nav

enum class Tab { HOME, SEARCH, LIBRARY, EQUALIZER, SETTINGS }

/** Screens pushed on top of the tab content. More are added as their stages land. */
sealed interface Destination {
    data object Settings : Destination
    data object SettingsPlayback : Destination
    data object SettingsMetadataLyrics : Destination
    data object SettingsDownloads : Destination
    data object SettingsYouTubeMusic : Destination
    data object SettingsAppearance : Destination
    data object SettingsPerformance : Destination
    data object SettingsStorage : Destination
    data object SettingsAbout : Destination
    data object About : Destination
    data object Equalizer : Destination
    data object Wrapped : Destination
    data object Backup : Destination
    data object History : Destination
    data class MetadataEditor(val trackId: String) : Destination
    data class AudioAnalysis(val trackId: String) : Destination
    data class PlaylistDetail(val playlistId: Long) : Destination
    data class SpotifyImport(val importId: Long) : Destination
    data object Logs : Destination
}

/** Tab plus a back stack, same model as the web reference. Pure so it can be unit tested. */
data class NavState(
    val tab: Tab = Tab.HOME,
    val stack: List<Destination> = emptyList(),
) {
    val top: Destination? get() = stack.lastOrNull()

    /** Selecting a tab clears the stack. */
    fun selectTab(tab: Tab): NavState = NavState(tab = tab, stack = emptyList())

    /** Pushing the destination already on top is ignored, so double taps don't stack duplicates. */
    fun push(destination: Destination): NavState =
        if (top == destination) this else copy(stack = stack + destination)

    fun pop(): NavState = if (stack.isEmpty()) this else copy(stack = stack.dropLast(1))
}
