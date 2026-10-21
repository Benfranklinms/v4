package dev.benfranklin.itune.data

import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.core.Track
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Home › recently played 2×3, held for the whole process: Home reads [tiles] synchronously, so coming back to the
 * tab draws the grid in its first frame (no wait on the core, no pop-in, no layout jump).
 *
 * Collections first (Ben, 0.2.10: "playlist coming here is better and not music"): recently played collections,
 * then the library's playlists, Liked Songs (when it has songs), saved playlists and albums. Songs only fill a
 * grid that still has fewer than 6, and never repeat a collection (same key, or same clean title / artwork).
 */
object HomeGrid {
    const val SIZE = 6

    /** A collection, or a recent song filling the gaps. */
    data class Tile(val origin: Recents.Origin?, val track: Track?) {
        val title: String get() = origin?.title ?: track?.title ?: ""
        val thumb: String get() = origin?.thumb ?: track?.thumb ?: ""
        val key: String get() = origin?.key ?: ("t:" + (track?.id ?: ""))
    }

    /** Pure: the 6 tiles from the parts (unit-tested). */
    fun compose(
        played: List<Recents.Origin>,
        playlists: List<Playlist>,
        liked: List<Track>,
        saved: List<Playlist>,
        albums: List<Album>,
        songs: List<Track>,
        hidden: Set<String> = emptySet(),
    ): List<Tile> {
        val out = ArrayList<Tile>(SIZE)
        val keys = HashSet<String>()
        val names = HashSet<String>()
        val thumbs = HashSet<String>()
        fun add(o: Recents.Origin): Boolean {
            if (out.size >= SIZE || o.id in hidden) return false
            val n = Recents.norm(o.title)
            if (!keys.add(o.key)) return false
            if (n.isNotEmpty() && n in names) return false
            if (o.thumb.isNotEmpty() && o.thumb in thumbs && o.kind != "liked") return false
            if (n.isNotEmpty()) names += n
            if (o.thumb.isNotEmpty()) thumbs += o.thumb
            out += Tile(o, null)
            return true
        }
        played.forEach { add(it) }
        playlists.filter { it.id != "liked" }.forEach { add(Recents.Origin("playlist", it.id, it.title, it.subtitle, it.thumb)) }
        if (liked.isNotEmpty()) add(Recents.Origin("liked", "liked", "Liked Songs", "${liked.size} songs", ""))
        saved.forEach { add(Recents.Origin("playlist", it.id, it.title, it.subtitle, it.thumb)) }
        albums.forEach { add(Recents.Origin("album", it.id, it.title, it.artists, it.thumb)) }
        for (t in songs) {
            if (out.size >= SIZE) break
            val n = Recents.norm(t.title)
            // a song never repeats a collection tile (or another song): same id, clean title or artwork
            if (!keys.add("t:" + t.id)) continue
            if (n.isNotEmpty() && n in names) continue
            if (t.thumb.isNotEmpty() && t.thumb in thumbs) continue
            if (n.isNotEmpty()) names += n
            if (t.thumb.isNotEmpty()) thumbs += t.thumb
            out += Tile(null, t)
        }
        return out
    }

    private fun now() = compose(
        Recents.items.value, Library.playlists.value.orEmpty(), Library.likedTracks.value.orEmpty(),
        Library.savedPlaylists.value, Library.albums.value, Recents.songs.value, Library.hidden.value,
    )

    /** Live tiles; seeded from whatever is in memory, recomputed off the main thread when a part changes. */
    val tiles: StateFlow<List<Tile>> by lazy {
        Recents.load()
        combine(
            combine(Recents.items, Library.playlists, Library.likedTracks) { a, b, c -> Triple(a, b.orEmpty(), c.orEmpty()) },
            combine(Library.savedPlaylists, Library.albums, Library.hidden) { a, b, c -> Triple(a, b, c) },
            Recents.songs,
        ) { x, y, songs -> compose(x.first, x.second, x.third, y.first, y.second, songs, y.third) }
            .stateIn(App.scope, SharingStarted.Eagerly, now())
    }

    /** True once the grid has played its entrance: a tab return draws it still (instant). */
    @Volatile var entered = false
}
