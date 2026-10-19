package dev.benfranklin.itune.data

import android.util.Log
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.AccountInfo
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.Artist
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every call into the Rust core from UI code goes through here: off the main thread, errors caught
 * (a CoreException or an InternalException from a Rust panic never reaches the UI thread).
 */
object Bg {
    fun run(block: () -> Unit) {
        App.scope.launch(Dispatchers.IO) { runCatching(block).onFailure { Log.w("iTune", "background core call failed", it) } }
    }
}

suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) {
    runCatching { block() }.onFailure { Log.w("iTune", "core call failed", it) }.getOrNull()
}

suspend fun <T> ioResult(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

/** Liked track ids in memory, so rows and buttons never query SQLite on the main thread. */
object Likes {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    fun load() = Bg.run { _ids.value = App.core.liked().map { it.id }.toSet() }
    fun isLiked(id: String) = id in _ids.value
    fun set(t: Track, liked: Boolean) {
        _ids.value = if (liked) _ids.value + t.id else _ids.value - t.id
        Bg.run { App.core.setLiked(t, liked) }
    }
    fun toggle(t: Track): Boolean {
        val now = !isLiked(t.id)
        set(t, now)
        return now
    }
}

/**
 * Library lists as flows. Mutations update the flow first (optimistic, the LazyColumn diffs by key and animates),
 * then persist in the Rust core on IO.
 */
object Library {
    val playlists = MutableStateFlow<List<Playlist>?>(null)
    val savedPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val artists = MutableStateFlow<List<Artist>>(emptyList())
    val liked = MutableStateFlow(0)
    val downloads = MutableStateFlow(0)
    /** Ids hidden while their delete is waiting for the undo window to pass. */
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    val pinned = MutableStateFlow<Set<String>>(emptySet())

    fun refresh() = Bg.run {
        val c = App.core
        playlists.value = c.playlists()
        savedPlaylists.value = c.savedPlaylists()
        albums.value = c.savedAlbums()
        artists.value = c.savedArtists()
        val n = c.libraryCounts()
        liked.value = n.liked.toInt()
        downloads.value = n.downloads.toInt()
        pinned.value = (c.getSetting("pinned") ?: "").split('\n').filter { it.isNotBlank() }.toSet()
    }

    fun upsert(p: Playlist) {
        playlists.value = listOf(p) + playlists.value.orEmpty().filter { it.id != p.id }
    }

    fun replace(p: Playlist) {
        playlists.value = playlists.value.orEmpty().map { if (it.id == p.id) p else it }
    }

    fun hide(ids: Collection<String>) { hidden.value = hidden.value + ids }
    fun unhide(ids: Collection<String>) { hidden.value = hidden.value - ids.toSet() }

    /** Commit a delete after the undo window: one transaction in the core, then drop from the list. */
    fun commitDelete(ids: Collection<String>) {
        val list = ids.toList()
        playlists.value = playlists.value.orEmpty().filter { it.id !in list }
        val saved = savedPlaylists.value.filter { it.id in list }
        savedPlaylists.value = savedPlaylists.value.filter { it.id !in list }
        hidden.value = hidden.value - list.toSet()
        Bg.run {
            App.core.deletePlaylists(list.filter { it.startsWith("local:") || it.startsWith("import:") || it.startsWith("isa:") })
            saved.forEach { App.core.savePlaylist(it, false) }
        }
    }

    fun togglePin(id: String) {
        val now = if (id in pinned.value) pinned.value - id else pinned.value + id
        pinned.value = now
        Bg.run { App.core.setSetting("pinned", now.joinToString("\n")) }
    }
}

/** Google account shown in the Account sheet (name, handle, avatar), cached in the core. */
object Account {
    val info = MutableStateFlow<AccountInfo?>(null)
    fun load(refresh: Boolean = false) {
        App.scope.launch {
            io { App.core.accountInfo(false) }?.let { info.value = it }
            if (refresh || info.value == null) io { App.core.accountInfo(true) }?.let { info.value = it }
            info.value?.name?.takeIf { it.isNotBlank() }?.let { App.prefs.userName.value = it.substringBefore(' ') }
        }
    }
}

/** Last track + position for Home › Continue listening. */
object Resume {
    data class Entry(val track: Track, val positionMs: Long, val durationMs: Long)

    fun save(t: Track, pos: Long, dur: Long) {
        if (dur > 0 && pos > dur - 15_000) { App.prefs.resumeJson.value = ""; return }
        if (pos < 10_000) return
        val o = org.json.JSONObject()
            .put("id", t.id).put("title", t.title).put("artists", t.artists).put("thumb", t.thumb)
            .put("album", t.album ?: "").put("albumId", t.albumId ?: "").put("artistId", t.artistId ?: "")
            .put("dur", t.durationS.toLong()).put("pos", pos).put("total", dur)
        App.prefs.resumeJson.value = o.toString()
    }

    fun load(): Entry? = runCatching {
        val j = App.prefs.resumeJson.value.takeIf { it.isNotBlank() } ?: return null
        val o = org.json.JSONObject(j)
        val t = Track(
            id = o.getString("id"), title = o.getString("title"), artists = o.getString("artists"),
            artistId = o.optString("artistId").ifEmpty { null }, album = o.optString("album").ifEmpty { null },
            albumId = o.optString("albumId").ifEmpty { null }, durationS = o.optLong("dur").toUInt(), thumb = o.optString("thumb"),
        )
        Entry(t, o.optLong("pos"), o.optLong("total"))
    }.getOrNull()
}

/** Connectivity for the offline banner. */
object Net {
    val online = MutableStateFlow(true)
    fun watch(ctx: android.content.Context) {
        runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            fun check() {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                online.value = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            check()
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { online.value = true }
                override fun onLost(network: android.net.Network) { check() }
                override fun onCapabilitiesChanged(network: android.net.Network, c: android.net.NetworkCapabilities) {
                    online.value = c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }
}
