package com.auralis.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.app.data.youtube.YOUTUBE_COOKIE_URLS
import com.auralis.app.data.youtube.YOUTUBE_LOGIN_USER_AGENT
import com.auralis.app.data.youtube.YOUTUBE_YT_CFG_SCRIPT
import com.auralis.app.data.youtube.YouTubeAuth
import com.auralis.app.data.youtube.YouTubeLoginMode
import com.auralis.app.data.youtube.clearYouTubeWebSessionCookies
import com.auralis.app.data.youtube.continueLabel
import com.auralis.app.data.youtube.hasSigningCookie
import com.auralis.app.data.youtube.installYouTubeSavedCookies
import com.auralis.app.data.youtube.loginMode
import com.auralis.app.data.youtube.mergeWebCookies
import com.auralis.app.logging.SessionLogSanitizer
import com.auralis.app.logging.YouTubeSessionLog
import org.json.JSONObject

/** Google account chooser loaded in the in-app WebView; no OAuth, no external browser. */
private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/** The signed-in YouTube Music page; opened first when a saved session exists. */
private const val MUSIC_URL = "https://music.youtube.com/"

/**
 * Runs only when the user taps Confirm. Returns the documented `ytcfg` keys; the cookie itself is
 * read from [CookieManager] in Kotlin, never from JavaScript.
 */
private const val SESSION_SCRIPT = YOUTUBE_YT_CFG_SCRIPT

/** Values captured by an explicit Confirm tap; the caller merges them into [YouTubeAuth]. */
data class YouTubeCapturedSession(
    val cookie: String,
    val visitorData: String = "",
    /** Raw `DATASYNC_ID`; the caller normalises it before use. */
    val dataSyncId: String = "",
    /** `DELEGATED_SESSION_ID` (a brand channel), empty for a personal account. */
    val delegatedSessionId: String = "",
    val sessionIndex: String = "0",
    val clientVersion: String = "",
    /** The exact WebView user agent, replayed on signed requests. */
    val userAgent: String = "",
    /**
     * Saved display identity, carried only on the Continue path. A one-tap re-confirm must not blank
     * the name while the account refresh is still in flight; a fresh sign-in passes none, so a
     * different account never inherits the old profile.
     */
    val name: String = "",
    val email: String = "",
    val avatarUrl: String = "",
)

/**
 * Full-screen in-app Google sign-in.
 *
 * With a saved session the dialog restores those cookies into the WebView jar and opens
 * music.youtube.com first, so a still-valid account is one Confirm tap away instead of a fresh
 * Google sign-in. If the page reports `LOGGED_IN=false` it falls back to the normal account form.
 * The factory never wipes cookies; only "Use a different account" (or Disconnect in Settings)
 * expires session cookies, and only by name.
 *
 * Nothing is captured automatically: cookies and `ytcfg` are read only after the user taps
 * Confirm. Cookie values are never logged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    onSession: (YouTubeCapturedSession) -> Unit,
    onDismiss: () -> Unit,
    loginHint: String? = null,
    savedSession: YouTubeAuth? = null,
    /** Settings' "Use a different account": skip the saved-session path and open the account form. */
    forceSignIn: Boolean = false,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Set once if this device has no WebView engine, so the factory stops retrying. */
    var webViewUnavailable by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(YouTubeLoginMode.SIGN_IN) }
    var continueName by remember {
        mutableStateOf(if (forceSignIn) "" else savedSession?.name.orEmpty())
    }
    // The saved identity used by the Continue label. Cleared when the user asks for a different
    // account, so the button can never name the previous account during the new sign-in.
    var labelProfile by remember { mutableStateOf(if (forceSignIn) null else savedSession) }
    // Bumped on "Use a different account"; a probe that started on the old page must not re-enable
    // Continue for the account the user is leaving.
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        YouTubeSessionLog.record(
            "login",
            "dialog opened saved=${if (savedSession?.hasSession == true) "yes" else "no"} " +
                "name=${if (savedSession?.name.isNullOrBlank()) "none" else "present"}",
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        text = "YouTube Music",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        enabled = ready && !checking,
                        onClick = {
                            val view = webView ?: return@TextButton
                            checking = true
                            error = null
                            confirmSession(
                                view = view,
                                profile = if (mode == YouTubeLoginMode.CONTINUE) labelProfile else null,
                            ) { captured, message ->
                                checking = false
                                if (captured == null) {
                                    error = message ?: "Finish signing in to YouTube Music first, then tap Confirm."
                                } else {
                                    onSession(captured)
                                    onDismiss()
                                }
                            }
                        },
                    ) {
                        val label = if (checking) "Checking..." else {
                            continueLabel(mode, continueName.ifBlank { labelProfile?.name.orEmpty() })
                        }
                        Text(label)
                    }
                }
                if (mode == YouTubeLoginMode.CONTINUE) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        TextButton(
                            onClick = {
                                // By name only: never removeAllCookies(), which could erase other
                                // logins in the same jar. The saved app session stays until a new
                                // Confirm replaces it.
                                clearYouTubeWebSessionCookies()
                                YouTubeSessionLog.record(
                                    "login",
                                    "use a different account: browser cookies expired",
                                )
                                mode = YouTubeLoginMode.SIGN_IN
                                ready = false
                                checking = false
                                error = null
                                continueName = ""
                                labelProfile = null
                                generation += 1
                                // No login_hint: the point is to choose a different account.
                                webView?.loadUrl(loginUrl(null))
                            },
                        ) { Text("Use a different account") }
                    }
                }
                error?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        // The browser's Google login is deliberately kept: the silent refresh
                        // depends on those cookies. Only the explicit Disconnect button (or
                        // "Use a different account", by name) may erase session cookies.
                        // Guard creation: a device without a WebView engine (stripped ROM) must show
                        // a message instead of taking down the screen.
                        val created = if (webViewUnavailable) null else runCatching { WebView(context) }.getOrNull()
                        if (created == null) {
                            if (!webViewUnavailable) {
                                webViewUnavailable = true
                                error = "WebView isn't available on this device, so YouTube sign-in can't be shown here."
                            }
                            android.view.View(context)
                        } else created.apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = YOUTUBE_LOGIN_USER_AGENT
                            val manager = CookieManager.getInstance()
                            manager.setAcceptCookie(true)
                            manager.setAcceptThirdPartyCookies(this, true)

                            // Put the saved session back before the first load, so the page can
                            // open already signed in (BitChord's installGoogleCookies idea).
                            val restored = if (forceSignIn) 0 else {
                                savedSession?.cookie?.let { installYouTubeSavedCookies(it) } ?: 0
                            }
                            val jar = mergeCookies(
                                YOUTUBE_COOKIE_URLS.mapNotNull { url ->
                                    manager.getCookie(url)?.takeIf { it.isNotBlank() }
                                },
                            )
                            val startMode = if (forceSignIn) {
                                YouTubeLoginMode.SIGN_IN
                            } else {
                                loginMode(savedSession, hasSigningCookie(jar))
                            }
                            mode = startMode
                            YouTubeSessionLog.record(
                                "login",
                                "mode=${if (startMode == YouTubeLoginMode.CONTINUE) "continue" else "sign-in"} " +
                                    "restored=$restored jarSigning=${if (hasSigningCookie(jar)) "yes" else "no"}",
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    webView = view
                                    if (url == null) return
                                    if (!isYouTubeMusic(url)) {
                                        // The Google form: Confirm becomes available again once the
                                        // redirect lands on the YouTube Music page.
                                        ready = false
                                        return
                                    }
                                    // Probe once the page reports a login. A dead saved session is
                                    // replaced by the normal account form instead of a broken page.
                                    val probeGeneration = generation
                                    probeLoggedIn(view, attempt = 0) { loggedIn ->
                                        // The user switched accounts while this page was loading.
                                        if (probeGeneration != generation) return@probeLoggedIn
                                        if (loggedIn) {
                                            mode = YouTubeLoginMode.CONTINUE
                                            ready = true
                                            if (continueName.isBlank()) {
                                                continueName = labelProfile?.name.orEmpty()
                                            }
                                        } else if (mode == YouTubeLoginMode.CONTINUE) {
                                            mode = YouTubeLoginMode.SIGN_IN
                                            ready = false
                                            // A dead jar-only session still gets the Clerk hint.
                                            view.loadUrl(
                                                loginUrl(
                                                    if (savedSession?.hasSession == true) null else loginHint,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            loadUrl(
                                if (startMode == YouTubeLoginMode.CONTINUE) MUSIC_URL
                                else loginUrl(if (savedSession?.hasSession == true) null else loginHint),
                            )
                            webView = this
                        }
                    },
                )
            }
        }
    }
}

private fun isYouTubeMusic(url: String): Boolean =
    url.startsWith("https://music.youtube.com") || url.startsWith("https://www.youtube.com")

/** One `ytcfg` probe, retried once because a just-loaded page can still be booting. */
private fun probeLoggedIn(view: WebView, attempt: Int, onResult: (Boolean) -> Unit) {
    view.evaluateJavascript(SESSION_SCRIPT) { result ->
        val loggedIn = parseObject(result)?.optBoolean("loggedIn") == true
        if (loggedIn || attempt >= 1) {
            onResult(loggedIn)
        } else {
            view.postDelayed({ probeLoggedIn(view, attempt + 1, onResult) }, 700L)
        }
    }
}

/**
 * Null when the cookie is missing a signing value or `ytcfg` does not report a login; the second
 * value is the message the dialog should show.
 *
 * The cookie header is built from de-duplicated names, preferring the music.youtube.com value:
 * the two hosts can carry a stale duplicate and a duplicated name confuses the session check.
 */
private fun confirmSession(
    view: WebView,
    profile: YouTubeAuth?,
    onResult: (YouTubeCapturedSession?, String?) -> Unit,
) {
    val cookie = mergeCookies(
        YOUTUBE_COOKIE_URLS.mapNotNull { url ->
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        },
    )
    if (!hasSigningCookie(cookie)) {
        YouTubeSessionLog.record("login", "confirm refused: no signing cookie")
        onResult(null, "Finish signing in to YouTube Music first, then tap Confirm.")
        return
    }
    view.evaluateJavascript(SESSION_SCRIPT) { result ->
        val json = parseObject(result)
        if (json?.optBoolean("loggedIn") != true) {
            YouTubeSessionLog.record("login", "confirm refused: ytcfg LOGGED_IN=no")
            onResult(null, "Finish signing in to YouTube Music first, then tap Confirm.")
            return@evaluateJavascript
        }
        YouTubeSessionLog.record("login", "confirm: ytcfg LOGGED_IN=yes")
        val clientVersion = json.optString("clientVersion").trim()
        if (clientVersion.isEmpty()) {
            // A half-finished session with a guessed client version fails later with 401.
            YouTubeSessionLog.record("login", "confirm refused: clientVersion absent")
            onResult(null, "YouTube Music is still loading. Wait a moment, then tap Confirm again.")
            return@evaluateJavascript
        }
        YouTubeSessionLog.record(
            "login",
            "confirm success cookies=[" +
                SessionLogSanitizer.cookieNames(cookie).joinToString(",") + "]",
        )
        onResult(
            YouTubeCapturedSession(
                cookie = cookie,
                visitorData = json.optString("visitor"),
                dataSyncId = json.optString("dataSync"),
                delegatedSessionId = json.optString("delegated"),
                sessionIndex = json.optString("sessionIndex", "0").ifBlank { "0" },
                clientVersion = clientVersion,
                userAgent = view.settings.userAgentString.orEmpty(),
                name = profile?.name.orEmpty(),
                email = profile?.email.orEmpty(),
                avatarUrl = profile?.avatarUrl.orEmpty(),
            ),
            null,
        )
    }
}

/**
 * Joins cookie headers from several hosts by name. The first host wins, which is why
 * music.youtube.com is listed before www.youtube.com: it owns the session the app uses.
 */
internal fun mergeCookies(headers: List<String>): String = mergeWebCookies(headers)

/**
 * `evaluateJavascript` hands back the script result encoded as JSON: a script that returned
 * `JSON.stringify(...)` arrives as a quoted string. Both the quoted and bare object forms are
 * accepted; anything else collapses to null rather than throwing.
 */
private fun parseObject(result: String?): JSONObject? {
    if (result.isNullOrBlank() || result == "null") return null
    val trimmed = result.trim()
    val inner = if (trimmed.startsWith("\"")) {
        runCatching { JSONObject("{\"v\":$trimmed}").optString("v") }.getOrNull()
    } else {
        trimmed
    }
    if (inner.isNullOrBlank()) return null
    return runCatching { JSONObject(inner) }.getOrNull()
}

/** Google prefills the account chooser when a `login_hint` email is supplied. */
private fun loginUrl(loginHint: String?): String {
    val hint = loginHint?.trim().orEmpty()
    if (hint.isEmpty()) return LOGIN_URL
    return "$LOGIN_URL&login_hint=${android.net.Uri.encode(hint)}"
}