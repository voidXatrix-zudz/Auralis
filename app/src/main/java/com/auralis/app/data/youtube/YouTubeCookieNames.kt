package com.auralis.app.data.youtube

/**
 * Cookie names that identify a Google/YouTube *session*. Kept in a pure file so the "only clear by
 * name" rule can be unit-tested on the JVM without the Android WebView classes.
 *
 * Everything else (PREF, CONSENT, SOCS, VISITOR_INFO1_LIVE, ...) is harmless browser state and is
 * left alone.
 */
internal val YOUTUBE_SESSION_COOKIE_NAMES = setOf(
    "SID",
    "HSID",
    "SSID",
    "APISID",
    "SAPISID",
    "LSID",
    "SIDCC",
    "LOGIN_INFO",
    "__Secure-1PSID",
    "__Secure-3PSID",
    "__Secure-1PAPISID",
    "__Secure-3PAPISID",
    "__Secure-1PSIDTS",
    "__Secure-3PSIDTS",
    "__Secure-1PSIDCC",
    "__Secure-3PSIDCC",
    "__Host-1PLSID",
    "__Host-3PLSID",
    "__Host-GAPS",
)

/** True for a name the app may expire by name (Disconnect / "Use a different account"). */
fun isYouTubeSessionCookieName(name: String): Boolean =
    name in YOUTUBE_SESSION_COOKIE_NAMES || name in SAPISID_COOKIE_NAMES