package com.auralis.app.data.youtube

/** Which page the sign-in dialog opens: an existing session first, or the Google form. */
enum class YouTubeLoginMode { CONTINUE, SIGN_IN }

/**
 * Task 2 mode selection: when the app already has a stored session (even an expired one) the dialog
 * loads music.youtube.com so the user can confirm in one tap. With no stored session it still uses
 * the browser jar when that jar carries signing cookies; otherwise it opens the account form.
 */
fun loginMode(saved: YouTubeAuth?, jarHasSigningCookie: Boolean): YouTubeLoginMode =
    if (saved?.hasSession == true || jarHasSigningCookie) YouTubeLoginMode.CONTINUE
    else YouTubeLoginMode.SIGN_IN

/** Button text for the Confirm action. */
fun continueLabel(mode: YouTubeLoginMode, name: String): String = when {
    mode != YouTubeLoginMode.CONTINUE -> "Confirm"
    name.isBlank() -> "Continue"
    else -> "Continue as $name"
}

/** One `name=value` pair from a saved cookie header. Values are never logged. */
data class SavedCookie(val name: String, val value: String)

/**
 * Parses the saved session cookie string so it can be put back into the WebView jar before loading
 * music.youtube.com. Empty names/values and duplicates are dropped; the first value wins, matching
 * [mergeWebCookies] (music.youtube.com is stored first).
 */
fun parseSavedCookies(cookieHeader: String): List<SavedCookie> {
    val byName = LinkedHashMap<String, String>()
    for (entry in cookieHeader.split(';')) {
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty()) continue
        byName.putIfAbsent(name, value)
    }
    return byName.map { (name, value) -> SavedCookie(name, value) }
}

/**
 * The `Set-Cookie` value used to restore one saved cookie. `__Host-` cookies must not carry a
 * Domain attribute; everything else is scoped to the URL it is set on by [installSavedCookies].
 */
fun savedCookieSetValue(cookie: SavedCookie): String =
    "${cookie.name}=${cookie.value}; Path=/; Secure"

/** Hosts that own the restored session. Google's forms need the same cookies to skip the chooser. */
val YOUTUBE_COOKIE_INSTALL_URLS = listOf(
    "https://music.youtube.com/",
    "https://www.youtube.com/",
    "https://accounts.google.com/",
    "https://www.google.com/",
)