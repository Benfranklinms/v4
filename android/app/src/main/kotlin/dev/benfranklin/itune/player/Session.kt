package dev.benfranklin.itune.player

import android.content.Context
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Track
import org.json.JSONObject

/**
 * Last-played session (queue, index, position, shuffle/repeat, source) so a relaunch restores the song
 * PAUSED where it stopped, like Apple Music. The Rust core owns it: [save] calls core saveSession (the core
 * already holds the queue, so only the position is passed) and [restore] calls core restoreSession, which also
 * loads the queue back into the core. No network, nothing plays. Both are blocking DB calls: callers run them
 * off the main thread (Playback's core thread / a background future).
 *
 * The 0.1.x Android-only prefs copy is read once as a fallback (migration) and then dropped.
 */
object Session {
    data class Saved(
        val tracks: List<Track>, val index: Int, val positionMs: Long, val source: String,
        val shuffle: Boolean = false, val repeat: Int = 0, val fromCore: Boolean = true,
    )

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Off-main only. */
    fun save(positionMs: Long) {
        runCatching { App.core.saveSession(positionMs.coerceAtLeast(0L).toULong()) }
            .onFailure { android.util.Log.w("iTune", "saveSession failed", it) }
    }

    /** Off-main only. Restores into the core queue and returns what to show. */
    fun restore(ctx: Context): Saved? {
        val s = runCatching { App.core.restoreSession() }.getOrNull()
        if (s != null && s.tracks.isNotEmpty()) {
            return Saved(s.tracks, s.index.coerceIn(0, s.tracks.lastIndex), s.positionMs.toLong(), s.sourceTitle, s.shuffle, s.repeat.toInt(), true)
        }
        return legacy(ctx)
    }

    fun clear(ctx: Context) {
        runCatching { App.core.clearSession() }
        runCatching { sp(ctx).edit().remove("last").apply() }
    }

    /** 0.1.x prefs session (read once, then removed: the core keeps it from now on). */
    private fun legacy(ctx: Context): Saved? = runCatching {
        val raw = sp(ctx).getString("last", null) ?: return null
        sp(ctx).edit().remove("last").apply()
        val o = JSONObject(raw)
        val a = o.getJSONArray("q")
        val list = (0 until a.length()).map { k ->
            val j = a.getJSONObject(k)
            Track(
                j.getString("id"), j.optString("t"), j.optString("a"), j.optString("ai").ifEmpty { null },
                j.optString("al").ifEmpty { null }, j.optString("ali").ifEmpty { null }, j.optLong("d").toUInt(), j.optString("th"),
            )
        }
        if (list.isEmpty()) null else Saved(list, o.optInt("i").coerceIn(0, list.lastIndex), o.optLong("p"), o.optString("s"), fromCore = false)
    }.getOrNull()
}
