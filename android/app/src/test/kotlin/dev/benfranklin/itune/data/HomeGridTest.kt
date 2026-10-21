package dev.benfranklin.itune.data

import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
    private fun t(id: String, title: String, thumb: String = "th:$id") = Track(id, title, "Artist", null, null, null, 200u, thumb)
    private fun pl(id: String, title: String) = Playlist(id, title, "sub", "pt:$id", null, "local")

    @Test fun radioAndItsSongAreOneTile() {
        val song = t("a", "White Mustang (Official Audio)", "wm")
        val radio = Recents.Origin("radio", "White Mustang (Official Audio) radio", Recents.radioTitle("White Mustang (Official Audio) radio"), "Radio", "wm")
        val tiles = HomeGrid.compose(listOf(radio), emptyList(), emptyList(), emptyList(), emptyList(), listOf(song, t("b", "Dusk Till Dawn")))
        assertEquals(listOf("White Mustang Radio", "Dusk Till Dawn"), tiles.map { it.title })
    }

    @Test fun collectionsBeforeSongs() {
        val tiles = HomeGrid.compose(
            listOf(Recents.Origin("playlist", "p1", "Young", "", "x")),
            listOf(pl("p1", "Young"), pl("p2", "Gym"), pl("p3", "Night")),
            listOf(t("l", "Liked one")),
            emptyList(),
            listOf(Album("al", "Born to Die", "Lana", null, "alb", "Album")),
            listOf(t("s1", "Song 1"), t("s2", "Song 2")),
        )
        assertEquals(listOf("Young", "Gym", "Night", "Liked Songs", "Born to Die", "Song 1"), tiles.map { it.title })
    }

    @Test fun sameSongTwoIdsOnce() {
        val tiles = HomeGrid.compose(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(t("a", "White Mustang (Official Video)", "v1"), t("b", "White Mustang", "v2"), t("c", "West Coast")))
        assertEquals(2, tiles.size)
        assertTrue(tiles.map { Recents.norm(it.title) }.toSet().size == tiles.size)
    }
}
