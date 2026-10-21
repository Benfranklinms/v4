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
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.data.HomeGrid
import dev.benfranklin.itune.data.Recents
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
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

private typealias Tile = HomeGrid.Tile

/**
 * Home › recently played (Spotify-style 2×3): collections first (recently played, then library playlists, Liked
 * Songs, saved playlists / albums), songs only as filler; hidden with no history. Tap opens, long-press = the usual
 * menu, the playing tile shows a small equaliser (still under Reduce Motion).
 *
 * The tiles come from a process-level StateFlow ([HomeGrid.tiles]), so a tab return draws them in the first frame.
 * Motion (graphicsLayer only, values read in layer lambdas): a staggered spring rise on the first appearance per
 * process only, a spring glide when a collection jumps to the top, new tiles fade/scale in. Reduce Motion or
 * battery saver: crossfades only.
 */
@Composable
fun RecentGrid(s: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tiles by HomeGrid.tiles.collectAsState()
    if (tiles.isEmpty()) return
    val reduce = LocalReduceMotion.current
    val saver by rememberPowerSave()
    val calm = reduce || saver
    // entrance only the first time the grid is ever drawn in this process; tab returns draw it still
    val entrance = remember { !HomeGrid.entered }
    val mounted = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) { HomeGrid.entered = true; mounted[0] = true }
    val active by Recents.active.collectAsState()
    val playing by Playback.playing.collectAsState()
    val current by Playback.current.collectAsState()
    val songs = remember(tiles) { tiles.mapNotNull { it.track } }
    Layout(
        content = {
            tiles.forEachIndexed { i, t ->
                key(t.key) {
                    val on = playing && (if (t.origin != null) active == t.key else active == null && current?.id == t.track?.id)
                    RecentTile(
                        t, on, Modifier.gridMotion(i, entrance, mounted, calm),
                        onOpen = { open(s, ctx, t, songs) },
                        onMenu = menu(s, t),
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth().testTag("recent_grid"),
    ) { ms, cs ->
        val gap = 8.dp.roundToPx()
        val w = ((cs.maxWidth - gap) / 2).coerceAtLeast(0)
        val h = 56.dp.roundToPx()
        val ps = ms.map { it.measure(Constraints.fixed(w, h)) }
        val rows = (ps.size + 1) / 2
        layout(cs.maxWidth, rows * h + (rows - 1).coerceAtLeast(0) * gap) {
            ps.forEachIndexed { i, p -> p.place((i % 2) * (w + gap), (i / 2) * (h + gap)) }
        }
    }
}

private val riseSpec = spring<Float>(dampingRatio = 0.82f, stiffness = 320f)
private val glideSpec = spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = Offset(0.5f, 0.5f))
private val fadeSpec = tween<Float>(150)

/**
 * Per-tile motion, all in graphicsLayer: [entrance] = first appearance (staggered, first 8 tiles only), a tile that
 * appears later (a new collection) fades/scales in, and a tile whose slot changes glides there from its old slot.
 */
private fun Modifier.gridMotion(index: Int, entrance: Boolean, mounted: BooleanArray, calm: Boolean): Modifier = composed {
    val late = mounted[0]
    val a = remember { Animatable(if ((entrance && index < 8) || late) 0f else 1f) }
    val off = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) {
            if (calm) a.animateTo(1f, fadeSpec)
            else { if (!late) delay(index * 45L); a.animateTo(1f, riseSpec) }
        }
    }
    this
        .onPlaced { lc ->
            val p = lc.positionInParent()
            val prev = last
            last = p
            if (prev != null && prev != p) {
                if (calm) scope.launch { a.snapTo(0f); a.animateTo(1f, fadeSpec) }
                else scope.launch { off.snapTo(off.value + (prev - p)); off.animateTo(Offset.Zero, glideSpec) }
            }
        }
        .graphicsLayer {
            val k = a.value
            alpha = k.coerceIn(0f, 1f)
            if (!calm) {
                val sc = 0.94f + 0.06f * k
                scaleX = sc; scaleY = sc
                translationY = (1f - k) * 14.dp.toPx() + off.value.y
                translationX = off.value.x
            }
        }
}

private fun open(s: AppState, ctx: android.content.Context, t: Tile, songs: List<Track>) {
    val o = t.origin
    when {
        o == null -> t.track?.let { tr -> val i = songs.indexOfFirst { it.id == tr.id }.coerceAtLeast(0); Playback.play(ctx, songs.ifEmpty { listOf(tr) }, i, "Recently played") }
        o.kind == "album" -> s.push(Dest.Album(o.id, Peek(o.title, o.subtitle, o.thumb)))
        o.kind == "playlist" || o.kind == "liked" -> s.push(Dest.Playlist(o.id, Peek(o.title, o.subtitle, o.thumb)))
        o.tracks.isNotEmpty() -> s.push(Dest.Mix(if (o.kind == "radio") o.id else o.title, o.tracks))
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
