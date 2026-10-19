package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.StatsSummary
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.RollingNumber
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Monthly Wrapped: animated story cards (tap right/left, auto-advance), from this month's stats. */
@Composable
fun WrappedScreen(s: AppState) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    var st by remember { mutableStateOf<StatsSummary?>(null) }
    LaunchedEffect(Unit) { st = withContext(Dispatchers.IO) { runCatching { App.core.stats("month", tzMin()) }.getOrNull() } }
    val month = remember { LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
    val data = st
    val cards = 5
    var page by remember { mutableIntStateOf(0) }
    val bar = remember { Animatable(0f) }
    LaunchedEffect(page, data) {
        if (data == null) return@LaunchedEffect
        bar.snapTo(0f)
        bar.animateTo(1f, tween(if (reduce) 1 else 6000, easing = LinearEasing))
        if (page < cards - 1) page++
    }
    val glow = s.glow
    Box(
        Modifier.fillMaxSize().background(c.bg)
            .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent), radius = 1400f))
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    if (o.x > size.width / 3f) { if (page < cards - 1) page++ else s.pop() } else if (page > 0) page--
                }
            },
    ) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(cards) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surf4)) {
                        val f = when { i < page -> 1f; i == page -> bar.value; else -> 0f }
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(c.txt))
                    }
                }
            }
            Text("$month Wrapped", style = T.ui(13.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(top = 14.dp))
        }
        if (data == null) return@Box
        // every card enters with a spring rise + fade
        val enter = remember(page) { Animatable(if (reduce) 1f else 0f) }
        LaunchedEffect(page) { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 180f)) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp).graphicsLayer {
                alpha = enter.value; translationY = (1f - enter.value) * 80f; scaleX = 0.94f + 0.06f * enter.value; scaleY = scaleX
            },
            verticalArrangement = Arrangement.Center,
        ) {
            when (page) {
                0 -> {
                    Text("You listened for", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    RollingNumber(data.minutes.toInt(), T.num(84.sp, FontWeight.Light))
                    Text("minutes this month", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    val prev = data.prevMinutes.toInt()
                    if (prev > 0) {
                        val d = ((data.minutes.toInt() - prev) * 100f / prev).toInt()
                        Text(if (d >= 0) "+$d% on last month" else "$d% on last month", style = T.num(15.sp, color = c.t3), modifier = Modifier.padding(top = 12.dp))
                    }
                }
                1 -> {
                    val a = data.topArtists.firstOrNull()
                    Text("Your top artist", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    Spacer(Modifier.height(18.dp))
                    if (a != null) {
                        Artwork(a.thumb, 220.dp, circle = true, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(a.name, style = T.ui(34.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 20.dp))
                        Text("${a.minutes} minutes · ${a.plays} plays", style = T.num(15.sp, color = c.t2))
                    } else Text("Play more to find out", style = T.ui(24.sp, FontWeight.Bold))
                }
                2 -> {
                    Text("Songs on repeat", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    Spacer(Modifier.height(18.dp))
                    data.topTracks.take(5).forEachIndexed { i, t ->
                        val r = remember(page) { Animatable(if (reduce) 1f else 0f) }
                        LaunchedEffect(page) { kotlinx.coroutines.delay(120L * i); r.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 220f)) }
                        Row(Modifier.padding(vertical = 7.dp).graphicsLayer { alpha = r.value; translationX = (1f - r.value) * 60f }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = T.num(22.sp, FontWeight.Light, c.t3), modifier = Modifier.width(34.dp))
                            Artwork(t.thumb, 52.dp, 8.dp)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(t.name, style = T.ui(16.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.plays} plays", style = T.num(12.sp, color = c.t2))
                            }
                        }
                    }
                }
                3 -> {
                    val clock = data.clock.map { it.toInt() }
                    val peak = clock.indices.maxByOrNull { clock[it] } ?: 0
                    val label = when (peak) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "late-night" }
                    Text("You're a", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    Text("$label listener", style = T.ui(38.sp, FontWeight.Bold))
                    Text("Peak hour ${"%02d".format(peak)}:00", style = T.num(15.sp, color = c.t2), modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                    val mx = (clock.maxOrNull() ?: 1).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        clock.forEachIndexed { h, v ->
                            Box(Modifier.weight(1f).fillMaxHeight(enter.value * (v.toFloat() / mx).coerceAtLeast(0.03f)).clip(RoundedCornerShape(3.dp))
                                .background(if (h == peak) glow else c.surf4))
                        }
                    }
                }
                else -> {
                    Text("Streak", style = T.ui(20.sp, FontWeight.SemiBold, c.t2))
                    RollingNumber(data.streakDays.toInt(), T.num(84.sp, FontWeight.Light))
                    Text("days in a row · best ${data.bestStreakDays}", style = T.ui(18.sp, FontWeight.SemiBold, c.t2))
                    Spacer(Modifier.height(28.dp))
                    Text("${data.plays} plays · ${data.topArtists.size} artists", style = T.num(15.sp, color = c.t3))
                    Text("Tap to close", style = T.ui(13.sp, color = c.t3), modifier = Modifier.padding(top = 40.dp))
                }
            }
        }
    }
}


