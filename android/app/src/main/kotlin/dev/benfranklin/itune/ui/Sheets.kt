package dev.benfranklin.itune.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
    val scrim by animateFloatAsState(if (open) 0.55f else 0f, tween(260), label = "scrim")
    if (scrim > 0.01f) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim / 0.55f }.background(Color.Black.copy(alpha = 0.55f)).plainClick { s.sheet = null })
    }
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = open,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it } + fadeIn(tween(120)),
            exit = slideOutVertically(spring(stiffness = 600f)) { it } + fadeOut(tween(160)),
        ) {
            val current = remember { mutableStateOf(s.sheet) }
            if (s.sheet != null) current.value = s.sheet
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = drag }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.surf2)
                    .navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(onDragEnd = { if (drag > 160f) s.sheet = null; drag = 0f }, onDragCancel = { drag = 0f }) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.surf4)) }
                AnimatedContent(current.value, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "sheet") { sh ->
                    when (sh) {
                        is Sheet.TrackMenu -> TrackMenu(s, sh.track)
                        Sheet.Queue -> QueueSheet(s)
                        is Sheet.Update -> UpdateSheet(s, sh.info)
                        is Sheet.AddToPlaylist -> AddToPlaylist(s, sh.track)
                        Sheet.Speed -> SpeedSheet(s)
                        Sheet.WhyThisSong -> WhySheet(s)
                        null -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, i: Int, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().enter(i, 25).press(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.txt, modifier = Modifier.size(20.dp))
        Text(label, style = T.ui(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
private fun TrackMenu(s: AppState, t: Track) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var liked by remember { mutableStateOf(App.core.isLiked(t.id)) }
    Column {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.thumb, 52.dp, 8.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(t.title, style = T.ui(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artists, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        MenuRow(Icons.Rounded.PlaylistPlay, "Play next", 0) { Playback.playNext(ctx, t); s.sheet = null; s.toast("Playing next") }
        MenuRow(Icons.Rounded.QueueMusic, "Add to queue", 1) { Playback.addToQueue(ctx, t); s.sheet = null; s.toast("Added to queue") }
        MenuRow(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked" else "Like", 2) {
            liked = !liked
            App.core.setLiked(t, liked)
        }
        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", 3) { s.sheet = Sheet.AddToPlaylist(t) }
        if (!t.id.startsWith("local:")) {
            MenuRow(Icons.Rounded.Radio, "Start radio", 4) {
                s.sheet = null
                scope.launch {
                    val r = runCatching { App.core.radio(t.id) }.getOrDefault(emptyList())
                    Playback.play(ctx, listOf(t) + r, 0, "${t.title} radio")
                }
            }
            MenuRow(Icons.Rounded.Download, "Download", 5) {
                s.sheet = null
                s.toast("Downloading…")
                scope.launch { runCatching { App.core.download(t) }.onSuccess { s.toast("Downloaded") }.onFailure { s.toast("Download failed") } }
            }
        }
        t.artistId?.let { a -> MenuRow(Icons.Rounded.Person, "Go to artist", 6) { s.sheet = null; s.push(Dest.Artist(a)) } }
        t.albumId?.let { a -> MenuRow(Icons.Rounded.Album, "Go to album", 7) { s.sheet = null; s.push(Dest.Album(a)) } }
    }
}

@Composable
private fun AddToPlaylist(s: AppState, t: Track) {
    val c = LocalColors.current
    val lists = remember { runCatching { App.core.playlists() }.getOrDefault(emptyList()).filter { it.source != "yt" } }
    Column {
        Text("Add to playlist", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        MenuRow(Icons.Rounded.Add, "New playlist", 0) {
            App.core.createPlaylist(t.title.take(24), listOf(t))
            s.sheet = null
            s.toast("Playlist created")
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(lists, key = { _, p -> p.id }) { i, p ->
                Row(Modifier.fillMaxWidth().enter(i + 1).press(scaleTo = 0.98f) { App.core.addToPlaylist(p.id, t); s.sheet = null; s.toast("Added to ${p.title}") }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    val q by Playback.queue.collectAsState()
    val st = q ?: return
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(st.index.coerceAtLeast(0)) }
    Column {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text("Autoplay", style = T.ui(12.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(end = 8.dp))
            var ap by remember { mutableStateOf(App.prefs.autoplay.value) }
            dev.benfranklin.itune.ui.components.Toggle(ap) { ap = it; App.prefs.autoplay.value = it; App.core.setAutoplay(it) }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), state = list) {
            itemsIndexed(st.tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                if (i == st.autoplayFrom && st.autoplayFrom >= 0) {
                    Text("FROM RADIO · TUNED TO YOUR TASTE", style = T.ui(10.sp, FontWeight.Bold, c.t3, 1.sp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
                val cur = i == st.index
                Row(
                    Modifier.fillMaxWidth().animateItem().press(scaleTo = 0.98f) { Playback.jump(i) }
                        .background(if (cur) c.surf3 else Color.Transparent).padding(horizontal = 24.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.thumb, 40.dp, 6.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(t.title, style = T.ui(14.sp, FontWeight.SemiBold, if (cur) c.accentUi else if (i < st.index) c.t3 else c.txt), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i > st.index) {
                        Icon(Icons.Rounded.Close, null, tint = c.t3, modifier = Modifier.size(36.dp).padding(9.dp).press { Playback.remove(i) })
                    }
                }
            }
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
private fun WhySheet(s: AppState) {
    val c = LocalColors.current
    val t by Playback.current.collectAsState()
    val tr = t ?: return
    val why = remember(tr.id) { App.core.isaWhy(tr) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Ask Isa · Why this song?", style = T.ui(17.sp, FontWeight.Bold))
        Text(why, style = T.ui(14.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
        dev.benfranklin.itune.ui.screens.IsaPill(s)
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
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.surf4).press {
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
