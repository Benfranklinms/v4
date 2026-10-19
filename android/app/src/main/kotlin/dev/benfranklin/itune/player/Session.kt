package dev.benfranklin.itune.player

import android.content.Context
import dev.benfranklin.itune.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last-played session (queue, index, position, source) so a relaunch restores the song paused where it
 * stopped, like Apple Music. Saved on pause, every few seconds while playing, on onStop and when the
 * service is destroyed; read on launch and for the media-resumption notification.
 * Stored in a tiny private prefs file (written off the main thread via apply()).
 * TODO(core): switch to core restore_session() once it lands; keep this as the fallback.
 */
object Session {
    data class Saved(val tracks: List<Track>, val index: Int, val positionMs: Long, val source: String)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun save(ctx: Context, tracks: List<Track>, index: Int, positionMs: Long, source: String) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        // keep a window around the current song so the blob stays small
        val from = (index - 50).coerceAtLeast(0)
        val win = tracks.subList(from, (index + 150).coerceAtMost(tracks.size)).filter { !it.id.startsWith("local:") || it === tracks[index] }
        val idx = win.indexOfFirst { it === tracks[index] }.coerceAtLeast(0)
        val arr = JSONArray()
        win.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("t", t.title).put("a", t.artists).put("ai", t.artistId ?: "")
                .put("al", t.album ?: "").put("ali", t.albumId ?: "").put("d", t.durationS.toLong()).put("th", t.thumb))
        }
        val o = JSONObject().put("v", 1).put("i", idx).put("p", positionMs.coerceAtLeast(0)).put("s", source).put("q", arr)
        runCatching { sp(ctx).edit().putString("last", o.toString()).apply() }
    }

    fun load(ctx: Context): Saved? = runCatching {
        val o = JSONObject(sp(ctx).getString("last", null) ?: return null)
        val a = o.getJSONArray("q")
        val list = (0 until a.length()).map { k ->
            val j = a.getJSONObject(k)
            Track(
                j.getString("id"), j.optString("t"), j.optString("a"), j.optString("ai").ifEmpty { null },
                j.optString("al").ifEmpty { null }, j.optString("ali").ifEmpty { null }, j.optLong("d").toUInt(), j.optString("th"),
            )
        }
        if (list.isEmpty()) null else Saved(list, o.optInt("i").coerceIn(0, list.lastIndex), o.optLong("p"), o.optString("s"))
    }.getOrNull()
}
