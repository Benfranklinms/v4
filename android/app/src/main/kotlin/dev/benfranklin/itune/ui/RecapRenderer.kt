package dev.benfranklin.itune.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.benfranklin.itune.core.Recap
import dev.benfranklin.itune.core.TrackStat
import java.io.File
import kotlin.random.Random

/**
 * The 1080x1920 Recap story card. Pure android.graphics (safe off the main thread). Every block is placed
 * from measured text metrics, top to bottom; the footer is pinned to the bottom margin and the song list
 * takes only the rows that fit between them.
 */
object RecapRenderer {
    const val W = 1080
    const val H = 1920
    /** Side gutters and the top / bottom margins. */
    private const val G = 96f
    private const val TOP = 120f
    private const val BOTTOM = 120f
    private const val MAX_SONGS = 5
    private const val BASE = 0xFF09090A.toInt()

    class Fonts(val bold: Typeface, val semi: Typeface, val med: Typeface, val numLight: Typeface, val num: Typeface)

    fun fonts(ctx: Context): Fonts {
        fun f(res: Int, fb: Typeface) = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, res) }.getOrNull() ?: fb
        return Fonts(
            f(dev.benfranklin.itune.R.font.manrope_bold, Typeface.DEFAULT_BOLD),
            f(dev.benfranklin.itune.R.font.manrope_semibold, Typeface.DEFAULT),
            f(dev.benfranklin.itune.R.font.manrope_medium, Typeface.DEFAULT),
            f(dev.benfranklin.itune.R.font.jetbrainsmono_light, Typeface.MONOSPACE),
            f(dev.benfranklin.itune.R.font.jetbrainsmono_regular, Typeface.MONOSPACE),
        )
    }

    /** The card is always dark: the palette accent, readable on #09090A (light palettes fall back to mono). */
    fun cardAccent(acc: androidx.compose.ui.graphics.Color, lightPalette: Boolean): Int {
        if (lightPalette) return 0xFFE8E8E8.toInt()
        val c = dev.benfranklin.itune.ui.theme.ensureContrast(acc, androidx.compose.ui.graphics.Color(BASE))
        return Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    }

    fun loadArt(ctx: Context, url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return runCatching {
            val req = coil.request.ImageRequest.Builder(ctx).data(url).size(720, 720).allowHardware(false).build()
            val res = kotlinx.coroutines.runBlocking { coil.Coil.imageLoader(ctx).execute(req) }
            (res.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    /** Same title (trimmed, case-insensitive) by the same main artist = one row. The core already merges; this keeps the card right for any list. */
    fun mergeSameSongs(list: List<TrackStat>): List<TrackStat> {
        val out = LinkedHashMap<String, TrackStat>()
        for (t in list) {
            val title = t.track.title.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
            val artist = t.track.artists.split(',', '&').first().trim().lowercase()
            val k = if (title.isEmpty()) "id:" + t.track.id else "$title\u001f$artist"
            val p = out[k]
            out[k] = if (p == null) t else p.copy(plays = p.plays + t.plays, minutes = p.minutes + t.minutes)
        }
        return out.values.sortedWith(compareByDescending<TrackStat> { it.plays }.thenByDescending { it.minutes })
    }

    private fun paint(tf: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    /** Height of a capital (top of "H" to the baseline). */
    private fun cap(p: Paint): Float { val r = Rect(); p.getTextBounds("H", 0, 1, r); return -r.top.toFloat() }

    private fun fit(p: Paint, s: String, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText(t.trimEnd() + "…") > max) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    /** Draws `s` so its ink starts at x; returns the ink width. */
    private fun ink(cv: Canvas, s: String, x: Float, baseline: Float, p: Paint): Float {
        val r = Rect(); p.getTextBounds(s, 0, s.length, r)
        cv.drawText(s, x - r.left, baseline, p)
        return r.width().toFloat()
    }
    private fun inkW(s: String, p: Paint): Float { val r = Rect(); p.getTextBounds(s, 0, s.length, r); return r.width().toFloat() }

    /** The Pulse mark: a disc with three rounded bars cut out (same geometry as the app icon glyph). */
    fun drawPulse(cv: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val s = size / 24f
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addCircle(x + 12 * s, y + 12 * s, 10 * s, Path.Direction.CW)
            fun bar(x0: Float, y0: Float, y1: Float) = addRoundRect(RectF(x + x0 * s, y + y0 * s, x + (x0 + 2f) * s, y + y1 * s), s, s, Path.Direction.CW)
            bar(7.4f, 8.6f, 15.4f); bar(11f, 6.4f, 17.6f); bar(14.6f, 7.8f, 16.2f)
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun backdrop(cv: Canvas, art: Bitmap?) {
        cv.drawColor(BASE)
        val cx = W * 0.70f
        val cy = H * 0.20f
        val rad = W * 0.95f
        if (art != null) {
            // a very soft, low-saturation glow of the top artist's art: progressive down/up scaling = smooth blur
            val side = minOf(art.width, art.height)
            val sq = Bitmap.createBitmap(art, (art.width - side) / 2, (art.height - side) / 2, side, side)
            var cur = Bitmap.createScaledBitmap(sq, 10, 10, true)
            var n = 10
            while (n < 640) {
                n = minOf(n * 2, 640)
                val next = Bitmap.createScaledBitmap(cur, n, n, true)
                cur.recycle(); cur = next
            }
            if (sq !== art) sq.recycle()
            val layer = cv.saveLayer(0f, 0f, W.toFloat(), H.toFloat(), null)
            val cm = ColorMatrix().apply { setSaturation(0.25f) }
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm); alpha = 175 }
            cv.drawBitmap(cur, null, RectF(cx - rad, cy - rad, cx + rad, cy + rad), p)
            cur.recycle()
            val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, rad, intArrayOf(Color.BLACK, Color.argb(150, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask)
            cv.restoreToCount(layer)
        } else {
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = RadialGradient(cx, cy, rad, intArrayOf(Color.argb(30, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            })
        }
        // lower half settles back to the base so the list reads cleanly
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, H * 0.30f, 0f, H.toFloat(), intArrayOf(Color.TRANSPARENT, Color.argb(150, 9, 9, 10)), null, Shader.TileMode.CLAMP)
        })
        // vignette
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = RadialGradient(W / 2f, H * 0.42f, H * 0.78f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        // fine grain (fixed seed: the same card twice is the same PNG)
        val g = 160
        val px = IntArray(g * g)
        val rnd = Random(29)
        for (i in px.indices) { val v = rnd.nextInt(256); px[i] = Color.argb(10, v, v, v) }
        val tile = Bitmap.createBitmap(px, g, g, Bitmap.Config.ARGB_8888)
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        tile.recycle()
    }

    fun render(f: Fonts, r: Recap, accent: Int, art: Bitmap?): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        backdrop(cv, art)
        val white = Color.argb(255, 242, 242, 242)
        val dim = Color.argb(160, 255, 255, 255)
        val faint = Color.argb(100, 255, 255, 255)
        val L = G
        val R = W - G
        var y = TOP

        // header: Pulse mark + name, period on the right; both centred on the mark
        val mark = 52f
        val brand = paint(f.bold, 38f, white).apply { letterSpacing = -0.01f }
        val per = paint(f.med, 32f, dim)
        val mid = y + mark / 2
        drawPulse(cv, L, y, mark, white)
        cv.drawText("iTune", L + mark + 18f, mid + cap(brand) / 2, brand)
        cv.drawText(r.periodLabel, R - per.measureText(r.periodLabel), mid + cap(per) / 2, per)
        y += mark + 112f

        // title
        val title = paint(f.bold, 112f, white).apply { letterSpacing = -0.025f }
        y += cap(title)
        cv.drawText(fit(title, r.title, R - L), L, y, title)
        y += 84f

        // total time: digits and h/m units share one baseline; spacing from ink bounds
        val minutes = r.minutes.toInt()
        val parts = if (minutes >= 60) listOf("${minutes / 60}" to "h", "%02d".format(minutes % 60) to "m") else listOf("$minutes" to "m")
        var digitSize = 250f
        val unitGap = 14f
        val pairGap = 46f
        fun width(ds: Float): Float {
            val d = paint(f.numLight, ds, white); val u = paint(f.semi, ds * 0.30f, dim)
            return parts.sumOf { (n, un) -> (inkW(n, d) + unitGap + inkW(un, u)).toDouble() }.toFloat() + pairGap * (parts.size - 1)
        }
        if (width(digitSize) > R - L) digitSize *= (R - L) / width(digitSize)
        val dP = paint(f.numLight, digitSize, white).apply { letterSpacing = -0.04f }
        val uP = paint(f.semi, digitSize * 0.30f, dim)
        y += cap(dP)
        var x = L
        parts.forEachIndexed { i, (n, un) ->
            x += ink(cv, n, x, y, dP) + unitGap
            x += ink(cv, un, x, y, uP)
            if (i < parts.size - 1) x += pairGap
        }
        y += 72f

        // plays (+ change): numerals in JetBrains Mono, words in Manrope, one baseline
        val numP = paint(f.num, 40f, accent)
        val wordP = paint(f.med, 38f, dim)
        val faintP = paint(f.med, 38f, faint)
        val plays = r.plays.toInt()
        val segs = mutableListOf("$plays" to numP, (if (plays == 1) " play" else " plays") to wordP)
        r.changePercent?.let {
            segs += "   ·   " to faintP
            segs += "${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)}%" to paint(f.num, 40f, dim)
            segs += " vs last ${if (r.range == "month") "month" else "week"}" to wordP
        }
        y += cap(wordP)
        x = L
        segs.forEach { (s, p) -> cv.drawText(s, x, y, p); x += p.measureText(s) }
        y += 120f

        // top artist: art disc + three lines centred on it
        r.topArtist?.let { a ->
            val d = 208f
            val c = PointF(L + d / 2, y + d / 2)
            if (art != null) {
                val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val sc = d / minOf(art.width, art.height)
                sh.setLocalMatrix(Matrix().apply { setScale(sc, sc); postTranslate(L - (art.width * sc - d) / 2, y - (art.height * sc - d) / 2) })
                cv.drawCircle(c.x, c.y, d / 2, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sh })
            } else {
                cv.drawCircle(c.x, c.y, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 255, 255, 255) })
                val ip = paint(f.bold, 84f, dim)
                val ch = a.name.trim().take(1).uppercase()
                cv.drawText(ch, c.x - ip.measureText(ch) / 2, c.y + cap(ip) / 2, ip)
            }
            cv.drawCircle(c.x, c.y, d / 2 + 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = accent; alpha = 200 })
            val tx = L + d + 52f
            val lab = paint(f.semi, 30f, dim).apply { letterSpacing = 0.08f }
            val nm = paint(f.bold, 66f, white).apply { letterSpacing = -0.015f }
            val tm = paint(f.num, 34f, dim)
            val g1 = 30f; val g2 = 32f
            val block = cap(lab) + g1 + cap(nm) + g2 + cap(tm)
            var ty = y + (d - block) / 2 + cap(lab)
            cv.drawText("TOP ARTIST", tx, ty, lab)
            ty += g1 + cap(nm)
            cv.drawText(fit(nm, a.name, R - tx), tx, ty, nm)
            ty += g2 + cap(tm)
            cv.drawText(hm(a.minutes.toInt()), tx, ty, tm)
            y += d + 120f
        }

        // footer, pinned to the bottom margin
        val footP = paint(f.med, 32f, dim)
        val foot = listOfNotNull(r.moodLine.ifEmpty { null }, if (r.goalStreak.toInt() > 0) "${r.goalStreak}-day goal streak" else null).joinToString("   ·   ")
        val footBase = H - BOTTOM
        val songsBottom = if (foot.isNotEmpty()) footBase - cap(footP) - 80f else H - BOTTOM
        if (foot.isNotEmpty()) cv.drawText(fit(footP, foot, R - L), L, footBase, footP)

        // top songs: as many rows as fit (max 5), titles ellipsized before the play count
        val songs = mergeSameSongs(r.topTracks)
        if (songs.isNotEmpty()) {
            val lab = paint(f.semi, 30f, dim).apply { letterSpacing = 0.08f }
            val tP = paint(f.bold, 50f, white).apply { letterSpacing = -0.01f }
            val aP = paint(f.med, 33f, dim)
            val rankP = paint(f.num, 38f, faint)
            val rank1 = paint(f.num, 38f, accent)
            val cntP = paint(f.num, 36f, dim)
            val rankCol = 76f
            val tToA = 28f // title baseline -> artist cap top (clears descenders)
            val rowH = cap(tP) + tToA + cap(aP) + aP.fontMetrics.descent
            val rowGap = 44f
            val labH = cap(lab) + 48f
            if (y + labH + rowH <= songsBottom) {
                y += cap(lab)
                cv.drawText("TOP SONGS", L, y, lab)
                y += 48f
                val room = songsBottom - y
                val n = minOf(MAX_SONGS, songs.size, ((room + rowGap) / (rowH + rowGap)).toInt())
                val shown = songs.take(n)
                val cntW = shown.maxOfOrNull { cntP.measureText("${it.plays}×") } ?: 0f
                shown.forEachIndexed { i, t ->
                    val tb = y + cap(tP)
                    cv.drawText("${i + 1}", L, tb, if (i == 0) rank1 else rankP)
                    val cnt = "${t.plays}×"
                    cv.drawText(cnt, R - cntP.measureText(cnt), tb, cntP)
                    val tx = L + rankCol
                    cv.drawText(fit(tP, t.track.title.trim(), R - tx - cntW - 36f), tx, tb, tP)
                    cv.drawText(fit(aP, t.track.artists, R - tx - cntW - 36f), tx, tb + tToA + cap(aP), aP)
                    y += rowH + rowGap
                }
            }
        }
        return b
    }

    private class PointF(val x: Float, val y: Float)

    private fun hm(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

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
