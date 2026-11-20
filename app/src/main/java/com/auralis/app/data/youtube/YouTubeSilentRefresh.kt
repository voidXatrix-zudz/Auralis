package com.auralis.app.data.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.auralis.app.logging.YouTubeSessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Session recovery without a dialog: loads music.youtube.com in an off-screen WebView using the same
 * mobile-Chrome UA as the sign-in dialog, reads the cookies the browser already has (Google login,
 * rotated `__Secure-1PSIDTS`, ...) and probes `ytcfg`.
 *
 * Never touches `CookieManager` state, never clears anything, and returns null when the page does
 * not report a logged-in session. The whole attempt has a 20 s budget.
 */
class YouTubeSilentRefresh(private val context: Context) {

    suspend fun refresh(): YouTubeAuth? = withContext(Dispatchers.Main.immediate) {
        var finished = false
        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            runCatching { refreshOnMain() }.getOrNull().also { finished = true }
        }
        if (!finished) YouTubeSessionLog.record("refresh", "timed out after 20 s")
        result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun refreshOnMain(): YouTubeAuth? {
        val startedAt = System.currentTimeMillis()
        YouTubeSessionLog.record("refresh", "silent refresh started")
        val view = runCatching { WebView(context) }.getOrNull()
        if (view == null) {
            YouTubeSessionLog.record("refresh", "WebView unavailable; skipping silent refresh")
            return null
        }
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = YOUTUBE_LOGIN_USER_AGENT

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(view, true)

            load(requireNotNull(view))

            val ytcfg = parseYtcfg(evaluate(requireNotNull(view)))
            if (ytcfg?.loggedIn != true) {
                YouTubeSessionLog.record(
                    "refresh",
                    "ytcfg LOGGED_IN=${if (ytcfg?.loggedIn == true) "yes" else "no"}",
                )
                return null
            }

            val cookie = mergeWebCookies(
                YOUTUBE_COOKIE_URLS.mapNotNull { url ->
                    cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }
                },
            )
            if (!hasSigningCookie(cookie)) {
                YouTubeSessionLog.record("refresh", "no signing cookie after load")
                return null
            }

            val delegated = ytcfg.delegatedSessionId.trim()
            YouTubeSessionLog.record(
                "refresh",
                "captured cookies=[${com.auralis.app.logging.SessionLogSanitizer.cookieNames(cookie).joinToString(",")}] " +
                    "clientVersion=${if (ytcfg.clientVersion.isNotBlank()) "present" else "absent"} " +
                    "in ${System.currentTimeMillis() - startedAt} ms",
            )
            return YouTubeAuth(
                cookie = cookie,
                visitorData = ytcfg.visitorData,
                dataSyncId = normalizeDataSyncId(delegated.ifBlank { ytcfg.dataSyncId }).orEmpty(),
                pageId = delegated.takeIf { it.isNotBlank() },
                authUser = ytcfg.sessionIndex.ifBlank { "0" },
                clientVersion = ytcfg.clientVersion,
                userAgent = view.settings.userAgentString.orEmpty(),
                lastRefreshedAt = System.currentTimeMillis(),
            )
        } finally {
            runCatching {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    /** Waits for the YouTube Music page (or any youtube.com navigation) to finish; the caller's 20 s
     * budget cancels this if the page never does. */
    private suspend fun load(view: WebView) {
        suspendCancellableCoroutine { continuation ->
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(page: WebView, url: String?) {
                    if (continuation.isActive && isYouTubeHost(url)) continuation.resume(Unit)
                }
            }
            view.loadUrl("https://music.youtube.com/")
        }
    }

    private suspend fun evaluate(view: WebView): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(YOUTUBE_YT_CFG_SCRIPT) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

    private fun isYouTubeHost(url: String?): Boolean =
        url != null && (url.startsWith("https://music.youtube.com") || url.startsWith("https://www.youtube.com"))

    private companion object {
        /** Whole attempt: page load plus `ytcfg` read. */
        const val TOTAL_TIMEOUT_MS = 20_000L

        /** `evaluateJavascript` returns the `JSON.stringify` result as a quoted JSON string. */
        fun parseYtcfg(result: String?): WebYtcfg? {
            if (result.isNullOrBlank() || result == "null") return null
            val trimmed = result.trim()
            val inner = if (trimmed.startsWith("\"")) {
                runCatching { JSONObject("{\"v\":$trimmed}").optString("v") }.getOrNull()
            } else {
                trimmed
            }
            if (inner.isNullOrBlank()) return null
            val json = runCatching { JSONObject(inner) }.getOrNull() ?: return null
            return WebYtcfg(
                loggedIn = json.optBoolean("loggedIn"),
                delegatedSessionId = json.optString("delegated"),
                dataSyncId = json.optString("dataSync"),
                sessionIndex = json.optString("sessionIndex", "0"),
                visitorData = json.optString("visitor"),
                clientVersion = json.optString("clientVersion").trim(),
            )
        }
    }
}