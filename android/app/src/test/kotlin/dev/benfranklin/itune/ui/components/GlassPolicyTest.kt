package dev.benfranklin.itune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Liquid Glass: real phones must get live blur, only the emulator draws the flat fallback. */
class GlassPolicyTest {
    @Test fun phoneGetsLiveBlur() {
        // OnePlus Nord CE5 (Android 15) and a Pixel: real hardware never matches the emulator checks
        val oneplus = GlassPolicy.decide("mt6886", "OnePlus/CPH2719IN/OP5D3BL1:15/AP3A.240617.008/U.1a2b3c4_1-2:user/release-keys", "CPH2719IN", 35)
        assertTrue(oneplus.reason, oneplus.liveBlur)
        val pixel = GlassPolicy.decide("tokay", "google/tokay/tokay:15/AP4A.250105.002/12701944:user/release-keys", "tokay", 35)
        assertTrue(pixel.reason, pixel.liveBlur)
        assertTrue(GlassPolicy.decide("qcom", "samsung/a52qnsxx/a52q:14/UP1A/123:user/release-keys", "a52qnsxx", 34).liveBlur)
    }

    @Test fun emulatorIsFlat() {
        val emu = GlassPolicy.decide("ranchu", "google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036/10931412:userdebug/dev-keys", "sdk_gphone64_x86_64", 34)
        assertFalse(emu.liveBlur)
        assertTrue(emu.reason.startsWith("emulator"))
        assertFalse(GlassPolicy.decide("goldfish_x86", "generic/sdk_x86/generic_x86:9/PSR1/1:userdebug/test-keys", "sdk_x86", 28).liveBlur)
    }

    @Test fun chromeTintKeepsLabelsLegible() {
        val white = 0xFFFFFFFF.toInt()
        for (mono in listOf(false, true)) for (h in 0 until 360 step 5) for (sv in listOf(1f to 1f, 0.6f to 0.9f, 0.3f to 0.5f)) {
            val art = android_hsv(h.toFloat(), sv.first, sv.second)
            val tint = ChromeTint.of(art, mono)
            // worst case: pure white behind the glass
            val bg = ChromeTint.over(tint, ChromeTint.ALPHA, white)
            val c = ChromeTint.contrast(ChromeTint.LABEL_INACTIVE, bg)
            assertTrue("hue $h mono=$mono inactive label $c", c >= 4.5)
            assertTrue(ChromeTint.contrast(ChromeTint.LABEL_ACTIVE, bg) >= 7.0)
        }
        assertEquals(ChromeTint.NO_TRACK, ChromeTint.of(null, false))
    }

    @Test fun monoStaysNearMono() {
        val yellow = 0xFFEACB0E.toInt()
        val t = ChromeTint.of(yellow, true)
        val r = (t shr 16) and 0xFF; val g = (t shr 8) and 0xFF; val b = t and 0xFF
        assertTrue("mono tint too coloured: ${Integer.toHexString(t)}", maxOf(r, g, b) - minOf(r, g, b) <= 6)
        val olive = ChromeTint.of(yellow, false)
        val ro = (olive shr 16) and 0xFF; val bo = olive and 0xFF
        assertTrue("yellow art -> olive glass", ro > bo)
    }

    private fun android_hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s; val x = c * (1 - Math.abs((h / 60f) % 2 - 1)); val m = v - c
        val (r, g, b) = when ((h / 60).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        fun ch(f: Float) = ((f + m) * 255 + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
