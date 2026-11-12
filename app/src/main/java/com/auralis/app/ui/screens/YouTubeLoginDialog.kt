package com.auralis.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.app.logging.AppLog
import org.json.JSONObject

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
        "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

private const val CF_ANDROID_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

private const val YTCFG_SCRIPT =
    "(function(){try{var v=(window.ytcfg&&window.ytcfg.get)?(window.ytcfg.get('VISITOR_DATA')||''):'';" +
        "var d=(window.ytcfg&&window.ytcfg.get)?(window.ytcfg.get('DATASYNC_ID')||''):'';" +
        "return JSON.stringify({v:v,d:d});}catch(e){return '';}})()"

private val SAPISID_PATTERN = Regex("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)")

/**
 * Full-screen Google sign-in WebView. Cookies are read via [CookieManager] and visitor/data-sync
 * values via `ytcfg`; nothing is written to the app log. Closes once SAPISID is present.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    onSession: (cookie: String, visitorData: String, dataSyncId: String) -> Unit,
    onDismiss: () -> Unit,
    loginHint: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Old cookies/storage from a previous account are cleared before showing login.
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }
                    WebStorage.getInstance().deleteAllData()
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = CF_ANDROID_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!url.contains("music.youtube.com")) return
                                val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                                if (!SAPISID_PATTERN.containsMatchIn(cookie)) return
                                view.evaluateJavascript(YTCFG_SCRIPT) { result ->
                                    val json = result?.takeIf { it != "null" && it.isNotBlank() }
                                    val visitorData = json?.let(::readField).orEmpty()
                                    val dataSyncId = json?.let(::readFieldD).orEmpty()
                                    AppLog.i("YouTube", "Sign-in captured (values not logged)")
                                    onSession(cookie, visitorData, dataSyncId)
                                    onDismiss()
                                }
                            }
                        }
                        loadUrl(loginUrl(loginHint))
                    }
                },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(8.dp)) { Text("Cancel") }
        }
    }
}

private fun readField(json: String): String = runCatching { JSONObject(json).optString("v") }.getOrDefault("")
private fun readFieldD(json: String): String = runCatching { JSONObject(json).optString("d") }.getOrDefault("")

/** Google's ServiceLogin prefills the account chooser when a `login_hint` email is supplied. */
private fun loginUrl(loginHint: String?): String {
    val hint = loginHint?.trim().orEmpty()
    if (hint.isEmpty()) return LOGIN_URL
    return "$LOGIN_URL&login_hint=${android.net.Uri.encode(hint)}"
}
