package dev.benfranklin.itune

import android.app.Application
import dev.benfranklin.itune.core.Core
import dev.benfranklin.itune.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
    }

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
                runCatching { c.setCacheLimitMb(prefs.cacheMb.value.toUInt()) }
            }
        }
    }
}
