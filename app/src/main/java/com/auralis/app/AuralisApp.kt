package com.auralis.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.auralis.app.data.sources.AutoScanWorker
import com.auralis.app.data.youtube.YOUTUBE_USER_AGENT
import com.auralis.app.logging.AppLog
import com.auralis.app.logging.CrashHandler
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

class AuralisApp : Application() {
    lateinit var container: AppContainer
        private set

    /** The `:crash` process hosts only [CrashReportActivity]; it must never build the app graph. */
    private var isCrashProcess = false

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Install the crash recorder before anything else can throw, so a startup crash is captured.
        isCrashProcess = CrashHandler.currentProcessName().endsWith(CRASH_PROCESS_SUFFIX)
        if (!isCrashProcess) CrashHandler.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // The crash viewer runs in its own process: no Clerk, Room, Hilt or AppContainer here.
        if (isCrashProcess) return

        // Every step below is best-effort. A failure is logged to the app log AND the crash folder
        // (as a "startup warning") and the app continues with that feature degraded, instead of
        // taking the whole launch down. Nothing is silently swallowed.
        runStep("AppLog.init") { AppLog.init(this) }
        runStep("YouTubeSessionLog.init") { com.auralis.app.logging.YouTubeSessionLog.init(this) }
        AppLog.i("App", "AURALIS started · ${BuildConfig.VERSION_NAME}")

        runStep("imageLoader") { configureImageLoader() }

        val created = runStep("AppContainer") { AppContainer(this) }
        if (created == null) {
            AppLog.e("Startup", "AppContainer could not be created; running with a degraded container")
            return
        }
        container = created

        // Clerk must be initialized before any screen tries to read Clerk.userFlow. It is a no-op
        // (and the app skips the login gate) when no publishable key was configured. A failure here
        // must not block local-only playback.
        runStep("clerk.initialize") { container.clerkAuth.initialize() }
        runStep("downloadGraph") { com.auralis.app.data.download.DownloadGraph.container = container }
        // Create the download notification channel up front. The worker also ensures it before every
        // notify, but doing it here means the first download notification never races channel setup.
        runStep("downloadChannel") { com.auralis.app.data.download.DownloadManager.ensureChannel(this) }
        runStep("spotifyGraph") { com.auralis.app.data.spotify.SpotifyGraph.container = container }
        // Hand the graph to the live-sync worker so a background pass can reach Room and the account.
        runStep("liveSyncGraph") { com.auralis.app.data.youtube.LiveSyncGraph.container = container }
        // Establish the InnerTube HTTP/2 connection while the first screen is still drawing so the
        // first user search does not pay the TCP/TLS handshake.
        runStep("youtube.warmUp") { container.youtube.warmUp() }
        runStep("autoScanner.start") { container.autoScanner.start() }
        runStep("autoScanWorker.schedule") { AutoScanWorker.schedule(this) }
    }

    /**
     * Bound the artwork memory cache to 15% of the heap. Large album art is the biggest single
     * allocation in the app, so this keeps a full-res library browse from pressuring the GC.
     * A small disk cache lets remote YouTube artwork survive process restarts and scroll-backs.
     */
    private fun configureImageLoader() {
        val imageClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", YOUTUBE_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.15)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                        .maxSizePercent(0.03)
                        .build()
                }
                .build()
        }
    }

    /** Runs one startup step, recording (never hiding) a failure and returning null on error. */
    private inline fun <T> runStep(name: String, block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        AppLog.e("Startup", "Startup step failed: $name", error)
        CrashHandler.writeStartupWarning(this, name, error)
        null
    }

    private companion object {
        const val CRASH_PROCESS_SUFFIX = ":crash"
    }
}
