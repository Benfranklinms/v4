package dev.benfranklin.itune.ui.theme

import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.R
import java.io.File

@Immutable
data class ItuneColors(
    val bg: Color, val side: Color, val surf: Color, val surf2: Color, val surf3: Color, val surf4: Color,
    val line: Color, val line2: Color, val line3: Color,
    val txt: Color, val acc: Color, val t2: Color, val t3: Color, val t4: Color,
    val light: Boolean,
    /** Album-art colour for glows/tints (never for text or controls). */
    val glow: Color,
    /** Accent used for the active tab / toggles: mono themes use text colour, accent themes their hue. */
    val accentUi: Color,
    /** Colour drawn on top of accentUi (play button glyph). */
    val onAccent: Color,
)

@Immutable
data class ItuneFonts(val ui: FontFamily, val num: FontFamily, val key: String)

val LocalColors = staticCompositionLocalOf { colorsOf(Palettes.mono, Color(0xFF6A4A8A)) }
val LocalFonts = staticCompositionLocalOf { ItuneFonts(FontFamily.Default, FontFamily.Monospace, "default") }
val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalLyricsScale = staticCompositionLocalOf { 1f }

fun colorsOf(p: Palette, glow: Color): ItuneColors {
    val accentTheme = p.id in setOf("nightgreen", "nightred", "daylight", "adaptive") || p.light && p.acc != p.txt
    val accentUi = when {
        p.id == "adaptive" -> lift(glow, p.light)
        accentTheme -> p.acc
        else -> p.acc
    }
    val onAccent = if (accentUi.luminance() > 0.5f) p.bg.takeIf { !p.light } ?: Color(0xFF0A0A0A) else Color.White
    return ItuneColors(p.bg, p.side, p.surf, p.surf2, p.surf3, p.surf4, p.line, p.line2, p.line3, p.txt, p.acc, p.t2, p.t3, p.t4, p.light, glow, accentUi, onAccent)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Make an album colour usable as an accent: brighten on dark themes, darken on light ones. */
fun lift(c: Color, light: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    hsv[1] = hsv[1].coerceIn(0.35f, 0.8f)
    hsv[2] = if (light) 0.55f else 0.92f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

object FontChoices {
    val all = listOf(
        "manrope" to "Manrope",
        "inter" to "Inter",
        "system" to "System",
        "geist" to "Geist",
        "spacegrotesk" to "Space Grotesk",
        "dmsans" to "DM Sans",
        "jakarta" to "Plus Jakarta Sans",
    )
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int): FontFamily = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val Manrope = FontFamily(
    Font(R.font.manrope_light, FontWeight.Light),
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)
val JetBrains = FontFamily(
    Font(R.font.jetbrainsmono_light, FontWeight.Light),
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_medium, FontWeight.SemiBold),
    Font(R.font.jetbrainsmono_medium, FontWeight.Bold),
)

fun fontFamilyFor(id: String, customPath: String): FontFamily = when (id) {
    "inter" -> variable(R.font.inter)
    "system" -> FontFamily.Default
    "geist" -> variable(R.font.geist)
    "spacegrotesk" -> variable(R.font.space_grotesk)
    "dmsans" -> variable(R.font.dm_sans)
    "jakarta" -> variable(R.font.plus_jakarta_sans)
    "custom" -> runCatching {
        val f = File(customPath)
        if (f.exists()) FontFamily(Typeface.createFromFile(f).let { androidx.compose.ui.text.font.Typeface(it) }) else Manrope
    }.getOrDefault(Manrope)
    else -> Manrope
}

@Composable
fun ItuneTheme(
    paletteId: String,
    followSystem: Boolean,
    pureBlack: Boolean,
    glow: Color,
    fontId: String,
    customFontPath: String,
    customNumbers: Boolean,
    textScale: Float,
    lyricsScale: Float,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    var pal = Palettes.byId(paletteId)
    if (followSystem) pal = if (sysDark) (if (pal.light) Palettes.mono else pal) else (if (pal.light) pal else Palettes.byId("snow"))
    if (pureBlack) pal = Palettes.pureBlack(pal)
    val target = colorsOf(pal, glow)
    val spec = tween<Color>(if (reduceMotion) 0 else 520)
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "pal").value
    val colors = ItuneColors(
        a(target.bg), a(target.side), a(target.surf), a(target.surf2), a(target.surf3), a(target.surf4),
        a(target.line), a(target.line2), a(target.line3), a(target.txt), a(target.acc), a(target.t2), a(target.t3), a(target.t4),
        target.light, animateColorAsState(target.glow, tween(if (reduceMotion) 0 else 900), label = "glow").value,
        a(target.accentUi), a(target.onAccent),
    )
    val ui = remember(fontId, customFontPath) { fontFamilyFor(fontId, customFontPath) }
    val num = if (fontId == "custom" && customNumbers) ui else JetBrains
    val fonts = remember(ui, num, fontId) { ItuneFonts(ui, num, "$fontId|$customFontPath|$customNumbers") }
    val scale by animateFloatAsState(textScale, if (reduceMotion) tween(0) else spring(stiffness = 300f), label = "scale")
    val base = LocalDensity.current
    val density = Density(base.density, base.fontScale * scale)
    val scheme = if (pal.light) lightColorScheme(background = colors.bg, surface = colors.surf, primary = colors.accentUi, onPrimary = colors.onAccent, onBackground = colors.txt, onSurface = colors.txt)
    else darkColorScheme(background = colors.bg, surface = colors.surf, primary = colors.accentUi, onPrimary = colors.onAccent, onBackground = colors.txt, onSurface = colors.txt)
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalFonts provides fonts,
        LocalDensity provides density,
        LocalReduceMotion provides reduceMotion,
        LocalLyricsScale provides lyricsScale,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object T {
    @Composable fun ui(size: TextUnit, w: FontWeight = FontWeight.Medium, color: Color = LocalColors.current.txt, track: TextUnit = 0.sp) =
        TextStyle(fontFamily = LocalFonts.current.ui, fontWeight = w, fontSize = size, color = color, letterSpacing = track)
    @Composable fun num(size: TextUnit, w: FontWeight = FontWeight.Normal, color: Color = LocalColors.current.txt) =
        TextStyle(fontFamily = LocalFonts.current.num, fontWeight = w, fontSize = size, color = color)
}
