package dev.benfranklin.itune.ui.screens

import dev.benfranklin.itune.ui.components.glass

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
import androidx.compose.ui.platform.testTag
import dev.benfranklin.itune.ui.components.fmtDur
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
    val overrides = remember { androidx.compose.runtime.mutableStateMapOf<Int, dev.benfranklin.itune.core.Track>() }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var more by remember { mutableStateOf<Map<Int, List<dev.benfranklin.itune.core.Track>>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    fun run() {
        if (url.isBlank() || busy) return
        busy = true
        err = null
        scope.launch {
            dev.benfranklin.itune.data.ioResult { App.core.importPlaylist(url.trim()) }
                .onSuccess { r ->
                    res = r
                    overrides.clear()
                    included.value = r.matches.indices.filter { r.matches[it].status == "matched" }.toSet()
                    tab = if (r.matches.any { it.status == "review" }) "review" else "matched"
                }
                .onFailure { err = it.message?.substringAfter(": ")?.takeIf { m -> m.isNotBlank() && !m.contains("HTTP") } ?: "Couldn't read that playlist. Make sure the link is public." }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (ImportLink.pending != null) { ImportLink.pending = null; run() } }
    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Import playlists")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Text("Paste a public Spotify or Apple Music playlist link. You can check and swap any song before it's added.", style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(horizontal = 24.dp))
            }
            item {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth().height(50.dp).glass(RoundedCornerShape(25.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (url.isEmpty()) Text("open.spotify.com/playlist/… or music.apple.com/…", style = T.ui(13.sp, FontWeight.Medium, c.t3), maxLines = 1)
                        BasicTextField(url, { url = it }, singleLine = true, textStyle = T.ui(13.sp, FontWeight.Medium), cursorBrush = SolidColor(c.txt), modifier = Modifier.fillMaxWidth().testTag("import_url"))
                    }
                    Icon(Icons.Rounded.ContentPaste, null, tint = c.t2, modifier = Modifier.size(38.dp).padding(9.dp).press { clip.getText()?.text?.let { url = it } })
                    Box(Modifier.size(38.dp).clip(CircleShape).background(if (url.isBlank()) c.surf3 else c.accentUi).press { if (url.isBlank()) s.toast("Paste a playlist link first") else run() }.testTag("import_go"), contentAlignment = Alignment.Center) {
                        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Check, "Import", tint = if (url.isBlank()) c.t3 else c.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (busy) item {
                var prog by remember { mutableStateOf<dev.benfranklin.itune.core.ImportProgress?>(null) }
                LaunchedEffect(Unit) {
                    while (true) {
                        prog = dev.benfranklin.itune.data.io { App.core.importProgress() }
                        kotlinx.coroutines.delay(400)
                    }
                }
                val pr = prog
                Column(Modifier.padding(horizontal = 24.dp).testTag("import_loading")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (pr != null && pr.total > 0u) "Importing… ${pr.done}/${pr.total}" else "Importing…",
                            style = T.ui(13.sp, FontWeight.SemiBold, c.txt), modifier = Modifier.weight(1f),
                        )
                        Text("Cancel", style = T.ui(13.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.press { dev.benfranklin.itune.data.Bg.run { App.core.importCancel() } }.padding(6.dp).testTag("import_cancel"))
                    }
                    if (pr != null && pr.current.isNotBlank()) Text(pr.current, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pr != null && pr.total > 0u) {
                        val f = (pr.done.toFloat() / pr.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.txt, trackColor = c.surf4, strokeCap = StrokeCap.Round)
                    } else {
                        LinearProgressIndicator(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.txt, trackColor = c.surf4, strokeCap = StrokeCap.Round)
                    }
                }
            }
            err?.let { e -> item { Text(e, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(24.dp)) } }
            res?.let { r ->
                val matched = r.matches.count { it.status == "matched" }
                val review = r.matches.count { it.status == "review" }
                val missing = r.matches.count { it.status == "missing" }
                item {
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                if (rows.isEmpty()) item { Text(if (tab == "missing") "Every song was found." else "Nothing here.", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                itemsIndexed(rows, key = { _, iv -> iv.index }) { i, (idx, m) ->
                    val inc = idx in included.value
                    val pick = overrides[idx] ?: m.matched
                    Column(Modifier.animateItem()) {
                        Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.99f) { expanded = if (expanded == idx) null else idx }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(pick?.thumb, 42.dp, 6.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(m.source.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pick?.let { "→ ${it.title} · ${it.artists}" } ?: "Not found · tap to search", style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (overrides[idx] == null) Text(if (m.matched == null) "–" else "${m.confidence}%", style = T.num(11.sp, color = c.t3), modifier = Modifier.padding(horizontal = 8.dp))
                            else Text("Swapped", style = T.ui(11.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(horizontal = 8.dp))
                            if (pick != null) {
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(if (inc) c.txt else c.surf4).press { included.value = if (inc) included.value - idx else included.value + idx },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(if (inc) Icons.Rounded.Check else Icons.Rounded.Close, if (inc) "Included" else "Skipped", tint = if (inc) c.bg else c.t3, modifier = Modifier.size(14.dp)) }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(expanded == idx) {
                            Column(Modifier.padding(start = 40.dp, end = 20.dp, bottom = 8.dp).glass(RoundedCornerShape(14.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).padding(vertical = 6.dp)) {
                                val alts = (m.alternatives + more[idx].orEmpty()).distinctBy { it.id }.filter { it.id != pick?.id }
                                if (alts.isEmpty()) Text("No other versions yet.", style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(12.dp))
                                alts.take(8).forEach { a ->
                                    Row(Modifier.fillMaxWidth().press(scaleTo = 0.98f) { overrides[idx] = a; included.value = included.value + idx; expanded = null }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Artwork(a.thumb, 34.dp, 5.dp)
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(a.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(listOf(a.artists, fmtDur(a.durationS.toInt())).filter { it.isNotEmpty() }.joinToString(" · "), style = T.ui(11.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                        }
                                        Text("Use", style = T.ui(12.sp, FontWeight.Bold), modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                Text("Search again", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(12.dp).press {
                                    scope.launch {
                                        val found = dev.benfranklin.itune.data.io { App.core.importCandidates(m.source, "") }.orEmpty()
                                        more = more + (idx to found)
                                        if (found.isEmpty()) s.toast("Nothing else found")
                                    }
                                })
                            }
                        }
                    }
                }
                item {
                    Box(
                        Modifier.padding(20.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.accentUi).press {
                            if (saving) return@press
                            val tracks = included.value.sorted().mapNotNull { overrides[it] ?: r.matches[it].matched }
                            if (tracks.isEmpty()) { s.toast("Pick at least one song"); return@press }
                            saving = true
                            scope.launch {
                                val pl = dev.benfranklin.itune.data.io { App.core.saveImport(r.name, r.service, r.cover, tracks) }
                                saving = false
                                if (pl == null) { s.toast("Couldn't save the playlist"); return@launch }
                                dev.benfranklin.itune.data.Library.upsert(pl)
                                s.toast("Imported ${tracks.size} songs")
                                s.pop()
                                s.push(Dest.Playlist(pl.id))
                            }
                        }.testTag("import_save"),
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
    var plan by remember(prompt) { mutableStateOf(dev.benfranklin.itune.core.IsaPlan(prompt, emptyList(), emptyList(), emptyList(), false, null, 0, "")) }
    LaunchedEffect(prompt) {
        dev.benfranklin.itune.data.io { App.core.isaPlan(prompt) }?.let { plan = it }
        dev.benfranklin.itune.data.ioResult { App.core.isa(prompt) }.onSuccess { res = it }.onFailure { err = it.message?.substringAfter(": ") ?: "Isa couldn't find anything for that." }
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
                        itemsIndexed(plan.moods + plan.languages) { i, m -> ChipButton(m, modifier = Modifier.enter(i)) { s.push(Dest.Isa(m)) } }
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
                                Box(Modifier.height(46.dp).glass(RoundedCornerShape(23.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).press {
                                    res?.let { r -> dev.benfranklin.itune.data.Bg.run { App.core.createPlaylistNamed(plan.title, "Made with Isa", "", r.tracks); dev.benfranklin.itune.data.Library.refresh() }; s.toast("Saved to Library") }
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
