package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.data.Recents
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Peek
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.delay

/** One tile of the grid: a collection, or a recent song filling the gaps. */
private data class Tile(val origin: Recents.Origin?, val track: Track?) {
    val title get() = origin?.title ?: track?.title ?: ""
    val thumb get() = origin?.thumb ?: track?.thumb ?: ""
    val key get() = origin?.key ?: ("t:" + (track?.id ?: ""))
}

/**
 * Home › recently played (Spotify-style 2×3): the 6 newest collections played (albums, playlists, Liked Songs,
 * mixes, radio), filled with recent songs; hidden with no history. Tap opens, long-press = the usual menu, the
 * playing tile shows a small equaliser (still under Reduce Motion). Updates live after every play.
 */
@Composable
fun RecentGrid(s: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Recents.load() }
    val cols by Recents.items.collectAsState()
    val plays by Recents.plays.collectAsState()
    val current by Playback.current.collectAsState()
    var songs by remember { mutableStateOf<List<Track>>(emptyList()) }
    // recent songs from play history (core, IO); re-read after plays once the core has logged them
    LaunchedEffect(plays, current?.id) {
        if (plays > 0) delay(1500)
        io { App.core.recent(12u) }?.let { songs = it }
    }
    val tiles = remember(cols, songs) {
        val out = cols.take(6).map { Tile(it, null) }.toMutableList()
        val seen = HashSet<String>()
        for (t in songs) {
            if (out.size >= 6) break
            if (seen.add(t.id)) out += Tile(null, t)
        }
        out.toList()
    }
    if (tiles.isEmpty()) return
    val active by Recents.active.collectAsState()
    val playing by Playback.playing.collectAsState()
    Column(modifier.fillMaxWidth().testTag("recent_grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    val on = playing && (if (t.origin != null) active == t.key else active == null && current?.id == t.track?.id)
                    RecentTile(t, on, Modifier.weight(1f),
                        onOpen = { open(s, ctx, t, songs) },
                        onMenu = menu(s, t))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun open(s: AppState, ctx: android.content.Context, t: Tile, songs: List<Track>) {
    val o = t.origin
    when {
        o == null -> t.track?.let { tr -> val i = songs.indexOfFirst { it.id == tr.id }.coerceAtLeast(0); Playback.play(ctx, songs.ifEmpty { listOf(tr) }, i, "Recently played") }
        o.kind == "album" -> s.push(Dest.Album(o.id, Peek(o.title, o.subtitle, o.thumb)))
        o.kind == "playlist" || o.kind == "liked" -> s.push(Dest.Playlist(o.id, Peek(o.title, o.subtitle, o.thumb)))
        o.tracks.isNotEmpty() -> s.push(Dest.Mix(o.title, o.tracks))
        else -> s.toast("Couldn't open ${o.title}")
    }
}

/** The existing context menus: album / playlist / song. Liked Songs, mixes and radio have none (null). */
private fun menu(s: AppState, t: Tile): (() -> Unit)? {
    val o = t.origin
    if (o == null) {
        val tr = t.track ?: return null
        return { s.sheet = Sheet.TrackMenu(tr) }
    }
    if (o.kind == "album") {
        val al = dev.benfranklin.itune.core.Album(o.id, o.title, o.subtitle, null, o.thumb, "Album")
        return { s.sheet = Sheet.AlbumMenu(al) }
    }
    if (o.kind == "playlist") {
        val local = o.id.startsWith("local:") || o.id.startsWith("import:") || o.id.startsWith("isa:")
        val pl = dev.benfranklin.itune.core.Playlist(o.id, o.title, o.subtitle, o.thumb, null, if (local) "local" else "yt")
        return { s.sheet = Sheet.PlaylistMenu(pl) }
    }
    return null
}

@Composable
private fun RecentTile(t: Tile, on: Boolean, modifier: Modifier, onOpen: () -> Unit, onMenu: (() -> Unit)?) {
    val c = LocalColors.current
    Row(
        modifier.height(56.dp).glass(RoundedCornerShape(16.dp), dark = 0.36f, sheen = 0.07f, rim = 0.55f)
            .press(scaleTo = 0.97f, onLongClick = onMenu, onClick = onOpen).testTag("recent_tile"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.origin?.kind == "liked" && t.thumb.isEmpty()) {
            Box(Modifier.padding(start = 6.dp).size(44.dp).clip(RoundedCornerShape(8.dp)).background(c.surf3), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, tint = c.txt, modifier = Modifier.size(20.dp))
            }
        } else {
            Artwork(t.thumb, 44.dp, 8.dp, modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            t.title, style = T.ui(13.5.sp, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = if (on) 4.dp else 10.dp).testTag("recent_tile_title"),
        )
        if (on) Equalizer(Modifier.padding(end = 10.dp).size(14.dp).testTag("recent_eq"))
    }
}

/** Three small bars; animated while playing, still under Reduce Motion. Values are read while drawing only. */
@Composable
private fun Equalizer(modifier: Modifier) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val a: androidx.compose.runtime.State<Float>
    if (!reduce) {
        val inf = rememberInfiniteTransition(label = "eq")
        a = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "eqp")
    } else a = remember { mutableFloatStateOf(0.35f) }
    Canvas(modifier) {
        val w = size.width / 5
        for (i in 0..2) {
            val ph = (a.value + i * 0.33f) % 1f
            val k = if (reduce) listOf(0.55f, 0.9f, 0.4f)[i] else 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(ph * Math.PI)).toFloat()
            val h = size.height * k
            drawRoundRect(c.txt, topLeft = Offset(i * 2 * w, size.height - h), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
        }
    }
}
