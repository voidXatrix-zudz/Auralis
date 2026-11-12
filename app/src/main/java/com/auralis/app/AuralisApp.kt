package com.auralis.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.auralis.app.data.sources.AutoScanWorker
import com.auralis.app.data.youtube.YOUTUBE_USER_AGENT
import com.auralis.app.logging.AppLog
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

class AuralisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "AURALIS started · ${BuildConfig.VERSION_NAME}")
        // Bound the artwork memory cache to 15% of the heap. Large album art is the biggest single
        // allocation in the app, so this keeps a full-res library browse from pressuring the GC.
        // A small disk cache lets remote YouTube artwork survive process restarts and scroll-backs.
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
        container = AppContainer(this)
        // Clerk must be initialized before any screen tries to read Clerk.userFlow. It is a no-op
        // (and the app skips the login gate) when no publishable key was configured.
        container.clerkAuth.initialize()
        com.auralis.app.data.download.DownloadGraph.container = container
        // Create the download notification channel up front. The worker also ensures it before every
        // notify, but doing it here means the first download notification never races channel setup.
        com.auralis.app.data.download.DownloadManager.ensureChannel(this)
        com.auralis.app.data.spotify.SpotifyGraph.container = container
        // Establish the InnerTube HTTP/2 connection while the first screen is still drawing so the
        // first user search does not pay the TCP/TLS handshake.
        container.youtube.warmUp()
        container.autoScanner.start()
        AutoScanWorker.schedule(this)
    }
}
