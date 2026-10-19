package dev.benfranklin.itune

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.launch
import dev.benfranklin.itune.core.Core
import dev.benfranklin.itune.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        prefs = Prefs(this)
        // Lean storage: trim the stream cache to its cap on every start.
        scope.launch { runCatching { dev.benfranklin.itune.player.DataSources.applyCap(this@App) } }
        // open the Rust core (SQLite + catalogue client) off the main thread at start
        scope.launch(Dispatchers.IO) { runCatching { core } }
    }

    /** Images: 64 MB disk, ~15% of heap in memory. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(64L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()

    companion object {
        lateinit var instance: App
            private set
        lateinit var prefs: Prefs
            private set
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The shared Rust engine (search, queue, stats, lyrics, Isa, downloads, updates). */
        val core: Core by lazy {
            Core(instance.filesDir.absolutePath + "/core", "android").also { c ->
                c.setAutoplay(prefs.autoplay.value)
                c.lastfmConfigure(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_API_SECRET)
                runCatching { c.setCacheLimitMb(prefs.cacheMb.value.toUInt()) }
            }
        }
    }
}
