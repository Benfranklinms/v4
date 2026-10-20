package dev.benfranklin.itune.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.io.File

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
    val accent = c.accentUi
    LaunchedEffect(range) {
        val t0 = System.nanoTime()
        val r = io { App.core.statsRecap(range, tzMin()) } ?: return@LaunchedEffect
        val art = withContext(Dispatchers.IO) { RecapRenderer.loadArt(ctx, r.topArtist?.thumb?.ifEmpty { null } ?: r.topTrack?.track?.thumb) }
        bmp = withContext(Dispatchers.Default) { RecapRenderer.render(ctx, r, accent, art) }
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

object RecapRenderer {
    const val W = 1080
    const val H = 1920

    fun loadArt(ctx: Context, url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return runCatching {
            val req = coil.request.ImageRequest.Builder(ctx).data(url).size(720, 720).allowHardware(false).build()
            val res = kotlinx.coroutines.runBlocking { coil.Coil.imageLoader(ctx).execute(req) }
            (res.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    private fun font(ctx: Context, res: Int, fallback: android.graphics.Typeface): android.graphics.Typeface =
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, res) }.getOrNull() ?: fallback

    /** Draws the 1080x1920 story card. Pure android.graphics: safe off the main thread. */
    fun render(ctx: Context, r: dev.benfranklin.itune.core.Recap, accent: androidx.compose.ui.graphics.Color, art: Bitmap?): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val bold = font(ctx, dev.benfranklin.itune.R.font.manrope_bold, android.graphics.Typeface.DEFAULT_BOLD)
        val semi = font(ctx, dev.benfranklin.itune.R.font.manrope_semibold, android.graphics.Typeface.DEFAULT)
        val med = font(ctx, dev.benfranklin.itune.R.font.manrope_medium, android.graphics.Typeface.DEFAULT)
        val mono = font(ctx, dev.benfranklin.itune.R.font.jetbrainsmono_light, android.graphics.Typeface.MONOSPACE)
        val monoR = font(ctx, dev.benfranklin.itune.R.font.jetbrainsmono_regular, android.graphics.Typeface.MONOSPACE)
        val acc = android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
        cv.drawColor(android.graphics.Color.rgb(12, 11, 13))
        // backdrop: the top artist's art, heavily softened (down- then up-scaled) and darkened
        if (art != null) {
            val small = Bitmap.createScaledBitmap(art, 18, 32, true)
            val soft = Bitmap.createScaledBitmap(small, W / 4, H / 4, true)
            val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            cv.drawBitmap(soft, null, android.graphics.Rect(0, 0, W, H), p)
            small.recycle(); soft.recycle()
        }
        val shade = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(android.graphics.Color.argb(120, 0, 0, 0), android.graphics.Color.argb(170, 0, 0, 0), android.graphics.Color.argb(235, 6, 6, 8)),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), shade)
        val white = android.graphics.Color.WHITE
        val dim = android.graphics.Color.argb(178, 255, 255, 255)
        val faint = android.graphics.Color.argb(120, 255, 255, 255)
        fun paint(tf: android.graphics.Typeface, size: Float, color: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color }
        fun fit(p: android.graphics.Paint, s: String, max: Float): String {
            if (p.measureText(s) <= max) return s
            var t = s
            while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
            return "$t…"
        }
        val L = 96f
        cv.drawText("iTune", L, 170f, paint(bold, 40f, dim))
        cv.drawText(r.periodLabel, (W - L) - paint(med, 34f, dim).measureText(r.periodLabel), 170f, paint(med, 34f, dim))
        cv.drawText(r.title, L, 330f, paint(bold, 104f, white))
        // total listening
        val h = r.minutes.toInt() / 60
        val m = r.minutes.toInt() % 60
        val big = paint(mono, 220f, white)
        val unit = paint(semi, 60f, dim)
        var x = L
        if (h > 0) {
            cv.drawText("$h", x, 600f, big); x += big.measureText("$h") + 10f
            cv.drawText("h", x, 600f, unit); x += unit.measureText("h") + 34f
            cv.drawText("%02d".format(m), x, 600f, big); x += big.measureText("%02d".format(m)) + 10f
        } else {
            cv.drawText("$m", x, 600f, big); x += big.measureText("$m") + 10f
        }
        cv.drawText("m", x, 600f, unit)
        val sub = buildString {
            append(playsLabel(r.plays.toInt()))
            r.changePercent?.let { append(" · ${if (it >= 0) "+" else ""}$it% vs last ${if (r.range == "month") "month" else "week"}") }
        }
        cv.drawText(sub, L, 680f, paint(med, 40f, acc))
        // top artist
        var y = 800f
        r.topArtist?.let { a ->
            val d = 230f
            if (art != null) {
                val sh = android.graphics.BitmapShader(art, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                val sc = d / minOf(art.width, art.height)
                sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(sc, sc); postTranslate(L - (art.width * sc - d) / 2, y - (art.height * sc - d) / 2) })
                cv.drawCircle(L + d / 2, y + d / 2, d / 2, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { shader = sh })
            }
            cv.drawCircle(L + d / 2, y + d / 2, d / 2 + 6f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f; color = acc })
            cv.drawText("Top artist", L + d + 48f, y + 82f, paint(semi, 36f, dim))
            cv.drawText(fit(paint(bold, 64f, white), a.name, W - L - (L + d + 48f)), L + d + 48f, y + 158f, paint(bold, 64f, white))
            cv.drawText(hm(a.minutes.toInt()), L + d + 48f, y + 212f, paint(monoR, 36f, dim))
            y += d + 110f
        }
        // top songs
        if (r.topTracks.isNotEmpty()) {
            cv.drawText("Top songs", L, y, paint(semi, 36f, dim))
            y += 30f
            r.topTracks.take(5).forEachIndexed { i, t ->
                y += 92f
                cv.drawText("${i + 1}", L, y, paint(monoR, 40f, if (i == 0) acc else faint))
                cv.drawText(fit(paint(bold, 48f, white), t.track.title, W - L - (L + 70f) - 120f), L + 70f, y, paint(bold, 48f, white))
                val pl = "${t.plays}×"
                val pp = paint(monoR, 36f, dim)
                cv.drawText(pl, W - L - pp.measureText(pl), y, pp)
                cv.drawText(fit(paint(med, 32f, dim), t.track.artists, W - 2 * L - 70f), L + 70f, y + 44f, paint(med, 32f, dim))
                y += 30f
            }
        }
        // footer: mood + streak
        val foot = listOfNotNull(r.moodLine.ifEmpty { null }, if (r.goalStreak.toInt() > 0) "${r.goalStreak}-day goal streak" else null).joinToString("  ·  ")
        if (foot.isNotEmpty()) cv.drawText(fit(paint(med, 36f, dim), foot, W - 2 * L), L, H - 140f, paint(med, 36f, dim))
        return b
    }

    fun writeShareFile(ctx: Context, b: Bitmap, range: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "recap-$range.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }.getOrNull()

    /** Pictures/iTune via MediaStore (Android 10+); the app's own Pictures folder before that. */
    fun saveToPictures(ctx: Context, b: Bitmap, range: String): String? = runCatching {
        val name = "recap-$range-${System.currentTimeMillis() / 1000}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/iTune")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching null
            cr.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return@runCatching null
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, v, null, null)
            "Pictures/iTune"
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "").apply { mkdirs() }
            File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "the app's Pictures folder"
        }
    }.getOrNull()
}
