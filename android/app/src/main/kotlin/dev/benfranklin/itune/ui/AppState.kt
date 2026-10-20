package dev.benfranklin.itune.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.ImportResult
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String) { Home("Home"), Search("Search"), Library("Library"), Stats("Stats") }

/** Title / subtitle / art of a tapped tile (header placeholder for album and playlist pages). */
data class Peek(val title: String, val subtitle: String = "", val art: String = "")

sealed interface Dest {
    /** [peek] = what the tapped tile already shows, so the header draws instantly while the page loads. */
    data class Album(val id: String, val peek: Peek? = null) : Dest
    data class Artist(val id: String) : Dest
    data class Playlist(val id: String, val peek: Peek? = null) : Dest
    data class Isa(val prompt: String) : Dest
    data object Settings : Dest
    data object Appearance : Dest
    data object Import : Dest
    data object Downloads : Dest
    data object Local : Dest
    data object Wrapped : Dest
    data object Diagnostics : Dest
    data class Mix(val title: String, val tracks: List<Track>) : Dest
}

sealed interface Sheet {
    data class TrackMenu(val track: Track, val queueIndex: Int? = null, val playlistId: String? = null, val position: Int? = null, val nowPlaying: Boolean = false) : Sheet
    data object Queue : Sheet
    data class Update(val info: UpdateInfo) : Sheet
    data class AddToPlaylist(val track: Track) : Sheet
    data object Speed : Sheet
    data class PlaylistMenu(val playlist: Playlist) : Sheet
    data class AlbumMenu(val album: Album) : Sheet
    /** Name dialog. `tracks` are added to the new playlist; `edit` renames an existing one. */
    data class NamePlaylist(val tracks: List<Track> = emptyList(), val edit: Playlist? = null) : Sheet
    data object Account : Sheet
    data object SleepTimer : Sheet
    /** Stats: the plays inside one bar of the chart (a day or a month). */
    data class StatsDay(val title: String, val from: Long, val to: Long) : Sheet
    data object StatsGoal : Sheet
    data class Recap(val range: String) : Sheet
}

/** Glow with nothing playing: neutral mono, so Adaptive looks like Pure Mono until a track plays. */
val NoTrackGlow = Color(0xFF8A8A8C)

data class Snack(val msg: String, val action: String? = null, val id: Long = System.nanoTime(), val onAction: (() -> Unit)? = null)

/** UI navigation state (single root; transitions are driven from here). */
class AppState {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Dest>()
    var nowPlaying by mutableStateOf(false)
    private val sheetState = mutableStateOf<Sheet?>(null)
    /** Sheets opened from another sheet (a track menu from the Queue): Back returns to the one below. */
    val sheetStack = mutableStateListOf<Sheet>()
    /**
     * The open sheet. Setting it replaces the current one and drops any sheets below (an action finished);
     * use [openChildSheet] to open on top and [closeSheet] to go back one level.
     */
    var sheet: Sheet?
        get() = sheetState.value
        set(v) {
            sheetStack.clear()
            sheetState.value = v
        }

    /** Opens [v] above the current sheet; Back / dismiss on it returns to the current one, as it was. */
    fun openChildSheet(v: Sheet) {
        sheetState.value?.let { sheetStack.add(it) }
        sheetState.value = v
    }

    /** Back / dismiss: closes ONLY the top sheet (never navigates, never touches Now Playing or the page). */
    fun closeSheet() {
        sheetState.value = if (sheetStack.isEmpty()) null else sheetStack.removeAt(sheetStack.lastIndex)
        android.util.Log.i("itune-nav", "sheet closed -> ${sheetState.value?.let { it::class.simpleName } ?: "none"} stack=${stack.size} np=$nowPlaying tab=${tab.name}")
    }

    /** Queue sheet: the Recently played list, kept while the sheet stack is open (instant when coming back). */
    var queueRecent by mutableStateOf<List<dev.benfranklin.itune.core.Track>>(emptyList())
    val queueRecentList = androidx.compose.foundation.lazy.LazyListState()
    /** Last session's crash report, shown once in a glass dialog. */
    var crashReport by mutableStateOf<String?>(null)
    var glow by mutableStateOf(NoTrackGlow)
    var toast by mutableStateOf<String?>(null)
    var snack by mutableStateOf<Snack?>(null)
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
        android.util.Log.i("itune-toast", msg)
        toast = msg
    }

    private var pendingCommit: (() -> Unit)? = null
    private var pendingJob: Job? = null

    /**
     * Destructive action with undo: [apply] runs now (optimistic UI), [commit] runs after the undo window
     * (or when another undoable action starts), [undo] restores the UI if the user taps Undo.
     */
    fun undoable(msg: String, apply: () -> Unit, undo: () -> Unit, commit: () -> Unit) {
        pendingJob?.cancel()
        pendingCommit?.invoke()
        apply()
        var done = false
        val c = { if (!done) { done = true; commit() } }
        pendingCommit = c
        snack = Snack(msg, "Undo") {
            if (!done) {
                done = true
                undo()
            }
            pendingCommit = null
            pendingJob?.cancel()
        }
        pendingJob = App.scope.launch(Dispatchers.Main) {
            delay(5_000)
            c()
            if (pendingCommit === c) pendingCommit = null
        }
    }

    /** Run a pending commit right away (app going to background). */
    fun flushPending() {
        pendingJob?.cancel()
        pendingCommit?.invoke()
        pendingCommit = null
    }
}
