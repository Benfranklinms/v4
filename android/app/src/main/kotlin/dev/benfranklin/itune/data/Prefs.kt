package dev.benfranklin.itune.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI + playback preferences, exposed as StateFlows so changes animate live. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("itune", Context.MODE_PRIVATE)

    inner class Pref<T>(private val key: String, default: T, private val read: (String) -> T, private val write: SharedPreferences.Editor.(String, T) -> Unit) {
        private val flow = MutableStateFlow(if (sp.contains(key)) read(key) else default)
        val state: StateFlow<T> get() = flow
        var value: T
            get() = flow.value
            set(v) {
                flow.value = v
                sp.edit().apply { write(key, v) }.apply()
            }
    }

    private fun str(k: String, d: String) = Pref(k, d, { sp.getString(it, d) ?: d }, { a, b -> putString(a, b) })
    private fun bool(k: String, d: Boolean) = Pref(k, d, { sp.getBoolean(it, d) }, { a, b -> putBoolean(a, b) })
    private fun float(k: String, d: Float) = Pref(k, d, { sp.getFloat(it, d) }, { a, b -> putFloat(a, b) })
    private fun int(k: String, d: Int) = Pref(k, d, { sp.getInt(it, d) }, { a, b -> putInt(a, b) })
    private fun long(k: String, d: Long) = Pref(k, d, { sp.getLong(it, d) }, { a, b -> putLong(a, b) })

    val theme = str("theme", "mono")
    val followSystem = bool("follow_system", false)
    val pureBlack = bool("pure_black", false)
    val textScale = float("text_scale", 1f)
    val lyricsScale = float("lyrics_scale", 1f)
    val font = str("font", "manrope")
    val customFontPath = str("custom_font_path", "")
    val customFontName = str("custom_font_name", "")
    val customFontNumbers = bool("custom_font_numbers", false)
    /** Strict LRU cap for streamed audio (incl. prefetch): 0 (off), 128, 256, 512, 1024. */
    val cacheMb = int("stream_cache_mb", 256)
    val autoplay = bool("autoplay", true)
    val normalize = bool("normalize", true)
    /** Fade between songs, seconds (0 = off, gapless). */
    val crossfadeSec = int("crossfade_sec", 0)
    val haptics = bool("haptics", true)
    val resumeJson = str("resume_json", "")
    val librarySort = str("library_sort", "recent")
    val libraryGrid = bool("library_grid", false)
    val batteryAsked = bool("battery_asked", false)
    val lastUpdateCheck = long("last_update_check", 0L)
    val skippedVersion = str("skipped_version", "")
    /** Set after an update check returned 401/404 with no token saved (repo went private). */
    val updateNeedsKey = bool("update_needs_key", false)
    val signedIn = bool("signed_in", false)
    val userName = str("user_name", "Ben")
    val localFiles = bool("local_files", false)
}
