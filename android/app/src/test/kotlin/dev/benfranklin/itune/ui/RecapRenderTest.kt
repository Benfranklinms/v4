package dev.benfranklin.itune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import dev.benfranklin.itune.core.NamedCount
import dev.benfranklin.itune.core.Recap
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.core.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws sample Recap cards with the real renderer and writes them as PNGs (run with RECAP_PNG=<dir>). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class RecapRenderTest {
    private val out = File(System.getProperty("recap.out") ?: "build/recap-png").apply { mkdirs() }
    private fun tf(n: String) = Typeface.createFromFile(File("src/main/res/font/$n.ttf"))
    private val fonts = RecapRenderer.Fonts(tf("manrope_bold"), tf("manrope_semibold"), tf("manrope_medium"), tf("jetbrainsmono_light"), tf("jetbrainsmono_regular"))
    private val mono = 0xFFE8E8E8.toInt()

    private fun t(id: String, title: String, artists: String, plays: Int, min: Int) =
        TrackStat(Track(id, title, artists, null, null, null, 200u, ""), plays.toUInt(), min.toUInt())

    /** A stand-in monochrome portrait (no real artwork in the repo). */
    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(24, 24, 26))
        c.drawRect(0f, 0f, 720f, 720f, Paint().apply { shader = RadialGradient(380f, 300f, 520f, Color.rgb(150, 146, 150), Color.rgb(20, 20, 22), Shader.TileMode.CLAMP) })
        c.drawOval(250f, 170f, 470f, 470f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 190, 192) })
        c.drawOval(200f, 90f, 520f, 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 34, 34) })
        c.drawRect(170f, 520f, 560f, 720f, Paint().apply { color = Color.rgb(60, 58, 60) })
        return b
    }

    private fun save(name: String, b: Bitmap) {
        assertEquals(1080, b.width); assertEquals(1920, b.height)
        File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("recap png: ${File(out, name).absolutePath}")
    }

    @Test fun benWeek() {
        val r = Recap(
            "week", "Your week", "15 – 21 Oct", 213u, 96u, null,
            NamedCount("UCx", "Lana Del Rey", "", 30u, 24u), null,
            listOf(
                t("h", "Hips Don’t Lie (Anniversary Version) [feat. Ed Sheeran & Beéle]", "Shakira, Ed Sheeran, Beéle", 3, 10),
                t("y1", "Young And Beautiful", "Lana Del Rey", 2, 8),
                t("l", "Levitating (feat. DaBaby)", "Dua Lipa", 2, 7),
                t("y2", "Young and Beautiful", "Lana Del Rey", 2, 8),
                t("s", "Summertime Sadness", "Lana Del Rey", 2, 8),
                t("b", "Born To Die", "Lana Del Rey", 1, 4),
            ),
            emptyList(), "Mostly afternoons this week", 15, 2u, 0u,
        )
        // the duplicate folds into one row with both play counts
        val merged = RecapRenderer.mergeSameSongs(r.topTracks)
        assertEquals(5, merged.size)
        assertEquals("y1", merged[0].track.id)
        assertEquals(4u, merged[0].plays)
        save("recap-ben-week.png", RecapRenderer.render(fonts, r, mono, art()))
    }

    @Test fun sparse() {
        val r = Recap(
            "week", "Your week", "20 – 21 Oct", 12u, 3u, null,
            NamedCount("", "Dua Lipa", "", 3u, 12u), null,
            listOf(t("l", "Levitating", "Dua Lipa", 2, 8), t("h", "Houdini", "Dua Lipa", 1, 4)),
            emptyList(), "", -1, 0u, 0u,
        )
        save("recap-sparse.png", RecapRenderer.render(fonts, r, mono, null))
    }

    @Test fun longValues() {
        val r = Recap(
            "month", "Your month", "1 – 31 Oct", 7687u, 1204u, -8,
            NamedCount("UCy", "Sushin Shyam, Dabzee, Vedan and the Manjummel Boys Orchestra", "", 300u, 2410u), null,
            (1..5).map { t("x$it", "A Very Long Song Title That Never Ends (Extended Club Mix) Part $it", "Some Artist, Another Artist, A Third One", 40 - it, 100) },
            emptyList(), "Mostly late nights this month", 23, 14u, 0u,
        )
        save("recap-long-month.png", RecapRenderer.render(fonts, r, mono, art()))
    }
}
