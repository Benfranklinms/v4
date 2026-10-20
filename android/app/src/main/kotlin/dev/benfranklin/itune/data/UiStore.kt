package dev.benfranklin.itune.data

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.benfranklin.itune.App
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Tab
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Where the user was (tab, open pages, list scroll positions), persisted so a relaunch after a swipe-away or a
 * process kill comes back to the same place. Saved instance state only covers system-initiated death, so this
 * goes to SharedPreferences. Now Playing's open state is deliberately NOT persisted (it never re-opens by itself).
 */
object UiStore {
    private val sp by lazy { App.instance.getSharedPreferences("ui_state", Context.MODE_PRIVATE) }

    fun scroll(key: String): IntArray? {
        val v = sp.getString("scroll:$key", null) ?: return null
        val p = v.split(',')
        return runCatching { intArrayOf(p[0].toInt(), p[1].toInt()) }.getOrNull()
    }

    fun saveScroll(key: String, index: Int, offset: Int) {
        sp.edit().putString("scroll:$key", "$index,$offset").apply()
        android.util.Log.d("itune-ui", "scroll $key=$index,$offset")
    }

    fun encode(d: Dest): String? = when (d) {
        is Dest.Album -> "album:" + d.id
        is Dest.Artist -> "artist:" + d.id
        is Dest.Playlist -> "playlist:" + d.id
        Dest.Settings -> "settings"
        Dest.Appearance -> "appearance"
        Dest.Downloads -> "downloads"
        Dest.Local -> "local"
        Dest.Diagnostics -> "diagnostics"
        // transient pages (Isa answers, mixes built in memory, import, wrapped) are not restored
        else -> null
    }

    fun decode(s: String): Dest? = when {
        s.startsWith("album:") -> Dest.Album(s.removePrefix("album:"))
        s.startsWith("artist:") -> Dest.Artist(s.removePrefix("artist:"))
        s.startsWith("playlist:") -> Dest.Playlist(s.removePrefix("playlist:"))
        s == "settings" -> Dest.Settings
        s == "appearance" -> Dest.Appearance
        s == "downloads" -> Dest.Downloads
        s == "local" -> Dest.Local
        s == "diagnostics" -> Dest.Diagnostics
        else -> null
    }

    fun save(s: AppState) {
        val stack = s.stack.mapNotNull(::encode)
        sp.edit().putString("tab", s.tab.name).putString("stack", stack.joinToString("\n")).apply()
        android.util.Log.i("itune-ui", "saved tab=${s.tab.name} stack=${stack.joinToString("|")}")
    }

    fun restore(s: AppState) {
        val t = sp.getString("tab", null)?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: return
        s.tab = t
        s.stack.clear()
        sp.getString("stack", "").orEmpty().split('\n').filter { it.isNotEmpty() }.mapNotNullTo(s.stack, ::decode)
        android.util.Log.i("itune-ui", "restored tab=${t.name} stack=${s.stack.size}")
    }
}

/**
 * A LazyListState whose position survives navigation (saveable), relaunches and process kills (persisted under
 * [key]). [ready] is true once the list has its items: the position is re-applied then.
 */
@Composable
fun rememberPersistentListState(key: String, ready: Boolean): LazyListState {
    val st = rememberLazyListState()
    val restoreTo = androidx.compose.runtime.remember(key) {
        if (st.firstVisibleItemIndex > 0 || st.firstVisibleItemScrollOffset > 0) intArrayOf(st.firstVisibleItemIndex, st.firstVisibleItemScrollOffset)
        else UiStore.scroll(key) ?: intArrayOf(0, 0)
    }
    LaunchedEffect(ready) {
        if (ready && (restoreTo[0] > 0 || restoreTo[1] > 0) && st.firstVisibleItemIndex <= restoreTo[0]) {
            runCatching { st.scrollToItem(restoreTo[0], restoreTo[1]) }
            android.util.Log.i("itune-ui", "scroll restored $key=${restoreTo[0]},${restoreTo[1]}")
        }
        if (ready) {
            restoreTo[0] = 0; restoreTo[1] = 0
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            snapshotFlow { st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset }
                .distinctUntilChanged().debounce(350)
                .collect { (i, o) -> UiStore.saveScroll(key, i, o) }
        }
    }
    return st
}
