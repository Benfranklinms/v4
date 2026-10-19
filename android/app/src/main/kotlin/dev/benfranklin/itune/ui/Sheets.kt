package dev.benfranklin.itune.ui

import dev.benfranklin.itune.ui.components.glass
import androidx.compose.ui.platform.testTag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import androidx.compose.foundation.lazy.items
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.plainClick
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import dev.benfranklin.itune.update.Updater
import kotlinx.coroutines.launch

@Composable
fun SheetHost(s: AppState) {
    val c = LocalColors.current
    val open = s.sheet != null
    // a menu sheet never sits under the keyboard: close it when a non-text sheet opens
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(s.sheet) {
        val sh = s.sheet
        if (sh != null && sh !is Sheet.NamePlaylist) { focus.clearFocus(); kb?.hide() }
    }
    val scrim by animateFloatAsState(if (open) 0.55f else 0f, tween(260), label = "scrim")
    if (scrim > 0.01f) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim / 0.55f }.background(Color.Black.copy(alpha = 0.55f)).plainClick { s.sheet = null })
    }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val reduceM = dev.benfranklin.itune.ui.theme.LocalReduceMotion.current
    LaunchedEffect(open) { if (open) drag.snapTo(0f) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = open,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it } + fadeIn(tween(120)),
            exit = slideOutVertically(spring(stiffness = 600f)) { it } + fadeOut(tween(160)),
        ) {
            val current = remember { mutableStateOf(s.sheet) }
            if (s.sheet != null) current.value = s.sheet
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = drag.value }
                    .glass(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), dark = 0.72f, sheen = 0.06f, rim = 0.65f)
                    .navigationBarsPadding().padding(bottom = 12.dp)
                    .testTag("sheet"),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag.value > 160f) s.sheet = null
                                else dragScope.launch { drag.animateTo(0f, dev.benfranklin.itune.ui.components.glassSpring(reduceM)) }
                            },
                            onDragCancel = { dragScope.launch { drag.animateTo(0f, dev.benfranklin.itune.ui.components.glassSpring(reduceM)) } },
                        ) { _, d -> dragScope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(36.dp, 4.5.dp).clip(RoundedCornerShape(2.25.dp)).background(c.t3)) }
                AnimatedContent(current.value, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "sheet") { sh ->
                    when (sh) {
                        is Sheet.TrackMenu -> TrackMenu(s, sh.track, sh.playlistId, sh.position, sh.nowPlaying, sh.queueIndex)
                        Sheet.Queue -> QueueSheet(s)
                        is Sheet.Update -> UpdateSheet(s, sh.info)
                        is Sheet.AddToPlaylist -> AddToPlaylist(s, sh.track)
                        Sheet.Speed -> SpeedSheet(s)
                        is Sheet.PlaylistMenu -> PlaylistMenu(s, sh.playlist)
                        is Sheet.AlbumMenu -> AlbumMenu(s, sh.album)
                        is Sheet.NamePlaylist -> NamePlaylistSheet(s, sh.tracks, sh.edit)
                        Sheet.Account -> AccountSheet(s)
                        Sheet.SleepTimer -> SleepTimerSheet(s)
                        null -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, label: String, i: Int, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().enter(i, 25).press(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.txt, modifier = Modifier.size(20.dp))
        Text(label, style = T.ui(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
private fun TrackMenu(s: AppState, t: Track, playlistId: String? = null, position: Int? = null, fromNowPlaying: Boolean = false, queueIndex: Int? = null) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val likedIds by dev.benfranklin.itune.data.Likes.ids.collectAsState()
    val liked = t.id in likedIds
    Column {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.thumb, 52.dp, 8.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(t.title, style = T.ui(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artists, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (fromNowPlaying) {
            val sp by Playback.speed.collectAsState()
            MenuRow(Icons.Rounded.Speed, "Playback speed · ${if (sp == 1f) "1×" else "%.2g×".format(sp)}", 0) { s.sheet = Sheet.Speed }
            MenuRow(Icons.Rounded.Bedtime, "Sleep timer", 0) { s.sheet = Sheet.SleepTimer }
        }
        if (queueIndex != null) MenuRow(Icons.Rounded.RemoveCircleOutline, "Remove from queue", 0) { Playback.remove(queueIndex); s.sheet = null }
        if (!fromNowPlaying) MenuRow(Icons.Rounded.PlaylistPlay, "Play next", 0) { Playback.playNext(ctx, t); s.sheet = null; s.toast("Playing next") }
        if (!fromNowPlaying) MenuRow(Icons.Rounded.QueueMusic, "Add to queue", 1) { Playback.addToQueue(ctx, t); s.sheet = null; s.toast("Added to queue") }
        MenuRow(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked" else "Like", 2) {
            val now = dev.benfranklin.itune.data.Likes.toggle(t)
            s.toast(if (now) "Added to Liked Songs" else "Removed from Liked Songs")
        }
        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", 3) { s.sheet = Sheet.AddToPlaylist(t) }
        if (!t.id.startsWith("local:")) {
            MenuRow(Icons.Rounded.Radio, "Start radio", 4) {
                s.sheet = null
                scope.launch {
                    val r = dev.benfranklin.itune.data.io { App.core.radio(t.id) }.orEmpty()
                    Playback.play(ctx, listOf(t) + r, 0, "${t.title} radio")
                }
            }
            var isDl by remember(t.id) { mutableStateOf(false) }
            LaunchedEffect(t.id) { isDl = dev.benfranklin.itune.data.io { App.core.isDownloaded(t.id) } ?: false }
            if (isDl) MenuRow(Icons.Rounded.RemoveCircleOutline, "Remove download", 5) {
                s.sheet = null
                s.undoable("Removed download", apply = {}, undo = {}, commit = { dev.benfranklin.itune.data.Bg.run { App.core.removeDownload(t.id) } })
            } else MenuRow(Icons.Rounded.Download, "Download", 5) {
                s.sheet = null
                s.toast("Downloading…")
                App.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    dev.benfranklin.itune.data.ioResult { App.core.download(t) }.onSuccess { s.toast("Downloaded") }.onFailure { s.toast("Couldn't download. Check your connection.") }
                }
            }
        }
        t.artistId?.let { a -> MenuRow(Icons.Rounded.Person, "Go to artist", 6) { s.sheet = null; s.push(Dest.Artist(a)) } }
        t.albumId?.let { a -> MenuRow(Icons.Rounded.Album, "Go to album", 7) { s.sheet = null; s.push(Dest.Album(a)) } }
        MenuRow(Icons.Rounded.Share, "Share", 8) { s.sheet = null; shareText(ctx, t.title + " · " + t.artists, "https://music.youtube.com/watch?v=" + t.id) }
        if (playlistId != null && position != null) {
            MenuRow(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist", 9) {
                s.sheet = null
                PlaylistEdits.removeTrack(s, playlistId, position, t)
            }
        }
    }
}

@Composable
private fun AddToPlaylist(s: AppState, t: Track) {
    val c = LocalColors.current
    val all by dev.benfranklin.itune.data.Library.playlists.collectAsState()
    LaunchedEffect(Unit) { if (all == null) dev.benfranklin.itune.data.Library.refresh() }
    val lists = all.orEmpty().filter { it.source != "yt" }
    Column {
        Text("Add to playlist", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        MenuRow(Icons.Rounded.Add, "New playlist", 0) {
            s.sheet = Sheet.NamePlaylist(listOf(t))
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(lists, key = { _, p -> p.id }) { i, p ->
                Row(Modifier.fillMaxWidth().enter(i + 1).press(scaleTo = 0.98f) {
                    dev.benfranklin.itune.data.Library.replace(p.copy(trackCount = (p.trackCount ?: 0uL) + 1uL))
                    dev.benfranklin.itune.data.Bg.run { App.core.addToPlaylist(p.id, t) }
                    s.sheet = null
                    s.toast("Added to ${p.title}")
                }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(p.thumb, 40.dp, 6.dp)
                    Text(p.title, style = T.ui(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 14.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${p.trackCount ?: 0}", style = T.num(11.sp, color = c.t3))
                }
            }
        }
    }
}

@Composable
private fun QueueSheet(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val q by Playback.queue.collectAsState()
    val st = q ?: return
    val reduce = dev.benfranklin.itune.ui.theme.LocalReduceMotion.current
    var tab by remember { mutableStateOf(0) }
    var recent by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(Unit) { recent = dev.benfranklin.itune.data.io { App.core.recent(40u) }.orEmpty() }
    val list = rememberLazyListState()
    var ap by remember { mutableStateOf(App.prefs.autoplay.value) }
    val likedIds by dev.benfranklin.itune.data.Likes.ids.collectAsState()
    val lens = androidx.compose.animation.core.animateFloatAsState(tab.toFloat(), dev.benfranklin.itune.ui.components.glassSpring(reduce), label = "qlens")
    Column(Modifier.testTag("queue_sheet")) {
        // tabs: Queue | Recently played with a sliding mono underline
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val half = maxWidth / 2
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Queue", "Recently played").forEachIndexed { i, l ->
                    Box(Modifier.weight(1f).fillMaxHeight().press(scaleTo = 0.96f) { tab = i }.testTag("queue_tab_$i"), contentAlignment = Alignment.Center) {
                        Text(l, style = T.ui(15.sp, if (tab == i) FontWeight.Bold else FontWeight.SemiBold, if (tab == i) c.txt else c.t3))
                    }
                }
            }
            Box(Modifier.padding(top = 40.dp).fillMaxWidth().height(0.6.dp).background(c.line3))
            Box(
                Modifier.padding(top = 39.dp).width(half).height(3.dp).graphicsLayer { translationX = lens.value * half.toPx() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(56.dp).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(c.acc)) }
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 1) {
            LazyColumn(Modifier.heightIn(max = 540.dp)) {
                if (recent.isEmpty()) item { Text("Songs you play show up here.", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(24.dp)) }
                itemsIndexed(recent, key = { i, t -> "h$i${t.id}" }) { i, t ->
                    QRow(t, false, onClick = { Playback.play(ctx, recent, i, "Recently played") }) {
                        Icon(Icons.Rounded.MoreHoriz, "More", tint = c.t2, modifier = Modifier.size(40.dp).press { s.sheet = Sheet.TrackMenu(t) }.padding(10.dp))
                    }
                }
            }
            return@Column
        }
        val apFrom = if (st.autoplayFrom >= 0) st.autoplayFrom else st.tracks.size
        LaunchedEffect(Unit) { list.scrollToItem(0) }
        LazyColumn(Modifier.heightIn(max = 540.dp), state = list) {
            st.tracks.getOrNull(st.index)?.let { cur ->
                item(key = "h_now") { QHeader("Now playing") }
                item(key = "now") {
                    QRow(cur, true, onClick = { Playback.toggle() }) {
                        val lk = cur.id in likedIds
                        Icon(if (lk) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (lk) "Unlike" else "Like", tint = c.txt, modifier = Modifier.size(40.dp).press { dev.benfranklin.itune.data.Likes.toggle(cur) }.padding(10.dp))
                    }
                }
            }
            val user = (st.index + 1 until apFrom).toList()
            if (user.isNotEmpty()) {
                item(key = "h_user") {
                    QHeader("Next in queue", "Clear") { user.reversed().forEach { Playback.remove(it) } }
                }
                items(user.size, key = { k -> "u${user[k]}-${st.tracks[user[k]].id}" }) { k ->
                    val i = user[k]
                    Box(Modifier.animateItem()) {
                        QRow(st.tracks[i], false, onClick = { Playback.jump(i) }, onLong = { s.sheet = Sheet.TrackMenu(st.tracks[i], queueIndex = i) }) {
                            DragHandle(i, st.index + 1, apFrom - 1)
                        }
                    }
                }
            }
            item(key = "h_auto") {
                Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Next up · Autoplay", style = T.ui(12.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.weight(1f))
                    Box(Modifier.testTag("autoplay_toggle")) {
                        dev.benfranklin.itune.ui.components.Toggle(ap) { v -> ap = v; App.prefs.autoplay.value = v; dev.benfranklin.itune.data.Bg.run { App.core.setAutoplay(v) } }
                    }
                }
            }
            if (ap) {
                val auto = (maxOf(apFrom, st.index + 1) until st.tracks.size).toList()
                if (auto.isEmpty()) item(key = "auto_empty") { Text("Similar songs appear here as the queue runs low.", style = T.ui(12.5.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(auto.size, key = { k -> "a${auto[k]}-${st.tracks[auto[k]].id}" }) { k ->
                    val i = auto[k]
                    Box(Modifier.animateItem()) {
                        QRow(st.tracks[i], false, onClick = { Playback.jump(i) }, onLong = { s.sheet = Sheet.TrackMenu(st.tracks[i], queueIndex = i) }) {
                            Icon(Icons.Rounded.MoreHoriz, "More", tint = c.t2, modifier = Modifier.size(40.dp).press { s.sheet = Sheet.TrackMenu(st.tracks[i], queueIndex = i) }.padding(10.dp))
                        }
                    }
                }
            }
            item(key = "q_save") {
                Text(
                    "Save queue as playlist", style = T.ui(13.sp, FontWeight.SemiBold, c.t2),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).press { s.sheet = Sheet.NamePlaylist(st.tracks) }.testTag("queue_save"),
                )
            }
        }
    }
}

@Composable
private fun QHeader(t: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, style = T.ui(12.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = T.ui(12.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.press { onAction?.invoke() }.padding(4.dp).testTag("queue_clear"))
    }
}

@Composable
private fun QRow(t: Track, current: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).press(scaleTo = 0.98f, onLongClick = onLong, onClick = onClick).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(t.thumb, 46.dp, 9.dp)
            if (current) Box(Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                EqBars(Playback.playing.collectAsState().value)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(t.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artists, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        dev.benfranklin.itune.ui.components.fmtDur(t.durationS.toInt()).takeIf { it.isNotEmpty() }?.let { Text(it, style = T.num(11.5.sp, color = c.t3), modifier = Modifier.padding(horizontal = 6.dp)) }
        trailing()
    }
}

/** Playing indicator: four mono bars that bounce while music plays (drawn, no recomposition per frame). */
@Composable
private fun EqBars(playing: Boolean) {
    val c = LocalColors.current
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "eq")
    val ph = inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "eqp")
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val hs = floatArrayOf(0.55f, 1f, 0.7f, 0.85f)
        val bw = size.width * 0.14f
        for (i in 0 until 4) {
            val k = if (playing) (0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((ph.value + i * 0.27f) * Math.PI.toFloat()))) else hs[i]
            val hh = size.height * k
            drawRoundRect(c.txt, androidx.compose.ui.geometry.Offset(i * size.width * 0.26f, size.height - hh), androidx.compose.ui.geometry.Size(bw, hh), androidx.compose.ui.geometry.CornerRadius(bw / 2))
        }
    }
}

@Composable
private fun SpeedSheet(s: AppState) {
    val sp by Playback.speed.collectAsState()
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("Playback speed", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f).forEach { v -> ChipButton("${v}×", selected = v == sp, mono = true) { Playback.setSpeed(v) } }
        }
    }
}

@Composable
private fun UpdateSheet(s: AppState, info: dev.benfranklin.itune.core.UpdateInfo) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val shown by animateFloatAsState(progress ?: 0f, spring(stiffness = 200f), label = "up")
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text("Update available", style = T.ui(13.sp, FontWeight.SemiBold, c.t3))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("iTune ", style = T.ui(24.sp, FontWeight.Bold))
            Text(info.version, style = T.num(24.sp, FontWeight.Medium))
        }
        if (info.notes.isNotBlank() && info.notes.trim() != info.version) {
            Text(info.notes.trim(), style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(top = 10.dp), maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        if (progress != null) {
            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = c.txt, trackColor = c.surf4, strokeCap = StrokeCap.Round)
            Text("${(shown * 100).toInt()}%", style = T.num(11.sp, color = c.t3), modifier = Modifier.padding(top = 6.dp))
        }
        error?.let { Text(it, style = T.ui(12.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(48.dp).glass(RoundedCornerShape(24.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).press {
                App.prefs.skippedVersion.value = info.version
                s.sheet = null
            }, contentAlignment = Alignment.Center) { Text("Later", style = T.ui(15.sp, FontWeight.SemiBold)) }
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.accentUi).press {
                if (progress != null) return@press
                progress = 0f
                error = null
                scope.launch {
                    val r = Updater.downloadAndInstall(ctx, info) { progress = it }
                    if (r != null) { error = r; progress = null }
                }
            }, contentAlignment = Alignment.Center) { Text(if (progress == null) "Update" else "Downloading…", style = T.ui(15.sp, FontWeight.Bold, c.onAccent)) }
        }
        @Suppress("UNUSED_EXPRESSION") Modifier.width(1.dp)
    }
}
