package dev.benfranklin.itune.ui.components

import java.text.Normalizer

/**
 * In-list search (playlist / album / Liked Songs / Downloads): case- and accent-insensitive match on title,
 * artist and album. Every word of the query must appear somewhere in the row. Pure functions, run off the
 * main thread by [TrackListPage].
 */
object TrackFilter {
    private val marks = Regex("\\p{Mn}+")
    private val space = Regex("\\s+")

    /** Lower-cased, accents stripped (é -> e, ñ -> n), whitespace collapsed. */
    fun norm(s: String): String =
        marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().replace(space, " ").trim()

    /** One search key per row: title, artists and album joined. */
    fun key(title: String, artists: String, album: String?): String =
        norm(title) + "\u0001" + norm(artists) + "\u0001" + norm(album.orEmpty())

    /** Indices into [keys] matching [query]; null when the query is blank (no filtering). */
    fun match(keys: List<String>, query: String): List<Int>? {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = ArrayList<Int>()
        for (i in keys.indices) {
            val k = keys[i]
            if (words.all { k.contains(it) }) out.add(i)
        }
        return out
    }
}
