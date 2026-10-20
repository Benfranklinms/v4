package dev.benfranklin.itune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {
    private val keys = listOf(
        TrackFilter.key("Café del Mar", "Energy 52", "Café del Mar"),
        TrackFilter.key("Blinding Lights", "The Weeknd", "After Hours"),
        TrackFilter.key("Señorita", "Shawn Mendes, Camila Cabello", null),
        TrackFilter.key("Hours", "Beabadoobee", "Beatopia"),
    )

    @Test fun blankQueryDoesNotFilter() {
        assertNull(TrackFilter.match(keys, ""))
        assertNull(TrackFilter.match(keys, "   "))
    }

    @Test fun caseAndAccentInsensitive() {
        assertEquals(listOf(0), TrackFilter.match(keys, "CAFE"))
        assertEquals(listOf(0), TrackFilter.match(keys, "café"))
        assertEquals(listOf(2), TrackFilter.match(keys, "senorita"))
        assertEquals(listOf(2), TrackFilter.match(keys, "SEÑOR"))
    }

    @Test fun titleArtistAlbumAndWords() {
        assertEquals(listOf(1), TrackFilter.match(keys, "weeknd"))
        assertEquals(listOf(1, 3), TrackFilter.match(keys, "hours"))
        assertEquals(listOf(1), TrackFilter.match(keys, "hours weeknd"))
        assertEquals(listOf(2), TrackFilter.match(keys, "camila"))
        assertTrue(TrackFilter.match(keys, "zzz")!!.isEmpty())
    }

    @Test fun thousandsOfRowsAreFast() {
        val big = List(5000) { TrackFilter.key("Song $it Ünïcödé", "Artist ${it % 97}", "Album ${it % 13}") }
        val t0 = System.nanoTime()
        val r = TrackFilter.match(big, "unicode artist 5")!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.isNotEmpty())
        assertTrue("took $ms ms", ms < 500)
    }
}
