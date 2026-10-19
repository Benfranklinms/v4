package dev.benfranklin.itune.ui.screens
import dev.benfranklin.itune.ui.components.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.data.Library
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.PlaylistEdits
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T

private val SORTS = listOf("recent" to "Recent", "az" to "A–Z", "played" to "Most songs")

@Composable
fun LibraryScreen(s: AppState) {
    val c = LocalColors.current
    var filter by remember { mutableStateOf("Playlists") }
    val playlists by Library.playlists.collectAsState()
    val saved by Library.savedPlaylists.collectAsState()
    val albums by Library.albums.collectAsState()
    val artists by Library.artists.collectAsState()
    val liked by Library.liked.collectAsState()
    val downloads by Library.downloads.collectAsState()
    val hidden by Library.hidden.collectAsState()
    val pinned by Library.pinned.collectAsState()
    val sort by App.prefs.librarySort.state.collectAsState()
    val grid by App.prefs.libraryGrid.state.collectAsState()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { Library.refresh() }

    val lists: List<Playlist> = remember(playlists, saved, hidden, pinned, sort) {
        val all = (playlists.orEmpty() + saved).filter { it.id !in hidden }
        val sorted = when (sort) {
            "az" -> all.sortedBy { it.title.lowercase() }
            "played" -> all.sortedByDescending { it.trackCount ?: 0uL }
            else -> all
        }
        sorted.sortedByDescending { it.id in pinned }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("library_list"), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RiseText(if (selecting) "${selected.size} selected" else "Library", T.ui(22.sp, FontWeight.Bold), Modifier.weight(1f))
                    if (selecting) {
                        Icon(Icons.Rounded.Close, "Done", tint = c.t2, modifier = Modifier.size(44.dp).padding(10.dp).press { selecting = false; selected = emptySet() }.testTag("select_done"))
                    } else {
                        Icon(Icons.Rounded.FileUpload, "Import", tint = c.t2, modifier = Modifier.size(44.dp).padding(11.dp).press { s.push(Dest.Import) }.testTag("lib_import"))
                        Icon(Icons.Rounded.Add, "New playlist", tint = c.t2, modifier = Modifier.size(44.dp).padding(10.dp).press { s.sheet = Sheet.NamePlaylist() }.testTag("lib_create"))
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(listOf("Playlists", "Albums", "Artists")) { i, f -> ChipButton(f, selected = filter == f, modifier = Modifier.enter(i).testTag("lib_$f")) { filter = f; selecting = false; selected = emptySet() } }
                    }
                    if (filter == "Playlists") {
                        Icon(Icons.Rounded.Sort, "Sort", tint = c.t2, modifier = Modifier.size(40.dp).padding(10.dp).press {
                            val i = SORTS.indexOfFirst { it.first == sort }
                            val n = SORTS[(i + 1) % SORTS.size]
                            App.prefs.librarySort.value = n.first
                            s.toast("Sorted by ${n.second}")
                        }.testTag("lib_sort"))
                        Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Layout", tint = c.t2, modifier = Modifier.size(40.dp).padding(10.dp).press { App.prefs.libraryGrid.value = !grid }.testTag("lib_grid"))
                        Icon(Icons.Rounded.Checklist, "Select", tint = if (selecting) c.txt else c.t2, modifier = Modifier.padding(end = 10.dp).size(40.dp).padding(10.dp).press { selecting = !selecting; selected = emptySet() }.testTag("lib_select"))
                    }
                }
            }
            if (!selecting) {
                item {
                    Column(Modifier.padding(top = 10.dp)) {
                        Shortcut(Icons.Rounded.Favorite, "Liked Songs", "$liked songs", 0) { s.push(Dest.Playlist("liked")) }
                        Shortcut(Icons.Rounded.DownloadDone, "Downloads", "$downloads songs · offline", 1) { s.push(Dest.Downloads) }
                        Shortcut(Icons.Rounded.PhoneAndroid, "On this phone", "Local files", 2) { s.push(Dest.Local) }
                    }
                }
            }
            when (filter) {
                "Playlists" -> {
                    if (playlists == null) item { SkeletonRows(5) }
                    else if (lists.isEmpty()) item {
                        EmptyState("No playlists yet", "Create one, or bring yours over from Spotify or Apple Music.", "New playlist") { s.sheet = Sheet.NamePlaylist() }
                    }
                    else if (grid && !selecting) {
                        items(lists.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                            Row(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { p ->
                                    Column(Modifier.weight(1f).press(scaleTo = 0.96f, onLongClick = { s.sheet = Sheet.PlaylistMenu(p) }) { s.push(Dest.Playlist(p.id)) }) {
                                        Box {
                                            Artwork(p.thumb, 1.dp, 12.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                                            if (p.id in pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = c.txt, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
                                        }
                                        Text(p.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                        Text(p.trackCount?.let { "$it songs" } ?: p.subtitle, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        items(lists, key = { it.id }) { p ->
                            val on = p.id in selected
                            Row(
                                Modifier.fillMaxWidth().animateItem()
                                    .press(scaleTo = 0.98f, onLongClick = { if (selecting) selected = selected + p.id else s.sheet = Sheet.PlaylistMenu(p) }) {
                                        if (selecting) selected = if (on) selected - p.id else selected + p.id else s.push(Dest.Playlist(p.id))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp).testTag("pl_${p.title}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selecting) {
                                    Box(
                                        Modifier.padding(end = 12.dp).size(22.dp).clip(CircleShape).background(if (on) c.txt else c.surf3).border(0.6.dp, c.line3, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Icon(Icons.Rounded.Check, null, tint = c.bg, modifier = Modifier.size(14.dp)) }
                                }
                                Artwork(p.thumb, 52.dp, 8.dp)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (p.id in pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = c.t2, modifier = Modifier.padding(end = 4.dp).size(13.dp))
                                        Text(p.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(listOfNotNull(p.subtitle, p.trackCount?.let { "$it songs" }).joinToString(" · "), style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                }
                                if (!selecting) Icon(Icons.Rounded.MoreHoriz, "More", tint = c.t3, modifier = Modifier.size(32.dp).padding(5.dp).press { s.sheet = Sheet.PlaylistMenu(p) })
                            }
                        }
                    }
                }
                "Albums" -> {
                    if (albums.isEmpty()) item { EmptyState("No saved albums", "Long-press an album and choose Save to Library.", "Search") { s.select(dev.benfranklin.itune.ui.Tab.Search) } }
                    items(albums, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth().animateItem().press(scaleTo = 0.98f, onLongClick = { s.sheet = Sheet.AlbumMenu(a) }) { s.push(Dest.Album(a.id)) }.testTag("lib_album").padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(a.thumb, 52.dp, 8.dp)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(a.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                            }
                        }
                    }
                }
                else -> {
                    if (artists.isEmpty()) item { EmptyState("No artists yet", "Follow artists from their page to see them here.", "Search") { s.select(dev.benfranklin.itune.ui.Tab.Search) } }
                    items(artists, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth().animateItem().press(scaleTo = 0.98f) { s.push(Dest.Artist(a.id)) }.testTag("lib_artist").padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(a.thumb, 52.dp, circle = true)
                            Text(a.name, style = T.ui(14.5.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                }
            }
        }
        // multi-select action bar
        AnimatedVisibility(selecting, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(tween(120))) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 160.dp).fillMaxWidth().height(56.dp).glass(RoundedCornerShape(28.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected.size == lists.size && lists.isNotEmpty()) "Clear" else "Select all",
                    style = T.ui(13.5.sp, FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 14.dp).press { selected = if (selected.size == lists.size) emptySet() else lists.map { it.id }.toSet() }.testTag("select_all"),
                )
                Text(
                    "Untitled",
                    style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2),
                    modifier = Modifier.padding(horizontal = 8.dp).press { selected = lists.filter { it.title.trim().equals("New playlist", true) || it.title.isBlank() }.map { it.id }.toSet() }.testTag("select_untitled"),
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (selected.isEmpty()) c.surf4 else c.txt).press {
                        if (selected.isEmpty()) return@press
                        val del = lists.filter { it.id in selected }
                        selecting = false
                        selected = emptySet()
                        PlaylistEdits.delete(s, del)
                    }.padding(horizontal = 16.dp).testTag("select_delete"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = if (selected.isEmpty()) c.t3 else c.bg, modifier = Modifier.size(18.dp))
                    Text("Delete", style = T.ui(13.5.sp, FontWeight.Bold, if (selected.isEmpty()) c.t3 else c.bg), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = T.ui(16.sp, FontWeight.Bold))
        Text(body, style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.padding(top = 14.dp).height(40.dp).press(onClick = onAction).glass(RoundedCornerShape(20.dp), dark = 0.3f, sheen = 0.1f, rim = 0.7f).padding(horizontal = 18.dp).testTag("empty_action"), contentAlignment = Alignment.Center) {
            Text(action, style = T.ui(13.5.sp, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, sub: String, i: Int, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).glass(RoundedCornerShape(8.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.txt, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = T.ui(14.5.sp, FontWeight.SemiBold))
            Text(sub, style = T.ui(12.sp, FontWeight.Medium, c.t3))
        }
    }
}
