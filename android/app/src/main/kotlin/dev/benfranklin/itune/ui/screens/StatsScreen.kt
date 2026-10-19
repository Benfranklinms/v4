package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.StatsSummary
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.Card
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.RollingNumber
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StatsScreen(s: AppState) {
    val c = LocalColors.current
    var range by remember { mutableStateOf("month") }
    var st by remember { mutableStateOf<StatsSummary?>(null) }
    LaunchedEffect(range) { st = withContext(Dispatchers.IO) { runCatching { App.core.stats(range, tzMin()) }.getOrNull() } }
    val reduce = LocalReduceMotion.current
    val grow = remember(range) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(st) { if (st != null && !reduce) { grow.snapTo(0f); grow.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 120f)) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp, start = 20.dp, end = 20.dp)) {
        item { RiseText("Stats", T.ui(22.sp, FontWeight.Bold), Modifier.statusBarsPadding().padding(top = 22.dp)) }
        item {
            // segmented control with a sliding pill
            val opts = listOf("week" to "Week", "month" to "Month", "year" to "Year", "all" to "All time")
            val idx = opts.indexOfFirst { it.first == range }
            val pill = remember { Animatable(idx.toFloat()) }
            LaunchedEffect(idx) { pill.animateTo(idx.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f)) }
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.surf2)) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(2.dp)) {
                    val w = maxWidth / opts.size
                    Box(Modifier.padding(start = w * pill.value).width(w).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(c.surf4))
                    Row(Modifier.fillMaxSize()) {
                        opts.forEach { (k, l) ->
                            Box(Modifier.weight(1f).fillMaxSize().press { range = k }, contentAlignment = Alignment.Center) {
                                Text(l, style = T.ui(12.5.sp, FontWeight.SemiBold, if (k == range) c.txt else c.t3))
                            }
                        }
                    }
                }
            }
        }
        val x = st
        item {
            Card(Modifier.padding(top = 14.dp).fillMaxWidth().enter(0)) {
                Column {
                    Text("Minutes listened", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2))
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                        RollingNumber(x?.minutes?.toInt() ?: 0, T.num(44.sp, FontWeight.Light))
                        Text("min", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                    }
                    if (x != null && x.prevMinutes.toInt() > 0) {
                        val pct = ((x.minutes.toFloat() - x.prevMinutes.toFloat()) / x.prevMinutes.toFloat() * 100).toInt()
                        Text("${if (pct >= 0) "+" else ""}$pct% vs previous", style = T.num(12.sp, color = c.t2))
                    }
                    val series = x?.series.orEmpty().map { it.toFloat() }
                    val max = (series.maxOrNull() ?: 1f).coerceAtLeast(1f)
                    Canvas(Modifier.padding(top = 12.dp).fillMaxWidth().height(34.dp)) {
                        val n = series.size.coerceAtLeast(1)
                        val slot = size.width / n
                        series.forEachIndexed { i, v ->
                            val h = (size.height * v / max * grow.value).coerceAtLeast(2.5f)
                            drawRoundRect(
                                if (i == series.lastIndex) c.accentUi else lerp(c.surf4, c.t3, 0.6f),
                                topLeft = Offset(i * slot + slot * 0.18f, size.height - h), size = Size(slot * 0.64f, h), cornerRadius = CornerRadius(3f),
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().enter(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.weight(1f).height(176.dp)) {
                    Column {
                        Text("Listening clock", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2))
                        val clock = x?.clock.orEmpty().map { it.toFloat() }
                        val cmax = (clock.maxOrNull() ?: 1f).coerceAtLeast(1f)
                        val peak = clock.indices.maxByOrNull { clock[it] } ?: 0
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(112.dp)) {
                                val r0 = size.minDimension * 0.18f
                                val r1 = size.minDimension * 0.48f
                                for (h in 0 until 24) {
                                    val a = Math.toRadians((h * 15 - 90 + 7.5).toDouble())
                                    val act = if (clock.isEmpty()) 0f else clock[h] / cmax
                                    val rr = r0 + (r1 - r0) * act * grow.value
                                    val cx = center.x
                                    val cy = center.y
                                    drawLine(c.surf3, Offset(cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat()), Offset(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat()), strokeWidth = 7f)
                                    drawLine(
                                        if (h == peak && act > 0) c.accentUi else lerp(c.surf4, c.t2, act),
                                        Offset(cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat()), Offset(cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat()), strokeWidth = 7f,
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    )
                                }
                            }
                            Text("${peak}h", style = T.num(10.sp, FontWeight.Medium))
                        }
                    }
                }
                Card(Modifier.weight(1f).height(176.dp)) {
                    Column {
                        Row {
                            Text("Streak", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.weight(1f))
                            Text("BEST ${x?.bestStreakDays ?: 0}", style = T.num(10.sp, color = c.t3))
                        }
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                            RollingNumber(x?.streakDays?.toInt() ?: 0, T.num(44.sp, FontWeight.Light))
                            Text("days", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                        }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            x?.streakWeek.orEmpty().forEachIndexed { i, on ->
                                Box(
                                    Modifier.size(10.dp).clip(CircleShape).background(if (on) (if (i == 6) c.accentUi else c.t2) else c.surf4),
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Text("Top artists", style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.padding(top = 28.dp, bottom = 12.dp)) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(x?.topArtists.orEmpty(), key = { _, a -> a.name }) { i, a ->
                    Column(Modifier.width(70.dp).enter(i).press { if (a.id.isNotEmpty()) s.push(Dest.Artist(a.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(a.thumb, 58.dp, circle = true)
                        Text(a.name, style = T.ui(12.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(if (a.minutes.toInt() >= 60) "${a.minutes.toInt() / 60} h" else "${a.minutes} min", style = T.num(11.sp, color = c.t3))
                    }
                }
            }
        }
        item { Text("Top songs", style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.padding(top = 26.dp, bottom = 6.dp)) }
        itemsIndexed(x?.topTracks.orEmpty(), key = { _, t -> t.id }) { i, t ->
            Row(Modifier.fillMaxWidth().enter(i).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = T.num(12.sp, color = c.t3), modifier = Modifier.width(24.dp))
                Artwork(t.thumb, 40.dp, 6.dp)
                Text(t.name, style = T.ui(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.plays}×", style = T.num(12.sp, color = c.t2))
            }
        }
        item {
            Card(Modifier.padding(top = 18.dp).fillMaxWidth()) {
                Column {
                    Row {
                        Text("Genres", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.weight(1f))
                        x?.genres?.firstOrNull()?.let { Text("${it.name} ${it.percent}%", style = T.num(11.5.sp, color = c.txt)) }
                    }
                    val shades = listOf(Color(0xFFEDEDED), Color(0xFFB4B4B7), Color(0xFF86868A), Color(0xFF5E5E62), Color(0xFF424245), Color(0xFF2C2C2F))
                    Canvas(Modifier.padding(top = 14.dp).fillMaxWidth().height(10.dp)) {
                        var xx = 0f
                        x?.genres.orEmpty().forEachIndexed { i, g ->
                            val w = size.width * g.percent.toFloat() / 100f * grow.value
                            drawRoundRect(shades[i % shades.size].let { if (c.light) lerp(it, Color.Black, 0.5f) else it }, Offset(xx, 0f), Size((w - 3f).coerceAtLeast(0f), size.height), CornerRadius(5f))
                            xx += w
                        }
                    }
                    x?.genres.orEmpty().forEachIndexed { i, g ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(shades[i % shades.size]))
                            Text(g.name, style = T.ui(12.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(start = 8.dp).weight(1f))
                            Text("${g.percent}%", style = T.num(11.5.sp, color = c.t2))
                        }
                    }
                    if (x != null && x.genres.isEmpty()) Text("Play some music and your stats appear here.", style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            @Suppress("UNUSED_VARIABLE") val unused = Stroke(1f)
        }
    }
}
