package com.auralis.app.data.youtube

/**
 * Browser identity shared by the visible Google sign-in dialog and the invisible silent refresh.
 * Google can tie a session to the browser that signed in, so both must send exactly this UA.
 */
internal const val YOUTUBE_LOGIN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/** The two hosts that own the session; music.youtube.com is listed first and wins a name clash. */
internal val YOUTUBE_COOKIE_URLS = listOf("https://music.youtube.com/", "https://www.youtube.com/")

/**
 * Reads the documented `ytcfg` keys. Runs only inside the WebView (after Confirm, or inside the
 * silent refresh); the cookie itself is always read from [android.webkit.CookieManager] in Kotlin.
 */
internal const val YOUTUBE_YT_CFG_SCRIPT =
    "(function(){try{var g=function(k){try{return window.ytcfg&&window.ytcfg.get?" +
        "window.ytcfg.get(k):null;}catch(e){return null;}};" +
        "return JSON.stringify({loggedIn:g('LOGGED_IN')===true," +
        "delegated:g('DELEGATED_SESSION_ID')||'',dataSync:g('DATASYNC_ID')||''," +
        "sessionIndex:g('SESSION_INDEX'),visitor:g('VISITOR_DATA')||''," +
        "clientVersion:g('INNERTUBE_CLIENT_VERSION')||''});}catch(e){return '';}})()"

/** The `ytcfg` values the app needs; never logged as values. */
data class WebYtcfg(
    val loggedIn: Boolean,
    val delegatedSessionId: String,
    val dataSyncId: String,
    val sessionIndex: String,
    val visitorData: String,
    val clientVersion: String,
)

/**
 * Joins cookie headers from several hosts by name. The first host wins, which is why
 * music.youtube.com is listed before www.youtube.com: it owns the session the app uses.
 */
internal fun mergeWebCookies(headers: List<String>): String {
    val byName = LinkedHashMap<String, String>()
    for (header in headers) {
        for (entry in header.split(';')) {
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) continue
            byName.putIfAbsent(name, value)
        }
    }
    return byName.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}