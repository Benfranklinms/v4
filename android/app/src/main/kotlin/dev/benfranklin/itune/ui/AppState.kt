package dev.benfranklin.itune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.core.UpdateInfo

enum class Tab(val label: String) { Home("Home"), Search("Search"), Library("Library"), Stats("Stats") }

sealed interface Dest {
    data class Album(val id: String) : Dest
    data class Artist(val id: String) : Dest
    data class Playlist(val id: String) : Dest
    data class Isa(val prompt: String) : Dest
    data object Settings : Dest
    data object Appearance : Dest
    data object Import : Dest
    data object Downloads : Dest
    data object Local : Dest
    data object Wrapped : Dest
    data class Mix(val title: String, val tracks: List<Track>) : Dest
}

sealed interface Sheet {
    data class TrackMenu(val track: Track, val queueIndex: Int? = null) : Sheet
    data object Queue : Sheet
    data class Update(val info: UpdateInfo) : Sheet
    data class AddToPlaylist(val track: Track) : Sheet
    data object Speed : Sheet
    data object WhyThisSong : Sheet
}

/** Glow with nothing playing: neutral mono, so Adaptive looks like Pure Mono until a track plays. */
val NoTrackGlow = Color(0xFF8A8A8C)

/** UI navigation state (single root; transitions are driven from here). */
class AppState {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Dest>()
    var nowPlaying by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var glow by mutableStateOf(NoTrackGlow)
    var toast by mutableStateOf<String?>(null)
    var forward by mutableStateOf(true)
    var updateNeedsKey by mutableStateOf(false)

    fun push(d: Dest) {
        forward = true
        nowPlaying = false
        stack.add(d)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun select(t: Tab) {
        forward = t.ordinal >= tab.ordinal
        if (t == tab && stack.isNotEmpty()) stack.clear()
        tab = t
        stack.clear()
    }

    fun toast(msg: String) {
        toast = msg
    }
}
