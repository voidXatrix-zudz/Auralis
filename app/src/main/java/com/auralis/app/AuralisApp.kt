package com.auralis.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.auralis.app.data.sources.AutoScanWorker
import com.auralis.app.logging.AppLog

class AuralisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "AURALIS started · ${BuildConfig.VERSION_NAME}")
        // Bound the artwork memory cache to 15% of the heap. Large album art is the biggest single
        // allocation in the app, so this keeps a full-res library browse from pressuring the GC.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.15)
                        .build()
                }
                .build()
        }
        container = AppContainer(this)
        container.autoScanner.start()
        AutoScanWorker.schedule(this)
    }
}
