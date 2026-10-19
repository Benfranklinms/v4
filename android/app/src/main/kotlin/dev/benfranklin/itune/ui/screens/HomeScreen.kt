package dev.benfranklin.itune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.data.Resume
import dev.benfranklin.itune.data.ioResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import dev.benfranklin.itune.core.HomeFeed
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.PulseMark
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.SectionTitle
import dev.benfranklin.itune.ui.components.TrackRow
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import java.util.TimeZone

object HomeCache {
    var feed: HomeFeed? = null
    var at = 0L
}

fun tzMin() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

@Composable
fun HomeScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(HomeCache.feed) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val resume = remember(feed) { Resume.load() }
    suspend fun load(force: Boolean) {
        if (!force && feed != null && System.currentTimeMillis() - HomeCache.at < 20 * 60_000) return
        loading = true
        ioResult { App.core.home(tzMin()) }
            .onSuccess { feed = it; HomeCache.feed = it; HomeCache.at = System.currentTimeMillis(); error = null }
            .onFailure { if (feed == null) error = "Couldn't load your home feed. Check your connection." }
        loading = false
    }
    LaunchedEffect(Unit) {
        // instant: the last saved feed from the core first, then refresh in the background (same keys: no jump)
        if (feed == null) io { App.core.homeCached(tzMin()) }?.let { if (feed == null) { feed = it; HomeCache.feed = it } }
        load(false)
    }
    LaunchedEffect(feed) {
        val f = feed ?: return@LaunchedEffect
        val loader = coil.Coil.imageLoader(ctx)
        val px = (138 * ctx.resources.displayMetrics.density).toInt()
        val urls = (f.shelves.firstOrNull()?.let { sh -> sh.items.map { it.thumb } }.orEmpty() + f.mixes.map { it.thumb }).filter { it.isNotEmpty() }.take(12)
        urls.forEach { u -> loader.enqueue(coil.request.ImageRequest.Builder(ctx).data(u).size(px, px).build()) }
    }
    val acctName by App.prefs.userName.state.collectAsState()
    val callName by App.prefs.callName.state.collectAsState()
    val name = callName.trim().ifEmpty { acctName }
    val account by dev.benfranklin.itune.data.Account.info.collectAsState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(Modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PulseMark(Modifier.size(22.dp))
                Text("iTune", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(start = 9.dp).weight(1f))
                Icon(Icons.Rounded.Settings, "Settings", tint = c.t2, modifier = Modifier.size(40.dp).press { s.push(Dest.Settings) }.padding(10.dp).testTag("home_settings"))
                Box(Modifier.padding(start = 4.dp).size(32.dp).press { s.sheet = Sheet.Account }.glass(CircleShape, dark = 0.5f, sheen = 0.1f).testTag("home_avatar"), contentAlignment = Alignment.Center) {
                    val av = account?.avatar
                    if (!av.isNullOrEmpty()) Artwork(av, 30.dp, circle = true)
                    else Text(name.take(1).uppercase(), style = T.ui(13.sp, FontWeight.Bold))
                }
            }
        }
        item {
            val greet = feed?.greeting ?: "Hello"
            RiseText("$greet, $name", T.ui(22.sp, FontWeight.Bold), Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp))
        }
        item { IsaCommandCard(s, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) }
        resume?.let { r ->
            item {
                Spacer(Modifier.height(36.dp))
                SectionTitle("Continue listening")
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), dark = 0.42f)
                        .press(scaleTo = 0.98f) { Playback.playFrom(ctx, r.track, r.positionMs) }.padding(10.dp).testTag("continue"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(r.track.thumb, 52.dp, 8.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(r.track.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.track.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val f = if (r.durationMs > 0) (r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) else 0f
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surf4)) {
                            Box(Modifier.fillMaxWidth(f).height(3.dp).background(c.txt))
                        }
                    }
                    Box(Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape).background(c.txt), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Resume", tint = c.bg, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        val f = feed
        if (f == null) {
            if (error != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "", style = T.ui(13.sp, FontWeight.Medium, c.t3))
                        Row(
                            Modifier.padding(top = 14.dp).height(40.dp).glass(RoundedCornerShape(20.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).press { scope.launch { error = null; load(true) } }.padding(horizontal = 18.dp).testTag("home_retry"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = c.txt, modifier = Modifier.size(16.dp))
                            Text("Try again", style = T.ui(13.5.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                item { Spacer(Modifier.height(26.dp)); SkeletonTitle(); Shimmers() }
                item { SkeletonTitle(); SkeletonRows() }
                item { SkeletonTitle(); Shimmers() }
            }
        } else {
            if (f.recent.isNotEmpty()) {
                item { Spacer(Modifier.height(44.dp)); SectionTitle("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                        itemsIndexed(f.recent.take(12), key = { i, t -> "r$i${t.id}" }) { i, t ->
                            Column(Modifier.width(160.dp).enter(i).press(scaleTo = 0.96f, onLongClick = { s.sheet = Sheet.TrackMenu(t) }) { Playback.play(ctx, f.recent, i, "Recently played") }) {
                                Artwork(t.thumb, 160.dp, 14.dp)
                                Text(t.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text(t.artists, style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (f.mixes.isNotEmpty()) {
                item { Spacer(Modifier.height(44.dp)); SectionTitle("Daily Mixes") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                        itemsIndexed(f.mixes, key = { k, v -> "$k:" + v.hashCode() }) { i, m ->
                            Column(Modifier.width(150.dp).enter(i).press(scaleTo = 0.96f) { s.push(Dest.Mix(m.title, m.tracks)) }) {
                                Box {
                                    Artwork(m.thumb, 150.dp, 16.dp)
                                    Text("%02d".format(i + 1), style = T.num(11.sp, FontWeight.Medium), modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(c.txt)
                                            .press { Playback.play(ctx, m.tracks, 0, m.title) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = c.bg, modifier = Modifier.size(18.dp)) }
                                }
                                Text(m.title, style = T.ui(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp))
                                Text(m.subtitle, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (f.onRepeat.size >= 4) {
                item { Spacer(Modifier.height(44.dp)); SectionTitle("On Repeat", action = "Play") { Playback.play(ctx, f.onRepeat, 0, "On Repeat") } }
                item { TrackRail(s, f.onRepeat, "On Repeat") }
            }
            if (f.discover.size >= 6) {
                item { Spacer(Modifier.height(44.dp)); SectionTitle("Discover", action = "Play") { Playback.play(ctx, f.discover, 0, "Discover") } }
                item { TrackRail(s, f.discover, "Discover") }
            }
            f.shelves.forEachIndexed { si, sh ->
                item(key = "shelf$si") { Spacer(Modifier.height(44.dp)); SectionTitle(sh.title) }
                if (sh.layout == "songs") {
                    val tracks = sh.items.mapNotNull { it.track }
                    item(key = "shelfc$si") {
                        // Quick-picks style: columns of 4 rows, swiped sideways
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            itemsIndexed(tracks.chunked(4), key = { k, v -> "$k:" + v.hashCode() }) { ci, col ->
                                Column(Modifier.width(300.dp)) {
                                    col.forEachIndexed { k, t ->
                                        val idx = ci * 4 + k
                                        TrackRow(t, idx, onClick = { Playback.play(ctx, tracks, idx, sh.title) }, onMore = { s.sheet = Sheet.TrackMenu(t) })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item(key = "shelfc$si") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                            itemsIndexed(sh.items, key = { k, v -> "$k:" + v.hashCode() }) { i, it ->
                                val circle = it.kind == "artist"
                                Column(Modifier.width(150.dp).enter(i).press(scaleTo = 0.96f, onLongClick = { homeItemMenu(s, it) }) { openHomeItem(s, ctx, it, sh) }.testTag("shelf_item")) {
                                    Artwork(it.thumb, 150.dp, 16.dp, circle = circle)
                                    Text(it.title, style = T.ui(13.5.sp, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                                    Text(it.subtitle, style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (f.trending.isNotEmpty() && f.shelves.none { it.title.contains("chart", true) || it.title.contains("trending", true) }) {
                item { Spacer(Modifier.height(44.dp)); SectionTitle("Trending"); Spacer(Modifier.height(6.dp)) }
                itemsIndexed(f.trending.take(10), key = { k, v -> "$k:" + v.hashCode() }) { i, t ->
                    TrackRow(t, i, onClick = { Playback.play(ctx, f.trending, i, "Trending") }, onMore = { s.sheet = Sheet.TrackMenu(t) }, leadingNumber = i + 1)
                }
            }
        }
    }
    }
}

private fun openHomeItem(s: AppState, ctx: android.content.Context, it: dev.benfranklin.itune.core.HomeItem, sh: dev.benfranklin.itune.core.HomeShelf) {
    when (it.kind) {
        "album" -> s.push(Dest.Album(it.id))
        "artist" -> s.push(Dest.Artist(it.id))
        "playlist" -> s.push(Dest.Playlist(it.id))
        else -> {
            val tracks = sh.items.mapNotNull { x -> x.track }
            val i = tracks.indexOfFirst { t -> t.id == it.id }.coerceAtLeast(0)
            if (tracks.isNotEmpty()) Playback.play(ctx, tracks, i, sh.title)
        }
    }
}

private fun homeItemMenu(s: AppState, it: dev.benfranklin.itune.core.HomeItem) {
    when (it.kind) {
        "album" -> s.sheet = Sheet.AlbumMenu(dev.benfranklin.itune.core.Album(it.id, it.title, it.subtitle, null, it.thumb, "Album"))
        "playlist" -> s.sheet = Sheet.PlaylistMenu(dev.benfranklin.itune.core.Playlist(it.id, it.title, it.subtitle, it.thumb, null, "yt"))
        else -> it.track?.let { t -> s.sheet = Sheet.TrackMenu(t) }
    }
}

@Composable
private fun TrackRail(s: AppState, tracks: List<dev.benfranklin.itune.core.Track>, source: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
        itemsIndexed(tracks.take(20), key = { k, v -> "$k:" + v.hashCode() }) { i, t ->
            Column(Modifier.width(124.dp).enter(i).press(scaleTo = 0.96f, onLongClick = { s.sheet = Sheet.TrackMenu(t) }) { Playback.play(ctx, tracks, i, source) }) {
                Artwork(t.thumb, 124.dp, 10.dp)
                Text(t.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.artists, style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SkeletonTitle() {
    val c = LocalColors.current
    Box(Modifier.padding(start = 20.dp, top = 8.dp).width(140.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(c.surf3))
}

@Composable
fun SkeletonRows(n: Int = 4) {
    val c = LocalColors.current
    val a = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
    val alpha by a.animateFloat(0.35f, 0.8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        repeat(n) {
            Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(7.dp)).background(c.surf3.copy(alpha = alpha)))
                Column(Modifier.padding(start = 14.dp)) {
                    Box(Modifier.width(170.dp).height(11.dp).clip(RoundedCornerShape(5.dp)).background(c.surf3.copy(alpha = alpha)))
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(100.dp).height(9.dp).clip(RoundedCornerShape(5.dp)).background(c.surf3.copy(alpha = alpha * 0.8f)))
                }
            }
        }
    }
}

@Composable
fun IsaPill(s: AppState, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun go() {
        if (text.isNotBlank()) {
            s.push(Dest.Isa(text.trim()))
            text = ""
            focus.clearFocus()
        }
    }
    Row(
        modifier.fillMaxWidth().height(52.dp).glass(RoundedCornerShape(26.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).padding(start = 18.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = c.txt, modifier = Modifier.size(17.dp))
        Text("Isa", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.padding(start = 10.dp))
        Box(Modifier.padding(horizontal = 12.dp).width(0.75.dp).height(18.dp).background(c.line3))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Ask Isa…", style = T.ui(14.sp, FontWeight.Medium, c.t3))
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = T.ui(14.sp, FontWeight.Medium),
                cursorBrush = SolidColor(c.txt),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accentUi).press { go() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowUpward, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun Shimmers() {
    val c = LocalColors.current
    val a = androidx.compose.animation.core.rememberInfiniteTransition(label = "sh")
    val alpha by a.animateFloat(0.35f, 0.8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { Box(Modifier.size(138.dp).clip(RoundedCornerShape(12.dp)).background(c.surf3.copy(alpha = alpha))) }
    }
}
