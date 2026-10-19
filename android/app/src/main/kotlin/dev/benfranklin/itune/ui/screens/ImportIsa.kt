package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.ImportLink
import dev.benfranklin.itune.core.ImportResult
import dev.benfranklin.itune.core.IsaResult
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.Glow
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.RollingNumber
import dev.benfranklin.itune.ui.components.TrackRow
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.fmtDur
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.launch

@Composable
fun ImportScreen(s: AppState) {
    val c = LocalColors.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(ImportLink.pending ?: "") }
    var busy by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<ImportResult?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("matched") }
    val included = remember { mutableStateOf(setOf<Int>()) }
    fun run() {
        if (url.isBlank() || busy) return
        busy = true
        err = null
        scope.launch {
            runCatching { App.core.importPlaylist(url.trim()) }
                .onSuccess { r -> res = r; included.value = r.matches.indices.filter { r.matches[it].status == "matched" }.toSet() }
                .onFailure { err = it.message?.substringAfter(": ") ?: "Import failed" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (ImportLink.pending != null) { ImportLink.pending = null; run() } }
    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Import playlists")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Spotify", "Apple Music", "Paste link").forEachIndexed { i, l -> ChipButton(l, modifier = Modifier.enter(i), selected = i == 2) { } }
                }
            }
            item {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.surf).border(0.6.dp, c.line3, RoundedCornerShape(25.dp)).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (url.isEmpty()) Text("open.spotify.com/playlist/… or music.apple.com/…", style = T.ui(13.sp, FontWeight.Medium, c.t3), maxLines = 1)
                        BasicTextField(url, { url = it }, singleLine = true, textStyle = T.ui(13.sp, FontWeight.Medium), cursorBrush = SolidColor(c.txt), modifier = Modifier.fillMaxWidth())
                    }
                    Icon(Icons.Rounded.ContentPaste, null, tint = c.t2, modifier = Modifier.size(38.dp).padding(9.dp).press { clip.getText()?.text?.let { url = it } })
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentUi).press { run() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (busy) item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("Reading the playlist and matching every song…", style = T.ui(13.sp, FontWeight.Medium, c.t2))
                    LinearProgressIndicator(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.txt, trackColor = c.surf4, strokeCap = StrokeCap.Round)
                }
            }
            err?.let { e -> item { Text(e, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(24.dp)) } }
            res?.let { r ->
                val matched = r.matches.count { it.status == "matched" }
                val review = r.matches.count { it.status == "review" }
                val missing = r.matches.count { it.status == "missing" }
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(r.cover, 56.dp, 8.dp)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(r.name, style = T.ui(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.matches.size} tracks · from ${r.service}", style = T.ui(12.sp, FontWeight.Medium, c.t3))
                        }
                    }
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        listOf("matched" to matched, "review" to review, "missing" to missing).forEach { (k, n) ->
                            Row(Modifier.press { tab = k }, verticalAlignment = Alignment.Bottom) {
                                RollingNumber(n, T.num(22.sp, FontWeight.Medium, if (tab == k) c.txt else c.t3))
                                Text(" " + k.replaceFirstChar { it.uppercase() }, style = T.ui(12.sp, FontWeight.SemiBold, if (tab == k) c.txt else c.t3), modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                }
                val rows = r.matches.withIndex().filter { it.value.status == tab }
                itemsIndexed(rows, key = { _, iv -> iv.index }) { i, (idx, m) ->
                    val inc = idx in included.value
                    Row(Modifier.fillMaxWidth().enter(i).padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(m.matched?.thumb, 42.dp, 6.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(m.source.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.matched?.let { "${it.title} · ${it.artists}" } ?: m.source.artists, style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${m.confidence}%", style = T.num(11.sp, color = c.t3), modifier = Modifier.padding(horizontal = 8.dp))
                        if (m.matched != null) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(if (inc) c.txt else c.surf4).press { included.value = if (inc) included.value - idx else included.value + idx },
                                contentAlignment = Alignment.Center,
                            ) { Icon(if (inc) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (inc) c.bg else c.t3, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
                item {
                    Box(
                        Modifier.padding(20.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.accentUi).press {
                            val tracks = included.value.sorted().mapNotNull { r.matches[it].matched }
                            val pl = App.core.saveImport(r.name, r.service, r.cover, tracks)
                            s.toast("Imported ${tracks.size} songs")
                            s.pop()
                            s.push(Dest.Playlist(pl.id))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("Add ${included.value.size} songs", style = T.ui(15.sp, FontWeight.Bold, c.onAccent)) }
                }
            }
        }
    }
}

@Composable
fun IsaScreen(s: AppState, prompt: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var res by remember { mutableStateOf<IsaResult?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val plan = remember(prompt) { App.core.isaPlan(prompt) }
    LaunchedEffect(prompt) {
        runCatching { App.core.isa(prompt) }.onSuccess { res = it }.onFailure { err = it.message?.substringAfter(": ") }
    }
    val pulse by animateFloatAsState(if (res == null) 1f else 0f, spring(stiffness = 80f), label = "pulse")
    Box(Modifier.fillMaxSize()) {
        Glow(c.glow, Modifier.fillMaxWidth().height(380.dp), alpha = 0.22f + 0.15f * pulse, centerY = 0.15f)
        Column(Modifier.fillMaxSize()) {
            BackBar(s, "")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = c.txt, modifier = Modifier.size(16.dp))
                        Text("Isa", style = T.ui(13.sp, FontWeight.Bold), modifier = Modifier.padding(start = 6.dp))
                    }
                    RiseText(plan.title, T.ui(24.sp, FontWeight.Bold), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2)
                    Text("\u201C$prompt\u201D", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(horizontal = 24.dp))
                    Text(plan.explanation, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(plan.moods + plan.languages) { i, m -> ChipButton(m, modifier = Modifier.enter(i)) {} }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    AnimatedContent(res != null, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "isa") { ready ->
                        if (!ready) {
                            Column(Modifier.padding(24.dp)) {
                                Text(err ?: "Isa is putting it together…", style = T.ui(13.sp, FontWeight.Medium, c.t2))
                                if (err == null) Shimmers()
                            }
                        } else {
                            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(c.accentUi).press {
                                    res?.let { Playback.play(ctx, it.tracks, 0, "Isa · ${plan.title}") }
                                }.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                                        Text(" Play", style = T.ui(15.sp, FontWeight.Bold, c.onAccent))
                                    }
                                }
                                Box(Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(c.surf3).press {
                                    res?.let { App.core.createPlaylist("Isa · ${plan.title}", it.tracks); s.toast("Saved to Library") }
                                }.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) { Text("Save", style = T.ui(15.sp, FontWeight.SemiBold)) }
                            }
                        }
                    }
                }
                res?.let { r ->
                    itemsIndexed(r.tracks, key = { i, t -> "$i${t.id}" }) { i, t ->
                        TrackRow(t, i, onClick = { Playback.play(ctx, r.tracks, i, "Isa · ${plan.title}") }, onMore = { s.sheet = Sheet.TrackMenu(t) }, trailing = fmtDur(t.durationS.toInt()))
                    }
                }
            }
        }
    }
}
