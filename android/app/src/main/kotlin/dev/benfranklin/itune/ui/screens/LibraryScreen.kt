package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.Artist
import dev.benfranklin.itune.core.LibraryCounts
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LibData(val counts: LibraryCounts, val playlists: List<Playlist>, val saved: List<Playlist>, val albums: List<Album>, val artists: List<Artist>)

@Composable
fun LibraryScreen(s: AppState) {
    val c = LocalColors.current
    var filter by remember { mutableStateOf("Playlists") }
    var data by remember { mutableStateOf<LibData?>(null) }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) {
            runCatching {
                LibData(App.core.libraryCounts(), App.core.playlists(), App.core.savedPlaylists(), App.core.savedAlbums(), App.core.savedArtists())
            }.getOrNull()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RiseText("Library", T.ui(22.sp, FontWeight.Bold), Modifier.weight(1f))
                Icon(Icons.Rounded.FileUpload, null, tint = c.t2, modifier = Modifier.size(44.dp).padding(11.dp).press { s.push(Dest.Import) })
                Icon(Icons.Rounded.Add, null, tint = c.t2, modifier = Modifier.size(44.dp).padding(10.dp).press {
                    App.core.createPlaylist("New playlist", emptyList())
                    s.toast("Playlist created")
                    s.select(dev.benfranklin.itune.ui.Tab.Library)
                })
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                itemsIndexed(listOf("Playlists", "Albums", "Artists")) { i, f -> ChipButton(f, selected = filter == f, modifier = Modifier.enter(i)) { filter = f } }
            }
        }
        val d = data
        item {
            Column(Modifier.padding(top = 10.dp)) {
                Shortcut(Icons.Rounded.Favorite, "Liked Songs", "${d?.counts?.liked ?: 0} songs", 0) { s.push(Dest.Playlist("liked")) }
                Shortcut(Icons.Rounded.DownloadDone, "Downloads", "${d?.counts?.downloads ?: 0} songs · offline", 1) { s.push(Dest.Downloads) }
                Shortcut(Icons.Rounded.PhoneAndroid, "On this phone", "Local files", 2) { s.push(Dest.Local) }
            }
        }
        item {
            AnimatedContent(filter, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "lib") { f ->
                Column {
                    when (f) {
                        "Playlists" -> (d?.playlists.orEmpty() + d?.saved.orEmpty()).forEachIndexed { i, p ->
                            Row(Modifier.fillMaxWidth().enter(i + 3).press(scaleTo = 0.98f) { s.push(Dest.Playlist(p.id)) }.padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(p.thumb, 52.dp, 8.dp)
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(p.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(p.subtitle, p.trackCount?.let { "$it songs" }).joinToString(" · "), style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                }
                            }
                        }
                        "Albums" -> d?.albums.orEmpty().forEachIndexed { i, a ->
                            Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f) { s.push(Dest.Album(a.id)) }.padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(a.thumb, 52.dp, 8.dp)
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(a.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(a.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1)
                                }
                            }
                        }
                        else -> d?.artists.orEmpty().forEachIndexed { i, a ->
                            Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f) { s.push(Dest.Artist(a.id)) }.padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(a.thumb, 52.dp, circle = true)
                                Text(a.name, style = T.ui(14.5.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                    val empty = when (f) {
                        "Playlists" -> d != null && d.playlists.isEmpty() && d.saved.isEmpty()
                        "Albums" -> d != null && d.albums.isEmpty()
                        else -> d != null && d.artists.isEmpty()
                    }
                    if (empty) Text(
                        if (f == "Playlists") "Create one, or import from Spotify / Apple Music." else "Save ${f.lowercase()} with the bookmark button.",
                        style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, sub: String, i: Int, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().enter(i).press(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(c.surf3), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.txt, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = T.ui(14.5.sp, FontWeight.SemiBold))
            Text(sub, style = T.ui(12.sp, FontWeight.Medium, c.t3))
        }
    }
}
