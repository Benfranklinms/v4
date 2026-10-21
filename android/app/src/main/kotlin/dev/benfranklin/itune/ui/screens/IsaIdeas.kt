package dev.benfranklin.itune.ui.screens

import dev.benfranklin.itune.App

/** One FOR YOU card in the Isa takeover: what it says, why, the art, and the prompt it sends. */
data class IsaIdea(val idea: String, val reason: String, val art: String, val prompt: String)

/**
 * FOR YOU ideas built from the listener's own stats (core statsPage, read on IO): top songs, top artists at their
 * peak hour, a length idea from the length pill or the usual daily listening, new-to-you artists. Thin history
 * falls back to generic ideas. Cached for 10 minutes; [build] must not run on the main thread.
 */
object IsaIdeas {
    private var cache: List<IsaIdea>? = null
    private var at = 0L
    private var cacheLen = -1

    val generic: List<IsaIdea> = listOf(
        IsaIdea("Something fresh for right now", "picked for this time of day", "", "something fresh for ${partOfDay(hourNow())}"),
        IsaIdea("A 40-minute bus ride", "a quick trip, no skips", "", "40 minutes for the bus, no skips"),
        IsaIdea("Focus, no vocals", "for deep work", "", "focus, no vocals, calm"),
        IsaIdea("Throwback hits", "a classic mix", "", "throwback hits everyone knows"),
        IsaIdea("Rainy day lo-fi", "slow and mellow", "", "rainy day, lo-fi and mellow"),
        IsaIdea("Feel-good Malayalam", "upbeat and warm", "", "feel good Malayalam songs"),
    )

    fun cached(): List<IsaIdea>? = cache

    fun build(lengthIdx: Int): List<IsaIdea> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - at < 10 * 60_000L && cacheLen == lengthIdx) return it }
        val out = mutableListOf<IsaIdea>()
        val page = runCatching { App.core.statsPage("month", tzMin()) }.getOrNull()
        if (page != null && page.plays.toInt() >= 5 && page.topTracks.isNotEmpty()) {
            val top = page.topTracks
            top.getOrNull(0)?.track?.let { t ->
                out += IsaIdea("Like ${t.title}, but slower", "from your top song", t.thumb, "songs like ${t.title} by ${t.artists.substringBefore(",")}, but slower")
            }
            val lenMin = LENGTHS[lengthIdx.coerceIn(0, LENGTHS.lastIndex)].second
            val usual = (((page.avgMinutes.toInt().coerceIn(20, 120)) + 2) / 5) * 5
            val mins = if (lenMin > 0) lenMin else usual
            out += IsaIdea(
                if (mins >= 60) "A ${mins / 60}-hour set of your favourites" else "A $mins-minute session",
                if (lenMin > 0) "fits your length, no skips" else "about your usual day's listening",
                top.getOrNull(1)?.track?.thumb ?: top[0].track.thumb,
                "$mins minutes of my favourites and songs like them, no skips",
            )
            val a = page.topArtists.firstOrNull()
            if (a != null && page.peakHour in 0..23) {
                val part = partOfDay(page.peakHour)
                out += IsaIdea("${part.replaceFirstChar { it.uppercase() }} ${a.name}", "you play this around ${hourLabel(page.peakHour)}", a.thumb, "${a.name} and similar artists for the $part")
            }
            top.getOrNull(1)?.track?.let { t -> out += IsaIdea("More like ${t.title}", "on repeat lately", t.thumb, "songs like ${t.title} by ${t.artists.substringBefore(",")}") }
            page.newArtists.firstOrNull()?.let { n -> out += IsaIdea("More from ${n.name}", "new to you this month", n.thumb, "the best of ${n.name} and similar artists") }
            page.topArtists.getOrNull(1)?.let { b -> out += IsaIdea("${b.name}, deep cuts", "one of your top artists", b.thumb, "${b.name} deep cuts, not the hits") }
        }
        // pad to two full sets with generic ideas
        for (g in generic) { if (out.size >= 6) break; if (out.none { it.idea == g.idea }) out += g }
        cache = out; at = now; cacheLen = lengthIdx
        android.util.Log.i("itune-isa", "ideas n=${out.size} personal=${page?.topTracks?.isNotEmpty() == true}")
        return out
    }

    private fun hourNow() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    fun partOfDay(h: Int) = when (h) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "late night" }
    fun hourLabel(h: Int) = when { h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM" }
}
