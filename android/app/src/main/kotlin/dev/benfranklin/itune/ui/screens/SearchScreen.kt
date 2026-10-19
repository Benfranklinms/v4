package dev.benfranklin.itune.ui.screens

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
    var q by rememberSaveable { mutableStateOf(SearchCache.query) }
    var submitted by remember { mutableStateOf(SearchCache.query) }
    var res by remember { mutableStateOf(SearchCache.results) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(q) {
        if (q.isBlank() || q == submitted) {
            sugg = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        sugg = runCatching { App.core.searchSuggestions(q) }.getOrDefault(emptyList()).take(6)
    }
    LaunchedEffect(submitted) {
        if (submitted.isBlank() || (submitted == SearchCache.query && res != null)) return@LaunchedEffect
        loading = true
        res = runCatching { App.core.search(submitted) }.getOrNull()
        SearchCache.query = submitted
        SearchCache.results = res
        loading = false
    }
    fun go(text: String) {
        q = text
        submitted = text
        sugg = emptyList()
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        RiseText("Search", T.ui(22.sp, FontWeight.Bold), Modifier.statusBarsPadding().padding(start = 20.dp, top = 22.dp))
        Row(
            Modifier.padding(20.dp, 14.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.surf).border(0.6.dp, c.line3, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.t2, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(start = 10.dp)) {
                if (q.isEmpty()) Text("Songs, artists, albums", style = T.ui(14.sp, FontWeight.Medium, c.t3))
                BasicTextField(
                    q, { q = it }, singleLine = true, textStyle = T.ui(14.sp, FontWeight.Medium), cursorBrush = SolidColor(c.txt),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go(q) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (q.isNotEmpty()) Icon(Icons.Rounded.Close, null, tint = c.t3, modifier = Modifier.size(20.dp).press { q = ""; sugg = emptyList() })
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
                    if (r == null && !loading) {
                        item {
                            Text("Find anything on YouTube Music. Every song, any order, no ads.", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(24.dp))
                        }
                    }
                    if (r != null && !loading) {
                        r.correctedQuery?.let { cq -> item { Text("Showing results for \"$cq\"", style = T.ui(12.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(horizontal = 20.dp)) } }
                        if (r.artists.isNotEmpty()) {
                            item { SectionTitle("Artists"); Spacer(Modifier.height(10.dp)) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    itemsIndexed(r.artists) { i, a ->
                                        Column(Modifier.width(84.dp).enter(i).press { s.push(Dest.Artist(a.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            Artwork(a.thumb, 76.dp, circle = true)
                                            Text(a.name, style = T.ui(12.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (r.tracks.isNotEmpty()) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Songs"); Spacer(Modifier.height(6.dp)) }
                            itemsIndexed(r.tracks) { i, t ->
                                TrackRow(t, i, onClick = { Playback.play(ctx, r.tracks, i, "Search · ${submitted}") }, onMore = { s.sheet = Sheet.TrackMenu(t) }, trailing = fmtDur(t.durationS.toInt()))
                            }
                        }
                        if (r.albums.isNotEmpty()) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Albums"); Spacer(Modifier.height(10.dp)) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(r.albums) { i, a ->
                                        Column(Modifier.width(130.dp).enter(i).press(scaleTo = 0.96f) { s.push(Dest.Album(a.id)) }) {
                                            Artwork(a.thumb, 130.dp, 10.dp)
                                            Text(a.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                            Text(listOfNotNull(a.artists, a.year?.toString()).joinToString(" · "), style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        if (r.playlists.isNotEmpty()) {
                            item { Spacer(Modifier.height(22.dp)); SectionTitle("Playlists"); Spacer(Modifier.height(6.dp)) }
                            itemsIndexed(r.playlists) { i, p ->
                                Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f) { s.push(Dest.Playlist(p.id)) }.padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
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
