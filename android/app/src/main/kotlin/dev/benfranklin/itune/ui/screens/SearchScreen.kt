package dev.benfranklin.itune.ui.screens

import dev.benfranklin.itune.ui.components.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import androidx.compose.ui.graphics.graphicsLayer
import dev.benfranklin.itune.ui.components.SwipeTrackRow
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.History
import androidx.compose.ui.platform.testTag
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.core.SearchResults
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.SectionTitle
import dev.benfranklin.itune.ui.components.TrackRow
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.fmtDur
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.delay

object SearchCache {
    var query = ""
    var results: SearchResults? = null
}

@Composable
fun SearchScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var q by rememberSaveable { mutableStateOf(SearchCache.query) }
    var submitted by remember { mutableStateOf(SearchCache.query) }
    var res by remember { mutableStateOf(SearchCache.results) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }
    val recentPlays by produceState<List<dev.benfranklin.itune.core.Track>>(emptyList()) { value = dev.benfranklin.itune.data.io { App.core.recent(8u) }.orEmpty() }
    LaunchedEffect(Unit) { recents = dev.benfranklin.itune.data.io { App.core.recentSearches(12u) }.orEmpty() }

    LaunchedEffect(q) {
        if (q.isBlank() || q == submitted) {
            sugg = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        sugg = dev.benfranklin.itune.data.io { App.core.searchSuggestions(q) }.orEmpty().take(6)
    }
    LaunchedEffect(submitted) {
        if (submitted.isBlank() || (submitted == SearchCache.query && res != null)) return@LaunchedEffect
        loading = true
        failed = false
        val q0 = submitted
        dev.benfranklin.itune.data.Bg.run { App.core.addRecentSearch(q0) }
        recents = (listOf(q0) + recents.filter { it != q0 }).take(12)
        res = dev.benfranklin.itune.data.io { App.core.search(submitted) }
        failed = res == null
        SearchCache.query = submitted
        SearchCache.results = res
        loading = false
    }
    fun go(text: String) {
        q = text
        submitted = text
        sugg = emptyList()
        focus.clearFocus()
        kb?.hide()
    }

    Column(Modifier.fillMaxSize()) {
        // header collapses while scrolling down (the field stays), springs back on scroll up
        val chromeHidden = dev.benfranklin.itune.ui.components.LocalChrome.current?.hidden == true
        Spacer(Modifier.statusBarsPadding())
        androidx.compose.animation.AnimatedVisibility(
            !chromeHidden,
            enter = androidx.compose.animation.expandVertically(dev.benfranklin.itune.ui.components.glassSpring()) + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(dev.benfranklin.itune.ui.components.glassSpring()) + fadeOut(),
        ) {
            RiseText("Search", T.ui(22.sp, FontWeight.Bold), Modifier.padding(start = 16.dp, top = 22.dp))
        }
        Row(
            Modifier.padding(16.dp, 14.dp).fillMaxWidth().height(48.dp).glass(RoundedCornerShape(24.dp), dark = 0.40f, sheen = 0.07f, rim = 0.65f).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.t2, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(start = 10.dp)) {
                if (q.isEmpty()) Text("Songs, artists, albums", style = T.ui(14.sp, FontWeight.Medium, c.t3))
                BasicTextField(
                    q, { q = it }, singleLine = true, textStyle = T.ui(14.sp, FontWeight.Medium), cursorBrush = SolidColor(c.txt),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go(q) }),
                    modifier = Modifier.fillMaxWidth().testTag("search_field"),
                )
            }
            if (q.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = c.t3, modifier = Modifier.size(20.dp).press { q = ""; sugg = emptyList(); submitted = ""; res = null; SearchCache.query = ""; SearchCache.results = null })
        }
        if (res != null && sugg.isEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                itemsIndexed(listOf("All", "Songs", "Albums", "Artists", "Playlists", "Videos")) { i, f ->
                    ChipButton(f, selected = filter == f, modifier = Modifier.enter(i).testTag("filter_$f")) { filter = f }
                }
            }
        }
        AnimatedContent(targetState = sugg.isNotEmpty(), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "sugg") { showSugg ->
            if (showSugg) {
                Column {
                    sugg.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().enter(i, 20).press(scaleTo = 0.98f) { go(t) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = c.t3, modifier = Modifier.size(16.dp))
                            Text(t, style = T.ui(14.sp, FontWeight.Medium), modifier = Modifier.weight(1f).padding(start = 14.dp))
                            Icon(Icons.Rounded.NorthWest, null, tint = c.t4, modifier = Modifier.size(16.dp).press { q = t })
                        }
                    }
                }
            } else {
                val r = res
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
                    if (loading) item { Shimmers() }
                    if (r == null && !loading && failed && submitted.isNotBlank()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Couldn't search right now. Check your connection.", style = T.ui(13.sp, FontWeight.Medium, c.t3))
                                ChipButton("Try again", modifier = Modifier.padding(top = 12.dp)) { val x = submitted; submitted = ""; SearchCache.query = ""; submitted = x }
                            }
                        }
                    }
                    if (r == null && !loading && !failed) {
                        if (recents.isNotEmpty()) {
                            item {
                                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recent searches", style = T.ui(16.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                                    Text("Clear all", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.press {
                                        val old = recents
                                        s.undoable("Cleared recent searches", apply = { recents = emptyList() }, undo = { recents = old }, commit = { dev.benfranklin.itune.data.Bg.run { App.core.clearRecentSearches() } })
                                    }.testTag("recent_clear"))
                                }
                            }
                            itemsIndexed(recents, key = { _, x -> "rs_$x" }) { i, x ->
                                Row(Modifier.fillMaxWidth().animateItem().press(scaleTo = 0.98f) { go(x) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.History, null, tint = c.t3, modifier = Modifier.size(18.dp))
                                    Text(x, style = T.ui(14.sp, FontWeight.Medium), modifier = Modifier.weight(1f).padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Rounded.Close, "Remove", tint = c.t4, modifier = Modifier.size(32.dp).padding(8.dp).press {
                                        recents = recents - x
                                        dev.benfranklin.itune.data.Bg.run { App.core.removeRecentSearch(x) }
                                    })
                                }
                            }
                        }
                        if (recentPlays.isNotEmpty()) {
                            item { Spacer(Modifier.height(18.dp)); SectionTitle("Recently played") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                                    itemsIndexed(recentPlays) { i, t ->
                                        Column(Modifier.width(96.dp).enter(i).press(scaleTo = 0.96f) { Playback.play(ctx, recentPlays, i, "Recently played") }) {
                                            Artwork(t.thumb, 96.dp, 10.dp)
                                            Text(t.title, style = T.ui(12.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(22.dp)); SectionTitle("Browse moods & genres") }
                        item { MoodGrid { go(it) } }
                    }
                    if (r != null && !loading) {
                        r.correctedQuery?.let { cq -> item { Text("Showing results for \"$cq\"", style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(horizontal = 16.dp)) } }
                        if (r.artists.isNotEmpty() && (filter == "All" || filter == "Artists")) {
                            item { SectionTitle("Artists"); Spacer(Modifier.height(10.dp)) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    itemsIndexed(r.artists) { i, a ->
                                        Column(Modifier.width(84.dp).enter(i).press { s.push(Dest.Artist(a.id)) }.testTag("search_artist"), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Artwork(a.thumb, 76.dp, circle = true)
                                            Text(a.name, style = T.ui(12.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                        val showTracks = r.tracks.filter { if (filter == "Videos") it.albumId == null else true }
                        if (showTracks.isNotEmpty() && (filter == "All" || filter == "Songs" || filter == "Videos")) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Songs"); Spacer(Modifier.height(6.dp)) }
                            itemsIndexed(if (filter == "All") showTracks.take(8) else showTracks) { i, t ->
                                SwipeTrackRow(s, t) { TrackRow(t, i, onClick = { Playback.play(ctx, showTracks, i, "Search · ${submitted}") }, onMore = { s.sheet = Sheet.TrackMenu(t) }, trailing = fmtDur(t.durationS.toInt())) }
                            }
                        }
                        if (r.albums.isNotEmpty() && (filter == "All" || filter == "Albums")) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Albums"); Spacer(Modifier.height(10.dp)) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(r.albums) { i, a ->
                                        Column(Modifier.width(130.dp).enter(i).press(scaleTo = 0.96f, onLongClick = { s.sheet = Sheet.AlbumMenu(a) }) { s.push(Dest.Album(a.id)) }.testTag("search_album")) {
                                            Artwork(a.thumb, 130.dp, 10.dp)
                                            Text(a.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                            Text(listOfNotNull(a.artists, a.year?.toString()).joinToString(" · "), style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        if (r.playlists.isNotEmpty() && (filter == "All" || filter == "Playlists")) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Playlists"); Spacer(Modifier.height(6.dp)) }
                            itemsIndexed(r.playlists) { i, p ->
                                Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f, onLongClick = { s.sheet = Sheet.PlaylistMenu(p) }) { s.push(Dest.Playlist(p.id)) }.testTag("search_playlist").padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Artwork(p.thumb, 46.dp, 7.dp)
                                    Column(Modifier.padding(start = 14.dp)) {
                                        Text(p.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(p.subtitle, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private val MOODS = listOf(
    "Chill", "Focus", "Workout", "Party", "Romance", "Sleep", "Commute", "Sad",
    "Malayalam", "Bollywood", "Tamil", "Hip-Hop", "Pop", "Indie", "Lo-fi", "Classical",
)

@Composable
fun MoodGrid(onPick: (String) -> Unit) {
    val c = LocalColors.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MOODS.chunked(2).forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { k, m ->
                    val i = r * 2 + k
                    Box(
                        Modifier.weight(1f).height(64.dp).enter(i).glass(RoundedCornerShape(14.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f)
                            .border(0.5.dp, c.line, RoundedCornerShape(14.dp)).press(scaleTo = 0.96f) { onPick("$m music") }.testTag("mood_grid_$m"),
                    ) {
                        dev.benfranklin.itune.ui.screens.MoodArt(i % 4, Modifier.align(Alignment.CenterEnd).size(64.dp).graphicsLayer { alpha = 0.55f })
                        Text(m, style = T.ui(14.5.sp, FontWeight.Bold), modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
                    }
                }
            }
        }
    }
}
