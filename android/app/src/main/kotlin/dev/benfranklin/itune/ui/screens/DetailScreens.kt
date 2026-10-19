package dev.benfranklin.itune.ui.screens

import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.ArtistPage
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.Glow
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.SectionTitle
import dev.benfranklin.itune.ui.components.TrackRow
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.fmtDur
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.launch

@Composable
fun BackBar(s: AppState, title: String = "", trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ArrowBack, null, tint = c.txt, modifier = Modifier.size(44.dp).padding(10.dp).press { s.pop() })
        Text(title, style = T.ui(15.sp, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** Shared layout for album / playlist / mix pages: art with glow, title, play + shuffle, tracks. */
@Composable
fun TrackListPage(
    s: AppState,
    title: String,
    subtitle: String,
    art: String,
    tracks: List<Track>?,
    source: String,
    circleArt: Boolean = false,
    actions: @Composable () -> Unit = {},
    extra: LazyListScope.() -> Unit = {},
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
    Box(Modifier.fillMaxSize()) {
        Glow(c.glow, Modifier.fillMaxWidth().height(420.dp), alpha = 0.28f, centerY = 0.2f)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset else 0
                    Box(Modifier.graphicsLayer { val k = 1f - (off / 900f).coerceIn(0f, 0.35f); scaleX = k; scaleY = k; alpha = 1f - (off / 700f).coerceIn(0f, 1f) }) {
                        Artwork(art, 220.dp, 14.dp, circle = circleArt)
                    }
                    RiseText(title, T.ui(22.sp, FontWeight.Bold), Modifier.padding(top = 20.dp, start = 24.dp, end = 24.dp), maxLines = 2)
                    Text(subtitle, style = T.ui(13.sp, FontWeight.Medium, c.t2), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp), maxLines = 2)
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                        Box(
                            Modifier.size(54.dp).clip(CircleShape).background(c.accentUi).press { tracks?.takeIf { it.isNotEmpty() }?.let { App.core.setShuffle(false); Playback.play(ctx, it, 0, source) } },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, null, tint = c.onAccent, modifier = Modifier.size(28.dp)) }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(c.surf3).press { tracks?.takeIf { it.isNotEmpty() }?.let { Playback.playShuffled(ctx, it, source) } },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Shuffle, null, tint = c.txt, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (tracks == null) item { Shimmers() }
            tracks?.let { tl ->
                itemsIndexed(tl, key = { i, t -> "$i${t.id}" }) { i, t ->
                    TrackRow(t, i, onClick = { Playback.play(ctx, tl, i, source) }, onMore = { s.sheet = Sheet.TrackMenu(t) }, trailing = fmtDur(t.durationS.toInt()))
                }
            }
            extra()
        }
        Box(Modifier.fillMaxWidth().background(if (collapsed) c.bg.copy(alpha = 0.92f) else c.bg.copy(alpha = 0f))) {
            BackBar(s, if (collapsed) title else "")
        }
    }
}

@Composable
fun AlbumScreen(s: AppState, id: String) {
    var page by remember { mutableStateOf<dev.benfranklin.itune.core.AlbumPage?>(null) }
    var saved by remember { mutableStateOf(App.core.isSaved("album", id)) }
    LaunchedEffect(id) { page = runCatching { App.core.album(id) }.getOrNull() }
    val a = page?.album
    TrackListPage(
        s, a?.title ?: "", listOfNotNull(a?.artists, a?.year?.toString(), a?.kind).joinToString(" · "), a?.thumb ?: "", page?.tracks, a?.title ?: "Album",
        actions = {
            SaveButton(saved) {
                saved = !saved
                a?.let { App.core.saveAlbum(it, saved) }
            }
        },
    )
}

@Composable
fun PlaylistScreen(s: AppState, id: String) {
    var page by remember { mutableStateOf<dev.benfranklin.itune.core.PlaylistPage?>(null) }
    val local = id.startsWith("local:") || id.startsWith("import:") || id == "liked"
    var saved by remember { mutableStateOf(App.core.isSaved("playlist", id)) }
    LaunchedEffect(id) { page = runCatching { App.core.playlist(id) }.getOrNull() }
    val p = page?.playlist
    TrackListPage(
        s, p?.title ?: "", listOfNotNull(p?.subtitle, p?.trackCount?.let { "$it songs" }).joinToString(" · "), p?.thumb ?: "", page?.tracks, p?.title ?: "Playlist",
        actions = {
            if (!local) SaveButton(saved) {
                saved = !saved
                p?.let { App.core.savePlaylist(it, saved) }
            } else if (id != "liked") {
                val c = LocalColors.current
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.surf3).press { App.core.deletePlaylist(id); s.pop(); s.toast("Playlist deleted") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Delete, null, tint = c.txt, modifier = Modifier.size(20.dp))
                }
            }
        },
    )
}

@Composable
fun MixScreen(s: AppState, title: String, tracks: List<Track>) {
    TrackListPage(s, title, tracks.map { it.artists.split(",")[0] }.distinct().take(4).joinToString(", "), tracks.firstOrNull()?.thumb ?: "", tracks, title)
}

@Composable
private fun SaveButton(saved: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.surf3).press(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null, tint = c.txt, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ArtistScreen(s: AppState, id: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<ArtistPage?>(null) }
    var saved by remember { mutableStateOf(App.core.isSaved("artist", id)) }
    LaunchedEffect(id) { page = runCatching { App.core.artist(id) }.getOrNull() }
    val a = page
    TrackListPage(
        s, a?.artist?.name ?: "", a?.artist?.subtitle ?: "", a?.artist?.thumb ?: "", a?.topTracks, a?.artist?.name ?: "Artist", circleArt = true,
        actions = { SaveButton(saved) { saved = !saved; a?.let { App.core.saveArtist(it.artist, saved) } } },
    ) {
        if (a != null && a.albums.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)); SectionTitle("Albums & singles"); Spacer(Modifier.height(10.dp)) }
            item { AlbumRow(s, a.albums) }
        }
        if (a != null && a.similar.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)); SectionTitle("Fans also like"); Spacer(Modifier.height(10.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(a.similar) { i, x ->
                        Column(Modifier.width(84.dp).enter(i).press { s.push(Dest.Artist(x.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(x.thumb, 76.dp, circle = true)
                            Text(x.name, style = T.ui(12.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        if (a?.description != null) {
            item { Text(a.description!!, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(20.dp), maxLines = 8, overflow = TextOverflow.Ellipsis) }
        }
        @Suppress("UNUSED_EXPRESSION") ctx
    }
}

@Composable
fun AlbumRow(s: AppState, albums: List<Album>) {
    val c = LocalColors.current
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(albums) { i, a ->
            Column(Modifier.width(130.dp).enter(i).press(scaleTo = 0.96f) { s.push(Dest.Album(a.id)) }) {
                Artwork(a.thumb, 130.dp, 10.dp)
                Text(a.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(listOfNotNull(a.year?.toString(), a.kind).joinToString(" · "), style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1)
            }
        }
    }
}

@Composable
fun DownloadsScreen(s: AppState) {
    var items by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(Unit) { items = runCatching { App.core.downloads().map { it.track } }.getOrDefault(emptyList()) }
    TrackListPage(s, "Downloads", "${items?.size ?: 0} songs · play offline", items?.firstOrNull()?.thumb ?: "", items, "Downloads")
}

/** Local audio files from MediaStore, mixed into the same queue as streaming tracks. */
@Composable
fun LocalFilesScreen(s: AppState) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    var items by remember { mutableStateOf<List<Track>?>(runCatching { App.core.localTracks() }.getOrNull()?.takeIf { it.isNotEmpty() }) }
    val scope = rememberCoroutineScope()
    fun scan() {
        scope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scanMediaStore(ctx) }
            App.core.setLocalTracks(list)
            items = list
            App.prefs.localFiles.value = true
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scan() else items = emptyList() }
    LaunchedEffect(Unit) {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) scan() else perm.launch(p)
    }
    TrackListPage(s, "On this phone", "${items?.size ?: 0} files", "", items, "On this phone") {
        if (items?.isEmpty() == true) item { Text("No audio files found.", style = T.ui(13.sp, FontWeight.Medium, c.t3), modifier = Modifier.padding(24.dp)) }
    }
}

fun scanMediaStore(ctx: android.content.Context): List<Track> {
    val out = ArrayList<Track>()
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
    )
    ctx.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cur ->
        while (cur.moveToNext()) {
            val id = cur.getLong(0)
            val item = ContentUris.withAppendedId(uri, id)
            val art = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), cur.getLong(5))
            out += Track(
                id = "local:$item", title = cur.getString(1) ?: "Unknown", artists = cur.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                artistId = null, album = cur.getString(3), albumId = null, durationS = (cur.getLong(4) / 1000).toUInt(), thumb = art.toString(),
            )
        }
    }
    return out
}

@Suppress("unused")
private val keepShape = RoundedCornerShape(4.dp)
@Suppress("unused")
private val keepIcon = Icons.Rounded.Download
