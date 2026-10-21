package dev.benfranklin.itune.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.PlayEntry
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.screens.GlassPill
import dev.benfranklin.itune.ui.screens.StatsCache
import dev.benfranklin.itune.ui.screens.hm
import dev.benfranklin.itune.ui.screens.playsLabel
import dev.benfranklin.itune.ui.screens.tzMin
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The plays inside one bar of the Stats chart, newest first. */
@Composable
internal fun StatsDaySheet(s: AppState, d: Sheet.StatsDay) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var plays by remember(d) { mutableStateOf<List<PlayEntry>?>(null) }
    LaunchedEffect(d) {
        plays = io { App.core.statsPlaysBetween(d.from, d.to) }.orEmpty()
        Log.i("itune-stats", "day sheet '${d.title}' plays=${plays?.size}")
    }
    Column(Modifier.padding(horizontal = 24.dp).testTag("stats_day_sheet")) {
        Text(d.title, style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
        val p = plays
        val min = p?.sumOf { it.listenedS.toLong() }?.div(60)?.toInt() ?: 0
        Text(if (p == null) " " else "${playsLabel(p.size)} · ${hm(min)}", style = T.num(12.sp, color = c.t2), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        if (p != null && p.isEmpty()) Text("Nothing played.", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(vertical = 12.dp))
        val tracks = remember(p) { p.orEmpty().map { it.track }.distinctBy { it.id } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            itemsIndexed(p.orEmpty(), key = { i, e -> "$i${e.startedAt}" }) { _, e ->
                Row(
                    Modifier.fillMaxWidth().press(scaleTo = 0.98f) {
                        val k = tracks.indexOfFirst { it.id == e.track.id }.coerceAtLeast(0)
                        Playback.play(ctx, tracks, k, d.title)
                        s.sheet = null
                    }.testTag("stats_day_row").padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e.time, style = T.num(11.5.sp, color = c.t3), modifier = Modifier.width(70.dp), maxLines = 1)
                    Artwork(e.track.thumb, 40.dp, 7.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.track.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.track.artists, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (e.skipped) Text("skipped", style = T.ui(11.5.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Daily goal picker: 15 / 30 / 60 minutes, stored in the core. */
@Composable
internal fun StatsGoalSheet(s: AppState) {
    val c = LocalColors.current
    var goal by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { goal = io { App.core.statsGoal().toInt() } }
    Column(Modifier.padding(horizontal = 24.dp).testTag("goal_sheet")) {
        Text("Daily goal", style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
        Text("Minutes a day that keep your streak going", style = T.ui(12.5.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        listOf(15, 30, 60).forEach { m ->
            Row(
                Modifier.fillMaxWidth().press(scaleTo = 0.98f) {
                    goal = m
                    App.scope.launch {
                        io { App.core.setStatsGoal(m.toUInt()) }
                        Log.i("itune-stats", "goal set $m")
                        withContext(Dispatchers.Main) {
                            StatsCache.invalidate()
                            s.sheet = null
                        }
                    }
                }.testTag("goal_$m").padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$m min", style = T.ui(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (goal == m) Icon(Icons.Rounded.Check, null, tint = c.accentUi, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Recap: a 9:16 story card (rendered to a PNG, so what you see is what is shared). */
@Composable
internal fun RecapSheet(s: AppState, startRange: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(if (startRange == "month") "month" else "week") }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    // the shared card keeps the theme's own accent (Pure Mono / Adaptive: #E8E8E8), never the live album tint
    val accent = RecapRenderer.cardAccent(c.acc, c.light)
    LaunchedEffect(range) {
        val t0 = System.nanoTime()
        val r = io { App.core.statsRecap(range, tzMin()) } ?: return@LaunchedEffect
        val art = withContext(Dispatchers.IO) { RecapRenderer.loadArt(ctx, r.topArtist?.thumb?.ifEmpty { null } ?: r.topTrack?.track?.thumb) }
        bmp = withContext(Dispatchers.Default) { RecapRenderer.render(RecapRenderer.fonts(ctx), r, accent, art) }
        Log.i("itune-recap", "rendered range=$range min=${r.minutes} ms=${(System.nanoTime() - t0) / 1_000_000}")
    }
    Column(Modifier.padding(horizontal = 24.dp).testTag("recap_sheet"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recap", style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
            Row(Modifier.glass(RoundedCornerShape(18.dp), dark = 0.40f, sheen = 0.05f, rim = 0.5f).padding(3.dp)) {
                listOf("week" to "Week", "month" to "Month").forEach { (k, l) ->
                    Text(
                        l, style = T.ui(12.5.sp, FontWeight.SemiBold, if (k == range) c.txt else c.t3),
                        modifier = Modifier.clip(RoundedCornerShape(15.dp)).then(if (k == range) Modifier.background(c.txt.copy(alpha = 0.12f)) else Modifier)
                            .press(scaleTo = 0.97f) { range = k }.testTag("recap_$k").padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Box(Modifier.height(400.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(20.dp)).background(c.surf3).testTag("recap_card"), contentAlignment = Alignment.Center) {
            AnimatedContent(bmp, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "recap") { b ->
                if (b != null) Image(b.asImageBitmap(), "Recap", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                else Text("…", style = T.ui(14.sp, FontWeight.Medium, c.t3))
            }
        }
        Row(Modifier.padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPill("Share", Icons.Rounded.Share, tag = "recap_share") {
                val b = bmp ?: return@GlassPill
                if (busy) return@GlassPill
                busy = true
                scope.launch {
                    val f = withContext(Dispatchers.IO) { RecapRenderer.writeShareFile(ctx, b, range) }
                    busy = false
                    if (f == null) { s.toast("Couldn't share the recap"); return@launch }
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(Intent.createChooser(send, "Share recap").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { s.toast("Couldn't share the recap") }
                    Log.i("itune-recap", "share ${f.length()} B")
                }
            }
            GlassPill("Save", Icons.Rounded.Download, tag = "recap_save") {
                val b = bmp ?: return@GlassPill
                if (busy) return@GlassPill
                busy = true
                scope.launch {
                    val where = withContext(Dispatchers.IO) { RecapRenderer.saveToPictures(ctx, b, range) }
                    busy = false
                    s.toast(if (where != null) "Saved to $where" else "Couldn't save the recap")
                    Log.i("itune-recap", "save -> ${where ?: "failed"}")
                }
            }
        }
    }
}
