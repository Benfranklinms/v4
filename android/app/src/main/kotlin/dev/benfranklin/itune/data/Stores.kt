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
import kotlinx.coroutines.flow.update
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

/**
 * Liked track ids in memory, so rows and buttons never query SQLite on the main thread. Every like/unlike goes
 * through [set], which updates the ids, the Liked Songs list and its count at once (optimistic), then writes to
 * the core on the library's serial queue and re-reads the authoritative list.
 */
object Likes {
    internal val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    fun load() = Library.write { if (pending.get() == 0) Library.applyLikes(App.core.liked()) }
    fun isLiked(id: String) = id in _ids.value
    fun set(t: Track, liked: Boolean) {
        _ids.update { if (liked) it + t.id else it - t.id }
        val cur = Library.likedTracks.value
        if (cur != null) {
            val next = if (liked) listOf(t) + cur.filter { it.id != t.id } else cur.filter { it.id != t.id }
            Library.likedTracks.value = next
            Library.liked.value = next.size
        } else {
            Library.liked.update { (it + if (liked) 1 else -1).coerceAtLeast(0) }
        }
        pending.incrementAndGet()
        Library.write {
            try { App.core.setLiked(t, liked) } finally {
                // only the last of a burst of toggles re-reads, so an older write never flips the UI back
                if (pending.decrementAndGet() == 0) Library.applyLikes(App.core.liked())
            }
        }
    }
    fun toggle(t: Track): Boolean {
        val now = !isLiked(t.id)
        set(t, now)
        return now
    }
    internal fun idle() = pending.get() == 0
}

/**
 * Library state as observable flows (the single source the Library tiles, Liked Songs, Downloads, playlist
 * pages, Now Playing and menus read). Mutations update the flows first (optimistic, LazyColumns diff by key and
 * animate), then persist in the Rust core on one serial IO queue (FIFO, so a re-read never overtakes a write)
 * and re-read what they changed.
 */
object Library {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeQ = Dispatchers.IO.limitedParallelism(1)

    /** Run a core write (then its re-read) on the library's serial queue, errors logged. */
    fun write(block: () -> Unit) {
        App.scope.launch(writeQ) { runCatching(block).onFailure { Log.w("iTune", "library write failed", it) } }
    }

    val playlists = MutableStateFlow<List<Playlist>?>(null)
    val savedPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val artists = MutableStateFlow<List<Artist>>(emptyList())
    /** Liked Songs, newest first (null until first read). */
    val likedTracks = MutableStateFlow<List<Track>?>(null)
    val liked = MutableStateFlow(0)
    /** Downloaded tracks, newest first (null until first read), and their ids for row/button state. */
    val downloadTracks = MutableStateFlow<List<Track>?>(null)
    val downloadIds = MutableStateFlow<Set<String>>(emptySet())
    val downloads = MutableStateFlow(0)
    /** Bumped whenever a playlist's tracks change; open playlist pages reload their list. */
    val trackChanges = MutableStateFlow(0)
    /** Ids hidden while their delete is waiting for the undo window to pass. */
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    val pinned = MutableStateFlow<Set<String>>(emptySet())

    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w("iTune", "library refresh: $name failed", it) }
    }

    /** Re-read everything; each part on its own, so one failing read never leaves the counts stale. */
    fun refresh() = write {
        val c = App.core
        step("playlists") { playlists.value = c.playlists() }
        step("saved") { savedPlaylists.value = c.savedPlaylists() }
        step("albums") { albums.value = c.savedAlbums() }
        step("artists") { artists.value = c.savedArtists() }
        step("liked") { if (Likes.idle()) applyLikes(c.liked()) }
        step("downloads") { applyDownloads(c.downloads().map { it.track }) }
        step("pinned") { pinned.value = (c.getSetting("pinned") ?: "").split('\n').filter { it.isNotBlank() }.toSet() }
    }

    internal fun applyLikes(l: List<Track>) {
        likedTracks.value = l
        liked.value = l.size
        Likes._ids.value = l.mapTo(HashSet()) { it.id }
    }

    private fun applyDownloads(l: List<Track>) {
        downloadTracks.value = l
        downloadIds.value = l.mapTo(HashSet()) { it.id }
        downloads.value = l.size
    }

    private fun reloadDownloads() = step("downloads") { applyDownloads(App.core.downloads().map { it.track }) }

    /** Download one track (suspends while it downloads); Library tiles, Downloads and buttons update on success. */
    suspend fun download(t: Track): Result<Unit> {
        val r = ioResult { App.core.download(t) }
        if (r.isSuccess) {
            downloadIds.update { it + t.id }
            downloadTracks.update { cur -> cur?.let { listOf(t) + it.filter { x -> x.id != t.id } } }
            downloads.value = downloadTracks.value?.size ?: downloadIds.value.size
            write { reloadDownloads() }
        }
        return r.map { }
    }

    /** Drop downloads from every list now (optimistic, e.g. during an undo window); [commitRemoveDownloads] deletes. */
    fun hideDownloads(ids: Collection<String>) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        downloadIds.update { it - set }
        downloadTracks.update { cur -> cur?.filter { it.id !in set } }
        downloads.value = downloadTracks.value?.size ?: downloadIds.value.size
    }

    /** Undo of [hideDownloads]: re-read the files that are still there. */
    fun restoreDownloads() = write { reloadDownloads() }

    fun commitRemoveDownloads(ids: Collection<String>) {
        val list = ids.toList()
        if (list.isEmpty()) return
        write {
            App.core.removeDownloads(list)
            reloadDownloads()
        }
    }

    fun removeDownloads(ids: Collection<String>) {
        hideDownloads(ids)
        commitRemoveDownloads(ids)
    }

    fun removeAllDownloads() {
        applyDownloads(emptyList())
        write { App.core.removeAllDownloads(); reloadDownloads() }
    }

    /** Add a track to a local playlist: count and cover update now, the page reloads after the write. */
    fun addToPlaylist(p: Playlist, t: Track) {
        replace(p.copy(trackCount = (p.trackCount ?: 0uL) + 1uL, thumb = p.thumb.ifEmpty { t.thumb }))
        write {
            App.core.addToPlaylist(p.id, t)
            step("playlists") { playlists.value = App.core.playlists() }
            trackChanges.update { it + 1 }
        }
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
        write {
            App.core.deletePlaylists(list.filter { it.startsWith("local:") || it.startsWith("import:") || it.startsWith("isa:") })
            saved.forEach { App.core.savePlaylist(it, false) }
        }
    }

    fun togglePin(id: String) {
        val now = if (id in pinned.value) pinned.value - id else pinned.value + id
        pinned.value = now
        write { App.core.setSetting("pinned", now.joinToString("\n")) }
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
