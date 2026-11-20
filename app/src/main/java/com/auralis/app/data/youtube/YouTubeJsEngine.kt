package com.auralis.app.data.youtube

import android.content.Context
import com.auralis.app.logging.AppLog
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Hosts the embedded [youtubei.js](https://github.com/LuanRT/YouTube.js) bundle inside a QuickJS
 * runtime so YouTube.js can act as a third, independent stream provider.
 *
 * Design constraints:
 * - One warm [QuickJs] instance, created once on a private single thread (QuickJS contexts are not
 *   thread-safe) and reused for every call. Nothing here ever runs on the main thread.
 * - The heavy bundle (1.5 MB) and its polyfills are decoded and evaluated a single time during
 *   [warmUp]; the first user tap therefore never pays the parse cost.
 * - All network access is bridged back to the shared OkHttp client through the `__nativeFetch`
 *   async binding, so DNS/TLS/HTTP-2 pooling and timeouts stay in one place.
 * - Every failure is swallowed: if the engine cannot start or a resolve throws, YouTube.js is simply
 *   marked unavailable and the other providers carry the race.
 */
class YouTubeJsEngine(
    private val context: Context,
    private val http: OkHttpClient,
) {
    /** QuickJS is single-threaded; all evaluation and job draining share this dispatcher. */
    private val jsThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "auralis-youtubejs").apply { isDaemon = true }
    }
    private val jsDispatcher: CoroutineDispatcher = jsThread.asCoroutineDispatcher()

    private val initLock = Mutex()

    /**
     * Serialises evaluations. QuickJS contexts are single-threaded and not re-entrant, while
     * [evaluate] suspends during top-level `await` (network round-trips), so two concurrent calls
     * must never share the context at once. Prefetch and a tap can overlap; this lock makes them
     * queue instead of corrupt the runtime.
     */
    private val evalLock = Mutex()

    /** Engine-scoped coroutines; all of them are pinned to the QuickJS worker. */
    private val engineScope = CoroutineScope(SupervisorJob() + jsDispatcher)

    @Volatile private var quickJs: QuickJs? = null

    /** Set once the engine has permanently failed to start; stops every later attempt. */
    @Volatile private var failed = false

    /** Human-readable state for the Logs screen diagnostics. */
    @Volatile var status: String = "not started"
        private set

    /** True only when the runtime is loaded and the provider globals are present. */
    fun isReady(): Boolean = quickJs != null && !failed

    /**
     * True while the engine is starting or already running, false only after a permanent failure.
     * The race uses this so a tap during warm-up still includes YouTube.js instead of silently
     * dropping a provider; [resolveAudio] then waits for the build instead of failing fast.
     */
    fun isViable(): Boolean = !failed

    /** Starts the engine off the main thread; safe to call repeatedly. Never throws. */
    fun warmUp() {
        if (isReady() || failed) return
        engineScope.launch {
            val qjs = runCatching { ensureReady() }
                .onFailure { AppLog.w(TAG, "YouTube.js warm-up failed: ${it.message}") }
                .getOrNull() ?: return@launch
            // Creating the Innertube session is a network step (config + player base.js). Do it now,
            // behind the same eval lock, so the first tap only pays one player request. A transient
            // network failure is not fatal: the first resolve calls getInnertube() again itself.
            runCatching {
                evalLock.withLock {
                    withContext(jsDispatcher) {
                        qjs.evaluate<Any?>(
                            "await __auralis.warmup()",
                            filename = "warmup.js",
                        )
                    }
                }
            }.onSuccess {
                status = "ready (session warm)"
                AppLog.i(TAG, "YouTube.js session warm")
            }.onFailure {
                AppLog.d(TAG, "YouTube.js session warm-up deferred: ${it.message}")
            }
        }
    }

    /**
     * Resolves an audio URL, or null. [targetKbps] of 0 means "highest available". Returns null on
     * any timeout or error so a slow/broken engine can never win the race.
     */
    suspend fun resolveAudio(videoId: String, targetKbps: Int, timeoutMs: Long): String? {
        if (videoId.isBlank()) return null
        val script = "await __auralis.resolveAudio(${jsString(videoId)}, ${targetKbps.coerceAtLeast(0)})"
        return runCatching {
            withTimeoutOrNull(timeoutMs) {
                // A first tap may arrive while warm-up is still running; wait for it under the same
                // deadline so a cold build can never outlive the provider's race budget.
                val qjs = ensureReady() ?: return@withTimeoutOrNull null
                evalLock.withLock {
                    withContext(jsDispatcher) {
                        qjs.evaluate<String>(script, filename = "resolve.js")
                    }
                }
            }
        }.onFailure { error ->
            AppLog.d(TAG, "YouTube.js resolve skipped: ${error.message}")
        }.getOrNull()?.takeIf { it != "null" && it.isNotBlank() }
    }

    /** Best-effort song search as JSON, or null. Used only as a fallback behind InnerTube. */
    suspend fun search(query: String, timeoutMs: Long): String? {
        if (query.isBlank()) return null
        val script = "await __auralis.search(${jsString(query)})"
        return runCatching {
            withTimeoutOrNull(timeoutMs) {
                val qjs = ensureReady() ?: return@withTimeoutOrNull null
                evalLock.withLock {
                    withContext(jsDispatcher) {
                        qjs.evaluate<String>(script, filename = "search.js")
                    }
                }
            }
        }.getOrNull()?.takeIf { it != "null" && it.isNotBlank() }
    }

    private suspend fun ensureReady(): QuickJs? {
        quickJs?.let { return it }
        if (failed) return null
        return initLock.withLock {
            quickJs?.let { return@withLock it }
            if (failed) return@withLock null
            val created = runCatching { build() }
                .onFailure {
                    failed = true
                    status = "unavailable: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}"
                    AppLog.w(TAG, "YouTube.js engine unavailable; continuing without it", it)
                }
                .getOrNull()
            if (created != null) {
                quickJs = created
                status = "ready (${created.version})"
                AppLog.i(TAG, "YouTube.js engine ready (QuickJS ${created.version})")
            }
            created
        }
    }

    private suspend fun build(): QuickJs {
        // Read the assets off the JS thread first so evaluation sees everything at once.
        val polyfills = readAsset("youtubejs/polyfills.js")
        val httpShims = readAsset("youtubejs/http.js")
        val bundle = transformBundleExports(readAsset("youtubejs/browser.js"))
        val api = readAsset("youtubejs/api.js")

        return withContext(jsDispatcher) {
            val qjs = QuickJs.create(jobDispatcher = jsDispatcher)
            // The bundle is a 1.5 MB classic script; give the parser and its recursive helpers room.
            qjs.maxStackSize = 4L * 1024 * 1024
            qjs.evaluationTimeoutMillis = 20_000
            qjs.asyncFunction<String>("__nativeFetch") { args ->
                nativeFetch(args.firstOrNull() as? String ?: "")
            }
            qjs.evaluate<Any?>(polyfills, filename = "polyfills.js")
            qjs.evaluate<Any?>(httpShims, filename = "http.js")
            qjs.evaluate<Any?>(bundle, filename = "youtubei.js")
            qjs.evaluate<Any?>(api, filename = "auralis-api.js")
            val ready = qjs.evaluate<Boolean>(
                "!!(globalThis.__YTJS && globalThis.__YTJS.Innertube && " +
                    "globalThis.__auralis && globalThis.__auralis.resolveAudio)",
                filename = "probe.js",
            )
            if (!ready) {
                runCatching { qjs.close() }
                throw QuickJsException("YouTube.js globals missing after load")
            }
            qjs
        }
    }

    /**
     * The browser bundle is an ES module ending in a single `export { A, B as C };` block. Rewriting
     * that block into `globalThis.__YTJS = { A, B: C };` lets it load as a plain script, so the
     * engine needs no module-loader wiring and the 1.5 MB file is evaluated exactly once.
     */
    private fun transformBundleExports(source: String): String {
        val exportIndex = source.lastIndexOf("export {")
        if (exportIndex < 0) return source
        val open = source.indexOf('{', exportIndex)
        val close = source.indexOf('}', open)
        if (open < 0 || close < 0) return source
        val entries = source.substring(open + 1, close)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val properties = entries.joinToString(",\n") { entry ->
            val renamed = RENAME.matchEntire(entry)
            if (renamed != null) "${renamed.groupValues[2]}: ${renamed.groupValues[1]}" else entry
        }
        val head = source.substring(0, exportIndex)
        val tail = source.substring(close + 1)
        return "$head" + "globalThis.__YTJS = {\n" + properties + "\n};" + tail
    }

    /** Runs one HTTP request on behalf of the JS `fetch` shim and returns a JSON envelope. */
    private suspend fun nativeFetch(payload: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = JSONObject(payload)
            val url = request.getString("url")
            val method = request.optString("method", "GET").uppercase()
            val bodyText = if (request.has("body") && !request.isNull("body")) request.optString("body") else null
            val headers = request.optJSONObject("headers")

            val builder = Request.Builder().url(url)
            var hasUserAgent = false
            headers?.let { map ->
                val names = map.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    // Let OkHttp own transport headers: it adds gzip + Length and transparently
                    // decompresses, which it will not do if the caller pins accept-encoding.
                    when (name.lowercase()) {
                        "accept-encoding", "content-length", "host", "connection", "transfer-encoding" -> continue
                        "user-agent" -> hasUserAgent = true
                    }
                    builder.header(name, map.optString(name))
                }
            }
            if (!hasUserAgent) builder.header("User-Agent", YOUTUBE_USER_AGENT)

            val hasBody = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
            val okhttpRequest = if (hasBody) {
                builder.method(method, (bodyText ?: "").toRequestBody(JSON_MEDIA))
            } else {
                builder.method(method, null)
            }.build()

            http.newCall(okhttpRequest).execute().use { response ->
                val body = response.body.string()
                JSONObject().apply {
                    put("status", response.code)
                    put("statusText", response.message)
                    put("url", response.request.url.toString())
                    put("body", body)
                    put("headers", JSONObject().apply {
                        val names = response.headers.names()
                        for (name in names) put(name, response.headers.values(name).joinToString(", "))
                    })
                }.toString()
            }
        } catch (error: Throwable) {
            JSONObject().put("error", error.message ?: error.javaClass.simpleName).toString()
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun jsString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private companion object {
        const val TAG = "YouTube"
        val JSON_MEDIA = "application/json".toMediaType()
        val RENAME = Regex("^(\\S+)\\s+as\\s+(\\S+)$")
    }
}
