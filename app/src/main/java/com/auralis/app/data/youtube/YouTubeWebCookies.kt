package com.auralis.app.data.youtube

import android.webkit.CookieManager

/**
 * Cookie helpers for the browser jar.
 *
 * Session cookies are expired one name at a time — never with `removeAllCookies()`/`deleteAllData()`
 * — so an explicit Disconnect or "Use a different account" cannot wipe unrelated WebView state.
 * The saved session is also restored by name before the dialog loads music.youtube.com.
 */

/**
 * Every origin that can own a Google/YouTube session cookie, with the domain Google actually
 * stores it under. `music.youtube.com` alone is not enough: the jar holds `.youtube.com` and
 * `.google.com` cookies, so a host-only deletion would leave the user signed in.
 */
private val YOUTUBE_COOKIE_CLEAR_TARGETS = listOf(
    "https://music.youtube.com/" to ".youtube.com",
    "https://www.youtube.com/" to ".youtube.com",
    "https://accounts.google.com/" to ".google.com",
    "https://www.google.com/" to ".google.com",
)

/**
 * Expires the Google session cookies for the YouTube and Google origins, by name, in
 * [CookieManager]. Only names from [isYouTubeSessionCookieName] are touched; preferences,
 * consent and visitor cookies stay.
 *
 * Only the explicit user actions (Disconnect / Use a different account) call this. The silent
 * refresh and the sign-in dialog rely on these cookies still existing, so they must never clear
 * them.
 */
fun clearYouTubeWebSessionCookies() {
    val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
    for ((url, domain) in YOUTUBE_COOKIE_CLEAR_TARGETS) {
        val header = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
        val names = header.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() && isYouTubeSessionCookieName(it) }
            .distinct()
        for (name in names) {
            runCatching {
                if (name.startsWith("__Host-")) {
                    // `__Host-` cookies must never carry a Domain attribute.
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                } else {
                    // Remove the shared-domain cookie and any host-only duplicate.
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$domain; Secure")
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                }
            }
        }
    }
    runCatching { manager.flush() }
}

/**
 * Puts the saved session back into the WebView cookie jar before loading music.youtube.com.
 *
 * This is the BitChord `installGoogleCookies` idea: a WebView that never saw the sign-in still
 * receives the saved cookies, so the page opens already signed in. Values are never logged.
 * Returns the number of cookie names restored.
 */
fun installYouTubeSavedCookies(cookieHeader: String): Int {
    val cookies = parseSavedCookies(cookieHeader)
    if (cookies.isEmpty()) return 0
    val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return 0
    for (cookie in cookies) {
        for (url in YOUTUBE_COOKIE_INSTALL_URLS) {
            runCatching { manager.setCookie(url, savedCookieSetValue(cookie)) }
        }
    }
    runCatching { manager.flush() }
    return cookies.size
}