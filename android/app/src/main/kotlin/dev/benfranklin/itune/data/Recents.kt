package dev.benfranklin.itune.data

import android.content.Context
import android.util.Log
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Home › recently played grid: the last collections the listener played from (albums, playlists, Liked Songs,
 * mixes, radio, Isa queues), newest first. Pages register what they are ([register]) under the source title they
 * hand to Playback; [onPlay] (called by Playback.play on the main thread) turns a play into an entry. The list is
 * a StateFlow (the grid updates live); disk reads/writes happen on IO only.
 */
object Recents {
    /** kind: album | playlist | liked | mix | radio. Mixes / radio keep their tracks (they have no page id). */
    data class Origin(
        val kind: String,
        val id: String,
        val title: String,
        val subtitle: String = "",
        val thumb: String = "",
        val tracks: List<Track> = emptyList(),
    ) {
        val key: String get() = "$kind:" + (if (kind == "mix" || kind == "radio") title else id)
    }

    private const val MAX = 12
    private const val MAX_TRACKS = 100
    private val _items = MutableStateFlow<List<Origin>>(emptyList())
    val items: StateFlow<List<Origin>> get() = _items
    /** Key of the collection the current queue came from (null = a single song / search / unknown). */
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> get() = _active
    /** Bumped on every play, so the Home grid refreshes its recent-track filler. */
    private val _plays = MutableStateFlow(0)
    val plays: StateFlow<Int> get() = _plays

    private val registered = object : LinkedHashMap<String, Origin>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Origin>?) = size > 24
    }
    private val loaded = AtomicBoolean(false)

    private fun sp() = App.instance.getSharedPreferences("recents", Context.MODE_PRIVATE)

    /** Reads the saved list once (IO thread). */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray(sp().getString("items", "[]") ?: "[]")
                val l = (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
                // a play that happened before the read finished stays on top
                val cur = _items.value
                _items.value = (cur + l.filter { o -> cur.none { it.key == o.key } }).take(MAX)
                _active.compareAndSet(null, sp().getString("active", null))
            }.onFailure { Log.w("itune-recents", "load failed", it) }
        }
    }

    /** A page / menu says: plays with [source] come from [o]. Main thread, cheap. */
    fun register(source: String, o: Origin) {
        if (source.isBlank()) return
        synchronized(registered) { registered[source] = o }
    }

    /** Called for every queue start (main thread). Never blocks: persistence is on IO. */
    fun onPlay(source: String, list: List<Track>) {
        val o = synchronized(registered) { registered[source] } ?: infer(source, list)
        _plays.value = _plays.value + 1
        if (o == null) { setActive(null); return }
        val e = if (o.kind == "mix" || o.kind == "radio") o.copy(tracks = list.take(MAX_TRACKS), thumb = o.thumb.ifEmpty { list.firstOrNull()?.thumb ?: "" }) else o
        _items.value = (listOf(e) + _items.value.filter { it.key != e.key }).take(MAX)
        setActive(e.key)
        Log.i("itune-recents", "played ${e.kind} '${e.title}' n=${_items.value.size}")
        save()
    }

    private fun setActive(k: String?) {
        _active.value = k
    }

    /** Sources without a registered page: radio and Isa queues are collections too; everything else is a song. */
    private fun infer(source: String, list: List<Track>): Origin? = when {
        list.size < 2 -> null
        source.endsWith(" radio") -> Origin("radio", source, source, "Radio", list.firstOrNull()?.thumb ?: "")
        source.startsWith("Isa · ") -> Origin("mix", source, source.removePrefix("Isa · "), "Made with Isa", list.firstOrNull()?.thumb ?: "")
        else -> null
    }

    private fun save() {
        val snap = _items.value
        val act = _active.value
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                snap.forEach { arr.put(toJson(it)) }
                sp().edit().putString("items", arr.toString()).putString("active", act).apply()
            }.onFailure { Log.w("itune-recents", "save failed", it) }
        }
    }

    private fun toJson(o: Origin) = JSONObject().put("kind", o.kind).put("id", o.id).put("title", o.title).put("subtitle", o.subtitle).put("thumb", o.thumb)
        .put("tracks", JSONArray().apply { o.tracks.forEach { put(trackJson(it)) } })

    private fun fromJson(j: JSONObject): Origin? = runCatching {
        val ts = j.optJSONArray("tracks")
        Origin(
            j.getString("kind"), j.getString("id"), j.getString("title"), j.optString("subtitle"), j.optString("thumb"),
            if (ts == null) emptyList() else (0 until ts.length()).map { trackOf(ts.getJSONObject(it)) },
        )
    }.getOrNull()

    internal fun trackJson(t: Track) = JSONObject()
        .put("id", t.id).put("title", t.title).put("artists", t.artists).put("thumb", t.thumb)
        .put("album", t.album ?: "").put("albumId", t.albumId ?: "").put("artistId", t.artistId ?: "").put("dur", t.durationS.toLong())

    internal fun trackOf(o: JSONObject) = Track(
        id = o.getString("id"), title = o.getString("title"), artists = o.optString("artists"),
        artistId = o.optString("artistId").ifEmpty { null }, album = o.optString("album").ifEmpty { null },
        albumId = o.optString("albumId").ifEmpty { null }, durationS = o.optLong("dur").toUInt(), thumb = o.optString("thumb"),
    )
}
