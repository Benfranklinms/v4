package dev.benfranklin.itune.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.HourDetail
import dev.benfranklin.itune.core.NamedCount
import dev.benfranklin.itune.core.StatsPage
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Peek
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.Card
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.RollingNumber
import dev.benfranklin.itune.ui.components.bottomInset
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Stats pages by range, kept across tab switches so a range change shows numbers at once (then refreshes). */
object StatsCache {
    val pages = java.util.concurrent.ConcurrentHashMap<String, StatsPage>()
    /** Bumped when the goal or a playlist changes from a sheet: the page reloads. */
    val rev = mutableIntStateOf(0)
    fun invalidate() {
        pages.clear()
        rev.intValue++
    }
}

private fun rangeTitle(r: String) = when (r) {
    "week" -> "This week"
    "month" -> "Last 30 days"
    "year" -> "Last 12 months"
    else -> "All time"
}

private fun prevLabel(r: String) = when (r) {
    "week" -> "vs last week"
    "month" -> "vs 30 days before"
    "year" -> "vs the year before"
    else -> ""
}

private fun rangeWord(r: String) = when (r) {
    "week" -> "this week"
    "month" -> "this month"
    "year" -> "this year"
    else -> "lately"
}

/** "1 play", "12 plays" */
fun playsLabel(n: Int): String = if (n == 1) "1 play" else "$n plays"

/** "1h 41m", "58m" */
fun hm(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

private fun hourLabel(h: Int): String = when (h) {
    0 -> "12 AM"
    in 1..11 -> "$h AM"
    12 -> "12 PM"
    else -> "${h - 12} PM"
}

private fun haptic(h: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    if (App.prefs.haptics.value) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@Composable
fun StatsScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val range by App.prefs.statsRange.state.collectAsState()
    var page by remember { mutableStateOf(StatsCache.pages[range]) }
    val rev = StatsCache.rev.intValue
    LaunchedEffect(range, rev) {
        StatsCache.pages[range]?.let { page = it }
        val t0 = System.nanoTime()
        val p = io { App.core.statsPage(range, tzMin()) }
        if (p != null) {
            StatsCache.pages[range] = p
            page = p
            Log.i(
                "itune-stats",
                "page range=$range min=${p.minutes} prev=${p.prevMinutes} bars=${p.bars.size} songs=${p.topTracks.size} " +
                    "artists=${p.topArtists.size} albums=${p.topAlbums.size} days=${p.historyDays} ms=${(System.nanoTime() - t0) / 1_000_000}",
            )
        }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val x = page?.takeIf { it.range == range } ?: page
    val empty = x != null && x.historyDays.toInt() == 0

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("stats_list"),
            state = list,
            contentPadding = PaddingValues(bottom = bottomInset(), start = 16.dp, end = 16.dp),
        ) {
            item("head") {
                Row(Modifier.statusBarsPadding().padding(top = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RiseText("Stats", T.ui(22.sp, FontWeight.Bold), Modifier.weight(1f))
                    GlassPill("Recap", Icons.Rounded.AutoAwesome, tag = "stats_recap", iconTint = c.accentUi) {
                        s.sheet = Sheet.Recap(if (range == "month") "month" else "week")
                    }
                }
            }
            item("seg") { RangeSwitch(range) }
            item("hero") { HeroCard(s, x, range) }
            if (empty) {
                item("empty") {
                    Text(
                        "Play something and your stats start here.",
                        style = T.ui(13.sp, FontWeight.Medium, c.t3),
                        modifier = Modifier.padding(top = 18.dp, start = 4.dp).testTag("stats_empty"),
                    )
                }
            }
            if (x != null && !empty) {
                if (x.topArtists.isNotEmpty() || x.topTracks.isNotEmpty()) item("cards") { ArtCards(s, x) }
                if (x.topTracks.isNotEmpty()) topSongs(this, s, x)
                if (x.topArtists.isNotEmpty()) item("artists") { TopArtists(s, x) }
                if (x.topAlbums.isNotEmpty()) item("albums") { TopAlbums(s, x) }
                item("clock") { ClockCard(x, range) }
                item("goal") { GoalCard(s, x) }
                if (x.newArtistsReady && x.newArtists.isNotEmpty()) item("new") { NewToYou(s, x, range) }
                if (x.skipped.isNotEmpty()) item("skips") { SkippedOften(s, x) }
                item("end") { Spacer(Modifier.height(8.dp)) }
            }
        }
        // compact glass bar once the header has scrolled away: title + the range, tap to step through ranges
        val pinned by remember { derivedStateOf { list.firstVisibleItemIndex >= 2 } }
        AnimatedVisibility(
            pinned,
            enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { -it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { -it / 2 },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .glass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), dark = 0.55f, sheen = 0.06f, rim = 0.5f)
                    .statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)
                    .testTag("stats_bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Stats", style = T.ui(20.sp, FontWeight.Bold), modifier = Modifier.weight(1f).press(haptic = false) { scope.launch { list.animateScrollToItem(0) } })
                val opts = listOf("week", "month", "year", "all")
                GlassPill(rangeLabel(range), Icons.Rounded.KeyboardArrowDown, tag = "stats_bar_range", iconEnd = true) {
                    App.prefs.statsRange.value = opts[(opts.indexOf(range) + 1) % opts.size]
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unusedCtx = ctx
}

private fun rangeLabel(r: String) = when (r) { "week" -> "Week"; "month" -> "Month"; "year" -> "Year"; else -> "All" }

@Composable
fun GlassPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tag: String? = null,
    iconTint: Color? = null,
    iconEnd: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalColors.current
    Row(
        modifier.press(onClick = onClick)
            .glass(RoundedCornerShape(20.dp), dark = 0.30f, sheen = 0.10f, rim = 0.7f)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null && !iconEnd) Icon(icon, null, tint = iconTint ?: c.txt, modifier = Modifier.size(16.dp))
        Text(label, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1)
        if (icon != null && iconEnd) Icon(icon, null, tint = iconTint ?: c.t2, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun RangeSwitch(range: String) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val opts = listOf("week" to "Week", "month" to "Month", "year" to "Year", "all" to "All")
    val idx = opts.indexOfFirst { it.first == range }.coerceAtLeast(0)
    val pill = remember { Animatable(idx.toFloat()) }
    LaunchedEffect(idx) { if (reduce) pill.snapTo(idx.toFloat()) else pill.animateTo(idx.toFloat(), spring(dampingRatio = 0.72f, stiffness = 520f)) }
    Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(40.dp).glass(RoundedCornerShape(20.dp), dark = 0.40f, sheen = 0.05f, rim = 0.5f)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
            val w = maxWidth / opts.size
            // translation (not padding): the spring overshoots below 0, and negative padding crashes
            Box(
                Modifier.graphicsLayer { translationX = w.toPx() * pill.value.coerceIn(-0.2f, opts.size - 0.8f) }.width(w).fillMaxHeight()
                    .glass(RoundedCornerShape(17.dp), dark = 0.06f, sheen = 0.20f, rim = 0.85f).background(c.txt.copy(alpha = 0.10f)),
            )
            Row(Modifier.fillMaxSize()) {
                opts.forEach { (k, l) ->
                    Box(Modifier.weight(1f).fillMaxSize().press(scaleTo = 0.97f) { App.prefs.statsRange.value = k }.testTag("range_$k"), contentAlignment = Alignment.Center) {
                        Text(l, style = T.ui(13.sp, FontWeight.SemiBold, if (k == range) c.txt else c.t3))
                    }
                }
            }
        }
    }
}

/** Hours + minutes that count up (and snap while scrubbing). */
@Composable
private fun BigTime(minutes: Int, snapNow: Boolean) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(0f) }
    LaunchedEffect(minutes, snapNow) {
        if (reduce || snapNow) a.snapTo(minutes.toFloat()) else a.animateTo(minutes.toFloat(), tween(750, easing = FastOutSlowInEasing))
    }
    val v = a.value.toInt().coerceAtLeast(0)
    val big = T.num(58.sp, FontWeight.Light)
    val unit = T.ui(18.sp, FontWeight.Medium, c.t2)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.testTag("stats_total")) {
        if (minutes >= 60) {
            Text("${v / 60}", style = big)
            Text("h", style = unit, modifier = Modifier.padding(start = 3.dp, end = 12.dp, bottom = 10.dp))
            Text("%02d".format(v % 60), style = big)
        } else {
            Text("$v", style = big)
        }
        Text("m", style = unit, modifier = Modifier.padding(start = 3.dp, bottom = 10.dp))
    }
}

@Composable
private fun HeroCard(s: AppState, x: StatsPage?, range: String) {
    val c = LocalColors.current
    val bars = x?.bars.orEmpty()
    var scrub by remember(range) { mutableIntStateOf(-1) }
    var ghost by remember(range) { mutableStateOf(false) }
    val hist = x?.historyDays?.toInt() ?: 0
    val sparse = x != null && hist in 1..6
    val bar = bars.getOrNull(scrub)
    val total = bar?.minutes?.toInt() ?: x?.minutes?.toInt() ?: 0
    val label = when {
        bar != null -> bar.title
        x != null && hist == 0 -> "Nothing played yet"
        sparse -> if (hist == 1) "Your first day" else "Your first $hist days"
        else -> rangeTitle(range)
    }
    Card(Modifier.padding(top = 14.dp).fillMaxWidth().enter(0).testTag("stats_hero"), padding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2), maxLines = 1, modifier = Modifier.testTag("stats_hero_label"))
                    BigTime(total, snapNow = bar != null)
                }
                if (bar == null && x != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (sparse) {
                            Text(
                                "First week",
                                style = T.ui(12.sp, FontWeight.SemiBold, c.accentUi),
                                modifier = Modifier.glass(RoundedCornerShape(14.dp), dark = 0.25f, sheen = 0.08f, rim = 0.6f).padding(horizontal = 12.dp, vertical = 6.dp).testTag("stats_first_week"),
                            )
                        } else if (x.hasPrev && x.prevMinutes.toInt() > 0) {
                            val pct = ((x.minutes.toFloat() - x.prevMinutes.toFloat()) / x.prevMinutes.toFloat() * 100f).let { kotlin.math.round(it).toInt() }
                            val up = pct >= 0
                            Row(
                                Modifier.press { ghost = !ghost }
                                    .glass(RoundedCornerShape(14.dp), dark = if (ghost) 0.10f else 0.25f, sheen = 0.08f, rim = if (ghost) 0.9f else 0.6f)
                                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag("stats_change"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (up) Icons.Rounded.NorthEast else Icons.Rounded.SouthEast, null, tint = if (up) c.accentUi else c.t2, modifier = Modifier.size(14.dp))
                                Text("${if (up) "+" else ""}$pct%", style = T.num(13.sp, FontWeight.Medium, if (up) c.accentUi else c.t2), modifier = Modifier.padding(start = 4.dp))
                            }
                            Text(prevLabel(range), style = T.ui(11.5.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            val line = when {
                bar != null -> bar.line.ifEmpty { "Nothing played" }
                else -> x?.moodLine.orEmpty()
            }
            if (line.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bar == null) {
                        val icon = when (x?.mood) {
                            "night" -> Icons.Rounded.Bedtime
                            "morning" -> Icons.Rounded.WbTwilight
                            "afternoon" -> Icons.Rounded.WbSunny
                            else -> Icons.Rounded.Schedule
                        }
                        Icon(icon, null, tint = c.t2, modifier = Modifier.size(16.dp).padding(end = 0.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(line, style = T.ui(14.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("stats_mood"))
                }
            }
            BarChart(x, range, scrub, ghost, onScrub = { scrub = it }) { i ->
                val b = bars.getOrNull(i) ?: return@BarChart
                if (b.minutes.toInt() > 0 || b.plays.toInt() > 0) s.sheet = Sheet.StatsDay(b.title, b.start, b.end)
            }
            if (sparse) {
                Text("Fills in as you listen", style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(top = 6.dp).testTag("stats_fills_in"))
            }
        }
    }
}

@Composable
private fun BarChart(x: StatsPage?, range: String, scrub: Int, ghost: Boolean, onScrub: (Int) -> Unit, onTap: (Int) -> Unit) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val bars = x?.bars.orEmpty()
    val prev = x?.prevBars.orEmpty()
    val n = bars.size.coerceAtLeast(1)
    val grow = remember(range) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(x) { if (x != null && !reduce && grow.value < 1f) grow.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 140f)) }
    val ghostA by animateFloatAsState(if (ghost) 1f else 0f, if (reduce) snap() else spring(stiffness = 300f), label = "ghost")
    val h = LocalHapticFeedback.current
    val tm = rememberTextMeasurer()
    val avgStyle = T.num(11.sp, FontWeight.Normal, c.t3)
    val accent = c.accentUi
    val rest = lerp(c.surf4, c.t3, 0.45f)
    val dot = c.t4
    val avg = x?.avgMinutes?.toFloat() ?: 0f
    Canvas(
        Modifier.padding(top = 18.dp).fillMaxWidth().height(128.dp).testTag("stats_chart")
            .pointerInput(n, bars) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slot = size.width.toFloat() / n
                    fun idx(px: Float) = (px / slot).toInt().coerceIn(0, n - 1)
                    var cur = idx(down.position.x)
                    val slop = viewConfiguration.touchSlop
                    // phase 1: tap, horizontal drag or a short hold starts scrubbing; a vertical drag belongs to the list
                    var scrubbing = false
                    var tapped = false
                    var gone = false
                    while (!scrubbing && !tapped && !gone) {
                        val ev = withTimeoutOrNull(220L) { awaitPointerEvent() }
                        if (ev == null) { scrubbing = true; break }
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null) { gone = true; break }
                        if (!ch.pressed) { tapped = true; break }
                        val d = ch.position - down.position
                        if (abs(d.y) > slop && abs(d.y) > abs(d.x)) { gone = true; break }
                        if (abs(d.x) > slop) { scrubbing = true; cur = idx(ch.position.x); ch.consume() }
                    }
                    if (tapped) { onTap(cur); return@awaitEachGesture }
                    if (!scrubbing) return@awaitEachGesture
                    haptic(h)
                    onScrub(cur)
                    Log.i("itune-stats", "scrub start idx=$cur")
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val i = idx(ch.position.x)
                        if (i != cur) { cur = i; haptic(h); onScrub(i) }
                    }
                    Log.i("itune-stats", "scrub end idx=$cur")
                    onScrub(-1)
                }
            },
    ) {
        if (bars.isEmpty()) return@Canvas
        val slot = size.width / n
        val bw = (slot * 0.56f).coerceAtMost(34.dp.toPx())
        val top = 16.dp.toPx()
        val hgt = size.height - top
        val maxV = maxOf(bars.maxOf { it.minutes.toFloat() }, if (ghost) prev.maxOfOrNull { it.toFloat() } ?: 0f else 0f, avg, 1f)
        val g = grow.value
        // average line
        if (avg > 0f) {
            val y = top + hgt - hgt * avg / maxV
            drawLine(c.t3.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            drawText(tm, "avg ${hm(avg.toInt())}", Offset(0f, (y - 16.dp.toPx()).coerceAtLeast(0f)), avgStyle)
        }
        for (i in bars.indices) {
            val cx = slot * i + slot / 2
            val v = bars[i].minutes.toFloat()
            if (ghostA > 0.01f) {
                val pv = prev.getOrNull(i)?.toFloat() ?: 0f
                if (pv > 0f) {
                    val ph = (hgt * pv / maxV).coerceAtLeast(6.dp.toPx())
                    drawRoundRect(
                        c.t2.copy(alpha = 0.55f * ghostA), Offset(cx - bw / 2, top + hgt - ph), Size(bw, ph), CornerRadius(bw / 2.6f),
                        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
            }
            if (v <= 0f) {
                drawCircle(dot, 2.6.dp.toPx(), Offset(cx, top + hgt - 4.dp.toPx()))
                continue
            }
            val bh = (hgt * v / maxV * g).coerceAtLeast(6.dp.toPx())
            val on = if (scrub >= 0) i == scrub else bars[i].isCurrent
            val col = if (on) accent else rest.copy(alpha = if (scrub >= 0) 0.75f else 1f)
            drawRoundRect(col, Offset(cx - bw / 2, top + hgt - bh), Size(bw, bh), CornerRadius(bw / 2.6f))
            if (i == scrub) {
                val r = (bw * 0.95f).coerceAtMost(slot * 0.55f)
                val cy = top + hgt - minOf(bh, r)
                drawCircle(Color.White.copy(alpha = 0.10f), r, Offset(cx, cy))
                drawCircle(c.t2.copy(alpha = 0.6f), r, Offset(cx, cy), style = Stroke(1.dp.toPx()))
            }
        }
    }
    // axis labels
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val every = when {
            bars.size <= 12 -> 1
            bars.size <= 31 -> 5
            else -> (bars.size + 5) / 6
        }
        bars.forEachIndexed { i, b ->
            val show = (bars.size - 1 - i) % every == 0
            val on = if (scrub >= 0) i == scrub else b.isCurrent
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (show || on) {
                    Text(b.label, style = T.num(11.5.sp, if (on) FontWeight.Medium else FontWeight.Normal, if (on) c.txt else c.t3), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ArtCards(s: AppState, x: StatsPage) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val a = x.topArtists.firstOrNull()
    val t = x.topTracks.firstOrNull()
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().enter(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a != null) {
            ArtCard(
                Modifier.weight(1f), a.thumb, "Top artist", a.name, "${hm(a.minutes.toInt())} · ${playsLabel(a.plays.toInt())}", circle = false,
                tag = "stats_top_artist", playTag = "stats_play_artist",
                onOpen = { if (a.id.isNotEmpty()) s.push(Dest.Artist(a.id)) },
            ) {
                App.scope.launch {
                    val mix = io { App.core.statsMix(x.range, a.name, tzMin()) }.orEmpty()
                    if (mix.isNotEmpty()) launch(Dispatchers.Main) { Playback.play(ctx, mix, 0, a.name) }
                }
            }
        } else Spacer(Modifier.weight(1f))
        if (t != null) {
            ArtCard(
                Modifier.weight(1f), t.track.thumb, "On repeat", t.track.title, "${playsLabel(t.plays.toInt())} · ${t.minutes} min", circle = false,
                tag = "stats_on_repeat", playTag = "stats_play_repeat",
                onOpen = { Playback.play(ctx, listOf(t.track), 0, "On repeat") },
            ) { Playback.play(ctx, listOf(t.track), 0, "On repeat") }
        } else Spacer(Modifier.weight(1f))
    }
    @Suppress("UNUSED_VARIABLE") val u = c
}

@Composable
private fun ArtCard(
    modifier: Modifier, art: String, chip: String, title: String, meta: String, circle: Boolean,
    tag: String, playTag: String, onOpen: () -> Unit, onPlay: () -> Unit,
) {
    val c = LocalColors.current
    Box(modifier.press(scaleTo = 0.97f, onClick = onOpen).glass(RoundedCornerShape(26.dp), dark = 0.42f, sheen = 0.06f, rim = 0.55f).testTag(tag).padding(10.dp)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Artwork(art, maxWidth, 18.dp, circle = circle)
                Text(
                    chip, style = T.ui(12.sp, FontWeight.SemiBold),
                    modifier = Modifier.padding(8.dp).glass(RoundedCornerShape(12.dp), dark = 0.45f, sheen = 0.08f, rim = 0.6f).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(42.dp).press(onClick = onPlay)
                        .glass(CircleShape, dark = 0.40f, sheen = 0.14f, rim = 0.8f).testTag(playTag),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = c.txt, modifier = Modifier.size(22.dp)) }
            }
            Text(title, style = T.ui(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            Text(meta, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun SectionHead(title: String, sub: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = LocalColors.current
    Row(Modifier.padding(top = 28.dp, bottom = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.ui(22.sp, FontWeight.Bold))
            if (sub != null) Text(sub, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(top = 2.dp))
        }
        action?.invoke()
    }
}

private fun topSongs(scope: androidx.compose.foundation.lazy.LazyListScope, s: AppState, x: StatsPage) {
    scope.item("songs_head") {
        val ctx = LocalContext.current
        SectionHead("Top songs") {
            GlassPill("Play top ${x.topTracks.size.coerceAtMost(25)}", Icons.Rounded.PlayArrow, tag = "stats_play_top") {
                Playback.play(ctx, x.topTracks.map { it.track }, 0, "Top songs")
            }
        }
    }
    scope.item("songs") { TopSongRows(x) }
}

@Composable
private fun TopSongRows(x: StatsPage) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var all by remember(x.range) { mutableStateOf(false) }
    val list = x.topTracks
    val maxP = (list.maxOfOrNull { it.plays.toInt() } ?: 1).coerceAtLeast(1)
    Column(Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = 400f))) {
        list.take(if (all) 25 else 5).forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().press(scaleTo = 0.98f) { Playback.play(ctx, list.map { it.track }, i, "Top songs") }
                    .testTag("stats_top_song").padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = T.num(12.5.sp, color = c.t3), modifier = Modifier.width(26.dp))
                Artwork(t.track.thumb, 46.dp, 8.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                    Text(t.track.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.track.artists, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(56.dp)) {
                    Text("${t.plays}×", style = T.num(12.5.sp, color = c.t2))
                    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surf3)) {
                        Box(Modifier.fillMaxWidth(t.plays.toFloat() / maxP).fillMaxHeight().background(if (i == 0) c.accentUi else c.t2))
                    }
                }
            }
        }
        if (list.size > 5) {
            Text(
                if (all) "Show less" else "Show all ${list.size}",
                style = T.ui(13.sp, FontWeight.SemiBold, c.t2),
                modifier = Modifier.padding(top = 4.dp).press { all = !all }.testTag("stats_more_songs").padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TopArtists(s: AppState, x: StatsPage) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    Column {
        SectionHead("Top artists") {
            GlassPill("Play mix", tag = "stats_play_mix") {
                App.scope.launch {
                    val mix = io { App.core.statsMix(x.range, null, tzMin()) }.orEmpty()
                    if (mix.isNotEmpty()) launch(Dispatchers.Main) { Playback.play(ctx, mix, 0, "Your top artists") }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(x.topArtists, key = { i, a -> "$i${a.name}" }) { i, a ->
                ArtistCircle(a, i == 0, Modifier.enter(i)) { if (a.id.isNotEmpty()) s.push(Dest.Artist(a.id)) }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val u = c
}

@Composable
private fun ArtistCircle(a: NamedCount, ring: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 74.dp, sub: String? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(modifier.width(size + 12.dp).press(onClick = onClick).testTag("stats_artist"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size + 8.dp).then(if (ring) Modifier.border(2.dp, c.accentUi, CircleShape) else Modifier).padding(4.dp),
        ) { Artwork(a.thumb, size, circle = true) }
        Text(a.name, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(sub ?: hm(a.minutes.toInt()), style = T.num(11.5.sp, color = c.t3), maxLines = 1)
    }
}

@Composable
private fun TopAlbums(s: AppState, x: StatsPage) {
    val c = LocalColors.current
    Column {
        SectionHead("Top albums")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(x.topAlbums, key = { i, a -> "$i${a.name}" }) { i, a ->
                Column(
                    Modifier.width(132.dp).enter(i).press(scaleTo = 0.97f) {
                        if (a.albumId.isNotEmpty()) {
                            s.push(Dest.Album(a.albumId, Peek(a.name, a.artist, a.thumb)))
                        } else {
                            // no album id was recorded with the plays: look it up once, then open
                            s.toast("Opening ${a.name}")
                            App.scope.launch {
                                val id = io { App.core.statsAlbumId(a.name, a.artist) }
                                Log.i("itune-stats", "album lookup '${a.name}' -> ${id ?: "none"}")
                                launch(Dispatchers.Main) {
                                    if (id.isNullOrEmpty()) s.toast("Couldn't find ${a.name}")
                                    else s.push(Dest.Album(id, Peek(a.name, a.artist, a.thumb)))
                                }
                            }
                        }
                    }.testTag("stats_album"),
                ) {
                    Artwork(a.thumb, 132.dp, 14.dp)
                    Text(a.name, style = T.ui(13.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text("${a.artist} · ${playsLabel(a.plays.toInt())}", style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ClockCard(x: StatsPage, range: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val reduce = LocalReduceMotion.current
    val h = LocalHapticFeedback.current
    var hour by remember(range) { mutableIntStateOf(-1) }
    var detail by remember(range) { mutableStateOf<HourDetail?>(null) }
    LaunchedEffect(hour, x) {
        detail = if (hour < 0) null else io { App.core.statsHour(range, hour.toUInt(), tzMin()) }
        if (hour >= 0) Log.i("itune-stats", "clock hour=$hour tracks=${detail?.tracks?.size ?: 0}")
    }
    val clock = x.clock.map { it.toFloat() }
    val cmax = (clock.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val peak = x.peakHour
    val open = remember(range) { Animatable(0f) }
    LaunchedEffect(hour) { if (reduce) open.snapTo(if (hour >= 0) 1f else 0f) else open.animateTo(if (hour >= 0) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
    val accent = c.accentUi
    val tm = rememberTextMeasurer()
    val tick = T.num(10.sp, color = c.t3)
    val midStyle = T.num(13.sp, FontWeight.Medium, c.txt)
    Card(
        Modifier.padding(top = 28.dp).fillMaxWidth().animateContentSize(if (reduce) snap() else spring(dampingRatio = 0.85f, stiffness = 380f)).testTag("stats_clock"),
        padding = PaddingValues(18.dp),
    ) {
        Column {
            Text("Listening clock", style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2))
            Text(
                when {
                    hour >= 0 -> detail?.title ?: "Around ${hourLabel(hour)}"
                    peak >= 0 -> "Mostly around ${hourLabel(peak)}"
                    else -> "Nothing yet"
                },
                style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.padding(top = 2.dp),
            )
            if (hour >= 0) Text("${detail?.minutes ?: 0} min ${rangeWord(range)}", style = T.num(12.sp, color = c.t2))
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.size(196.dp).testTag("stats_clock_face").pointerInput(range) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun hourAt(p: Offset): Int {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                if (hypot(p.x - cx, p.y - cy) < size.width * 0.18f) return if (peak >= 0) peak else -1
                                var deg = Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())) + 90.0
                                if (deg < 0) deg += 360.0
                                return ((deg / 15.0).toInt()) % 24
                            }
                            val slop = viewConfiguration.touchSlop
                            var dragging = false
                            var cur = hourAt(down.position)
                            while (true) {
                                val ev = if (dragging) awaitPointerEvent() else withTimeoutOrNull(220L) { awaitPointerEvent() }
                                if (ev == null) { dragging = true; haptic(h); hour = cur; continue }
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    if (!dragging) { haptic(h); hour = if (hour == cur) -1 else cur }
                                    break
                                }
                                val d = ch.position - down.position
                                if (!dragging) {
                                    if (abs(d.y) > slop && abs(d.y) > abs(d.x)) break
                                    if (abs(d.x) > slop) { dragging = true; haptic(h); hour = cur }
                                }
                                if (dragging) {
                                    ch.consume()
                                    val nh = hourAt(ch.position)
                                    if (nh != cur && nh >= 0) { cur = nh; haptic(h); hour = nh }
                                }
                            }
                        }
                    },
                ) {
                    val r0 = size.minDimension * 0.20f
                    val r1 = size.minDimension * 0.43f
                    val sw = (size.minDimension * 0.040f)
                    val cx = center.x
                    val cy = center.y
                    for (hh in 0 until 24) {
                        val a = Math.toRadians((hh * 15 - 90 + 7.5))
                        val act = (clock.getOrNull(hh) ?: 0f).div(cmax).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
                        val rr = r0 + (r1 - r0) * act.coerceAtLeast(0.04f)
                        val ca = cos(a).toFloat()
                        val sa = sin(a).toFloat()
                        drawLine(c.surf3, Offset(cx + r0 * ca, cy + r0 * sa), Offset(cx + r1 * ca, cy + r1 * sa), strokeWidth = sw, cap = StrokeCap.Round)
                        val sel = hh == hour
                        val col = when {
                            sel -> accent
                            hour < 0 && hh == peak -> accent
                            else -> lerp(c.surf4, c.t2, act)
                        }
                        val grown = if (sel) r0 + (rr - r0) + (r1 - rr) * 0.15f * open.value else rr
                        drawLine(col, Offset(cx + r0 * ca, cy + r0 * sa), Offset(cx + grown * ca, cy + grown * sa), strokeWidth = if (sel) sw * (1f + 0.5f * open.value) else sw, cap = StrokeCap.Round)
                    }
                    // 12 AM / 6 AM / 12 PM / 6 PM ticks
                    listOf(0 to "12A", 6 to "6A", 12 to "12P", 18 to "6P").forEach { (hh, l) ->
                        val a = Math.toRadians((hh * 15 - 90).toDouble())
                        val rr = r1 + size.minDimension * 0.06f
                        val m = tm.measure(l, tick)
                        drawText(m, topLeft = Offset(cx + rr * cos(a).toFloat() - m.size.width / 2f, cy + rr * sin(a).toFloat() - m.size.height / 2f))
                    }
                    val mid = if (hour >= 0) hourLabel(hour) else if (peak >= 0) hourLabel(peak) else "–"
                    val m = tm.measure(mid, midStyle)
                    drawText(m, topLeft = Offset(cx - m.size.width / 2f, cy - m.size.height / 2f))
                }
            }
            val d = detail
            if (hour >= 0 && d != null) {
                Column(Modifier.padding(top = 12.dp).graphicsLayer { alpha = open.value.coerceIn(0f, 1f) }.testTag("stats_hour_detail")) {
                    if (d.tracks.isEmpty()) {
                        Text("Nothing played around ${hourLabel(hour)} ${rangeWord(range)}.", style = T.ui(13.sp, FontWeight.Medium, c.t3))
                    } else {
                        d.tracks.take(5).forEachIndexed { i, t ->
                            Row(
                                Modifier.fillMaxWidth().press(scaleTo = 0.98f) { Playback.play(ctx, d.tracks.map { it.track }, i, d.title) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(t.track.thumb, 40.dp, 7.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(t.track.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.track.artists, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${t.minutes} min", style = T.num(12.sp, color = c.t3))
                            }
                        }
                        GlassPill("Play these", Icons.Rounded.PlayArrow, tag = "stats_play_hour", modifier = Modifier.padding(top = 8.dp)) {
                            Playback.play(ctx, d.tracks.map { it.track }, 0, d.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(s: AppState, x: StatsPage) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val goal = x.goalMinutes.toInt().coerceAtLeast(1)
    val grow = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(x.goalMinutes, x.todayMinutes) { if (!reduce) { grow.snapTo(0f); grow.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 120f)) } }
    Card(Modifier.padding(top = 14.dp).fillMaxWidth().testTag("stats_goal"), padding = PaddingValues(18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily goal", style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.weight(1f))
                GlassPill("$goal min", Icons.Rounded.Edit, tag = "goal_edit", iconEnd = true) { s.sheet = Sheet.StatsGoal }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                RollingNumber(x.goalStreak.toInt(), T.num(40.sp, FontWeight.Light))
                Text(if (x.goalStreak.toInt() == 1) "day streak" else "day streak", style = T.ui(13.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).weight(1f))
                Text("best ${x.goalBest}", style = T.num(11.5.sp, color = c.t3), modifier = Modifier.padding(bottom = 9.dp))
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                x.goalWeek.forEach { d ->
                    val p = (d.minutes.toFloat() / goal).coerceIn(0f, 1f)
                    val col = if (d.isToday) c.accentUi else c.t2
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.size(32.dp)) {
                            val sw = 3.6.dp.toPx()
                            val inset = sw / 2
                            drawArc(c.surf3, 0f, 360f, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                            if (p > 0f) drawArc(col, -90f, 360f * p * grow.value, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Text(d.label, style = T.num(11.sp, if (d.isToday) FontWeight.Medium else FontWeight.Normal, if (d.isToday) c.txt else c.t3), modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            val left = (goal - x.todayMinutes.toInt()).coerceAtLeast(0)
            Text(
                if (left == 0) "Today's goal done" else "$left min to go today",
                style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun NewToYou(s: AppState, x: StatsPage, range: String) {
    val n = x.newArtists.size
    Column(Modifier.testTag("stats_new")) {
        SectionHead("New to you", "$n ${if (n == 1) "artist" else "artists"} you first played ${if (range == "all") "this month" else rangeWord(range)}")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(x.newArtists, key = { i, a -> "n$i${a.name}" }) { i, a ->
                ArtistCircle(a, false, Modifier.enter(i), size = 60.dp) { if (a.id.isNotEmpty()) s.push(Dest.Artist(a.id)) }
            }
        }
    }
}

@Composable
private fun SkippedOften(s: AppState, x: StatsPage) {
    val c = LocalColors.current
    val hidden = remember { mutableStateListOf<String>() }
    val rows = x.skipped.filter { it.track.id !in hidden }
    if (rows.isEmpty()) return
    val where = x.skipped.flatMap { it.playlists }.groupingBy { it.title }.eachCount().maxByOrNull { it.value }?.key
    Column(Modifier.testTag("stats_skipped")) {
        SectionHead("Skipped often", if (where != null) "You skip these in $where" else "Songs you usually skip")
        rows.forEachIndexed { i, k ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = T.num(12.5.sp, color = c.t3), modifier = Modifier.width(26.dp))
                Artwork(k.track.thumb, 44.dp, 8.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                    Text(k.track.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("skipped ${k.skips} of ${k.plays}", style = T.num(11.5.sp, color = c.t3))
                }
                if (k.playlists.isNotEmpty()) {
                    val names = k.playlists.map { it.title }.distinct()
                    GlassPill("Remove", tag = "skip_remove") {
                        val label = if (names.size == 1) names[0] else "${names.size} playlists"
                        s.undoable(
                            "Removed from $label",
                            apply = { hidden += k.track.id },
                            undo = { hidden -= k.track.id },
                            commit = {
                                App.scope.launch(Dispatchers.IO) {
                                    // highest position first per playlist, so earlier positions stay valid
                                    k.playlists.groupBy { it.playlistId }.forEach { (pid, slots) ->
                                        slots.sortedByDescending { it.position }.forEach { sl -> runCatching { App.core.removeFromPlaylist(pid, sl.position) } }
                                    }
                                    Log.i("itune-stats", "removed skipped ${k.track.id} from ${k.playlists.size} slot(s)")
                                    launch(Dispatchers.Main) { StatsCache.invalidate() }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun tracksOf(l: List<dev.benfranklin.itune.core.TrackStat>): List<Track> = l.map { it.track }
