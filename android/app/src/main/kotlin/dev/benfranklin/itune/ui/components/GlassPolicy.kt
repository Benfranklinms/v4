package dev.benfranklin.itune.ui.components

/**
 * Pure decision for live (RenderEffect) blur, kept free of Android types so it is unit-tested on the JVM.
 * Live blur is off on the Android emulator only (its software GPU crashed with blur layers on CI);
 * every real phone gets it. Below API 31 Haze itself draws its fallback tint.
 */
data class BlurDecision(val liveBlur: Boolean, val reason: String)

object GlassPolicy {
    fun decide(hardware: String, fingerprint: String, product: String, sdk: Int): BlurDecision {
        val h = hardware.lowercase()
        val f = fingerprint.lowercase()
        val p = product.lowercase()
        val emu = when {
            h.contains("ranchu") -> "emulator:hardware=ranchu"
            h.contains("goldfish") -> "emulator:hardware=goldfish"
            p.contains("sdk_gphone") || p.startsWith("sdk_") -> "emulator:product=$p"
            f.startsWith("generic") -> "emulator:fingerprint=generic"
            f.contains("/sdk_gphone") || f.contains(":emulator") || f.contains("emulator/") -> "emulator:fingerprint"
            else -> null
        }
        return if (emu != null) BlurDecision(false, emu)
        else BlurDecision(true, if (sdk >= 31) "device:renderEffect(sdk=$sdk)" else "device:hazeFallback(sdk=$sdk)")
    }
}

/**
 * Album-tinted chrome glass (mini player, bottom nav, top bar; approved option B): the current art colour,
 * darkened and desaturated (hue kept, S <= 0.45, or <= 0.08 on Pure Mono so it stays near-mono, V ~0.20 and
 * relative luminance <= 0.03), laid over a ~28dp blur at [ALPHA]. 0.2.2-0.2.6 used 0.82 over 40dp, which hid the
 * content completely (the bars read as opaque pills). At 0.55 what scrolls behind shows through blurred; labels
 * stay >= 4.5:1 (#D0D0D0) and >= 7:1 (white) over typical art, and white stays >= 3:1 (plus its text shadow) even
 * over a pure white backdrop (unit-tested).
 */
object ChromeTint {
    const val ALPHA = 0.55f
    const val NO_TRACK = 0xFF09090A.toInt()
    const val LABEL_INACTIVE = 0xFFD0D0D0.toInt()
    const val LABEL_ACTIVE = 0xFFFFFFFF.toInt()
    private const val MAX_LUM = 0.03

    /** [art] = the art's dominant/vibrant colour (ARGB), null when nothing is playing. */
    fun of(art: Int?, mono: Boolean): Int {
        if (art == null) return NO_TRACK
        val r = ((art shr 16) and 0xFF) / 255.0
        val g = ((art shr 8) and 0xFF) / 255.0
        val b = (art and 0xFF) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> ((g - b) / d).mod(6.0)
            mx == g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6.0
        val s0 = if (mx == 0.0) 0.0 else d / mx
        val s = minOf(s0, if (mono) 0.08 else 0.45)
        var v = 0.20
        var rgb = hsv(h, s, v)
        while (lum(rgb) > MAX_LUM && v > 0.05) { v *= 0.92; rgb = hsv(h, s, v) }
        return argb(rgb)
    }

    /** Colour of [tint] at [alpha] over [backdrop] (sRGB blend, as the GPU composites it). */
    fun over(tint: Int, alpha: Float, backdrop: Int): Int {
        val t = rgbOf(tint); val k = rgbOf(backdrop)
        return argb(DoubleArray(3) { alpha * t[it] + (1 - alpha) * k[it] })
    }

    fun contrast(a: Int, b: Int): Double {
        val la = lum(rgbOf(a)); val lb = lum(rgbOf(b))
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun rgbOf(c: Int) = doubleArrayOf(((c shr 16) and 0xFF) / 255.0, ((c shr 8) and 0xFF) / 255.0, (c and 0xFF) / 255.0)
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    fun lum(c: DoubleArray) = 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])
    private fun argb(c: DoubleArray): Int {
        fun ch(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }
    private fun hsv(h: Double, s: Double, v: Double): DoubleArray {
        val i = Math.floor(h * 6).toInt()
        val f = h * 6 - i
        val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
        return when (((i % 6) + 6) % 6) {
            0 -> doubleArrayOf(v, t, p); 1 -> doubleArrayOf(q, v, p); 2 -> doubleArrayOf(p, v, t)
            3 -> doubleArrayOf(p, q, v); 4 -> doubleArrayOf(t, p, v); else -> doubleArrayOf(v, p, q)
        }
    }
}
