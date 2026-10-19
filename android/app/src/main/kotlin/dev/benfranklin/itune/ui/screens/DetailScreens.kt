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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Check
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
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.launch

@Composable
fun BackBar(s: AppState, title: String = "", trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        dev.benfranklin.itune.ui.components.GlassIconButton(Icons.Rounded.ArrowBack, "Back", modifier = Modifier.padding(start = 8.dp, end = 10.dp), size = 36.dp, iconSize = 20.dp, tag = "back") { s.pop() }
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
    playlistId: String? = null,
    albumMode: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    extra: LazyListScope.() -> Unit = {},
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
    val hiddenMap by dev.benfranklin.itune.ui.PendingRemovals.hidden.collectAsState()
    val hiddenRows = playlistId?.let { hiddenMap[it] }.orEmpty()
    // paged playlists / mixes: ask for the next page when the user nears the end of what is loaded
    if (onNearEnd != null) {
        val near by remember { derivedStateOf { val li = list.layoutInfo; val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0; li.totalItemsCount > 0 && last >= li.totalItemsCount - 12 } }
        LaunchedEffect(near, tracks?.size) { if (near) onNearEnd() }
    }
    Box(Modifier.fillMaxSize()) {
        Glow(c.glow, Modifier.fillMaxWidth().height(420.dp), alpha = 0.28f, centerY = 0.2f)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = dev.benfranklin.itune.ui.components.bottomInset())) {
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
                            Modifier.size(54.dp).clip(CircleShape).background(c.accentUi).press { tracks?.takeIf { it.isNotEmpty() }?.let { Playback.setShuffle(false); Playback.play(ctx, it, 0, source) } ?: s.toast("Nothing to play yet") },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, null, tint = c.onAccent, modifier = Modifier.size(28.dp)) }
                        Box(
                            Modifier.size(44.dp).glass(CircleShape, dark = 0.34f, sheen = 0.08f, rim = 0.65f).press { tracks?.takeIf { it.isNotEmpty() }?.let { Playback.playShuffled(ctx, it, source) } ?: s.toast("Nothing to play yet") },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Shuffle, null, tint = c.txt, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (tracks == null) item { Shimmers() }
            if (tracks != null && tracks.isEmpty()) item {
                EmptyState("Nothing here yet", if (playlistId != null) "Add songs from any song's menu (⋯ › Add to playlist)." else "No songs to show.", "Search") { s.select(dev.benfranklin.itune.ui.Tab.Search) }
            }
            tracks?.let { tl ->
                itemsIndexed(tl, key = { i, t -> "$i${t.id}" }) { i, t ->
                    if (playlistId != null && i in hiddenRows) return@itemsIndexed
                    Box(Modifier.animateItem()) {
                        dev.benfranklin.itune.ui.components.SwipeTrackRow(s, t) {
                            TrackRow(t, i, onClick = { Playback.play(ctx, tl, i, source) }, onMore = { s.sheet = Sheet.TrackMenu(t, playlistId = playlistId, position = i) }, trailing = fmtDur(t.durationS.toInt()), showArt = !albumMode)
                        }
                    }
                }
            }
            if (loadingMore) item(key = "more") { Box(Modifier.fillMaxWidth().padding(vertical = 18.dp).testTag("loading_more"), contentAlignment = Alignment.Center) { Text("Loading more…", style = T.ui(12.sp, FontWeight.Medium, c.t2)) } }
            extra()
        }
        val barA = androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, dev.benfranklin.itune.ui.components.glassSpring(), label = "dbar")
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = barA.value }.glass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), dark = 0.62f, sheen = 0.04f, rim = 0.5f))
            BackBar(s, if (collapsed) title else "")
        }
    }
}

@Composable
fun AlbumScreen(s: AppState, id: String, peek: dev.benfranklin.itune.ui.Peek? = null) {
    var page by remember(id) { mutableStateOf<dev.benfranklin.itune.core.AlbumPage?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        // last cached page at any age first (instant, no network), then revalidate
        dev.benfranklin.itune.data.io { App.core.albumCached(id) }?.let { if (page == null) page = it }
        saved = dev.benfranklin.itune.data.io { App.core.isSaved("album", id) } ?: false
        dev.benfranklin.itune.data.io { App.core.album(id) }?.let { page = it }
    }
    val a = page?.album
    TrackListPage(
        s, a?.title ?: peek?.title ?: "",
        if (a != null) listOfNotNull(a.artists, a.year?.toString(), a.kind).joinToString(" · ") else peek?.subtitle ?: "",
        a?.thumb ?: peek?.art ?: "", page?.tracks, a?.title ?: peek?.title ?: "Album",
        albumMode = true,
        actions = {
            SaveButton(saved) {
                val al = a ?: return@SaveButton
                fun apply(v: Boolean) { saved = v; dev.benfranklin.itune.data.Bg.run { App.core.saveAlbum(al, v) }; dev.benfranklin.itune.data.Library.albums.value = if (v) listOf(al) + dev.benfranklin.itune.data.Library.albums.value.filter { it.id != al.id } else dev.benfranklin.itune.data.Library.albums.value.filter { it.id != al.id } }
                val now = !saved
                apply(now)
                s.snack = dev.benfranklin.itune.ui.Snack(if (now) "Saved to Library" else "Removed from Library", "Undo") { apply(!now) }
            }
        },
    )
}

@Composable
fun PlaylistScreen(s: AppState, id: String, peek: dev.benfranklin.itune.ui.Peek? = null) {
    var page by remember(id) { mutableStateOf<dev.benfranklin.itune.core.PlaylistPage?>(null) }
    val local = id.startsWith("local:") || id.startsWith("import:") || id.startsWith("isa:") || id == "liked"
    var saved by remember { mutableStateOf(false) }
    var hasMore by remember(id) { mutableStateOf(false) }
    var loadingMore by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changes by dev.benfranklin.itune.ui.PlaylistEdits.trackChanges.collectAsState()
    val libLists by dev.benfranklin.itune.data.Library.playlists.collectAsState()
    LaunchedEffect(id) { saved = dev.benfranklin.itune.data.io { App.core.isSaved("playlist", id) } ?: false }
    LaunchedEffect(id, changes) {
        if (!local && page == null) dev.benfranklin.itune.data.io { App.core.playlistCached(id) }?.let { if (page == null) page = it }
        // remote playlists / mixes: the first page comes back at once, the rest pages in while scrolling
        val fresh = dev.benfranklin.itune.data.io { App.core.playlist(id) }
        if (fresh != null) {
            val old = page
            // keep rows already shown from the cache when the fresh first page is shorter (it is only page 1)
            page = if (!local && old != null && old.playlist.id == fresh.playlist.id && old.tracks.size > fresh.tracks.size) {
                val seen = fresh.tracks.mapTo(HashSet()) { it.id }
                fresh.copy(tracks = fresh.tracks + old.tracks.filter { seen.add(it.id) })
            } else fresh
        }
        hasMore = !local && (dev.benfranklin.itune.data.io { App.core.playlistHasMore(id) } ?: false)
    }
    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val more = dev.benfranklin.itune.data.io { App.core.playlistMore(id, 100u) }
            if (more != null) {
                val cur = page
                if (cur != null) {
                    val seen = cur.tracks.mapTo(HashSet()) { it.id }
                    val add = more.tracks.filter { seen.add(it.id) }
                    if (add.isNotEmpty()) {
                        page = cur.copy(tracks = cur.tracks + add)
                        Playback.appendFromSource(cur.playlist.title, add)
                    }
                }
                hasMore = !more.done
            } else hasMore = false
            loadingMore = false
        }
    }
    // reflect renames done from the menu without a reload
    val p = page?.playlist?.let { pp -> libLists?.firstOrNull { it.id == pp.id }?.let { l -> pp.copy(title = l.title, subtitle = l.subtitle) } ?: pp }
    TrackListPage(
        s, p?.title ?: peek?.title ?: "",
        if (p != null) dev.benfranklin.itune.ui.components.withSongs(p.subtitle, page?.tracks?.size) else peek?.subtitle ?: "",
        p?.thumb ?: peek?.art ?: "", page?.tracks, p?.title ?: peek?.title ?: "Playlist",
        playlistId = if (local && id != "liked") id else null,
        onNearEnd = if (hasMore) ({ loadMore() }) else null,
        loadingMore = loadingMore,
        actions = {
            val c = LocalColors.current
            if (!local) SaveButton(saved) {
                val pl = p ?: return@SaveButton
                fun apply(v: Boolean) { saved = v; dev.benfranklin.itune.data.Bg.run { App.core.savePlaylist(pl, v); dev.benfranklin.itune.data.Library.refresh() } }
                val now = !saved
                apply(now)
                s.snack = dev.benfranklin.itune.ui.Snack(if (now) "Saved to Library" else "Removed from Library", "Undo") { apply(!now) }
            }
            if (id != "liked" && p != null) {
                Box(Modifier.size(44.dp).glass(CircleShape, dark = 0.34f, sheen = 0.08f, rim = 0.65f).press { s.sheet = Sheet.PlaylistMenu(p) }.testTag("playlist_more"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, "More", tint = c.txt, modifier = Modifier.size(20.dp))
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
    val k by androidx.compose.animation.core.animateFloatAsState(if (saved) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "save")
    Box(Modifier.size(44.dp).glass(CircleShape, dark = 0.34f, sheen = 0.08f, rim = 0.65f).press(onClick = onClick).testTag("save_button"), contentAlignment = Alignment.Center) {
        Icon(
            if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Remove from Library" else "Save to Library", tint = c.txt,
            modifier = Modifier.size(20.dp).graphicsLayer { val z = 1f + 0.18f * kotlin.math.sin(k * Math.PI.toFloat()); scaleX = z; scaleY = z },
        )
    }
}

@Composable
fun ArtistScreen(s: AppState, id: String) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<ArtistPage?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        dev.benfranklin.itune.data.io { App.core.artistCached(id) }?.let { if (page == null) page = it }
        saved = dev.benfranklin.itune.data.io { App.core.isSaved("artist", id) } ?: false
        dev.benfranklin.itune.data.io { App.core.artist(id) }?.let { page = it }
    }
    val a = page
    TrackListPage(
        s, a?.artist?.name ?: "", a?.artist?.subtitle ?: "", a?.artist?.thumb ?: "", a?.topTracks, a?.artist?.name ?: "Artist", circleArt = true,
        actions = {
            SaveButton(saved) {
                val ar = a ?: return@SaveButton
                fun apply(v: Boolean) { saved = v; dev.benfranklin.itune.data.Bg.run { App.core.saveArtist(ar.artist, v); dev.benfranklin.itune.data.Library.refresh() } }
                val now = !saved
                apply(now)
                s.snack = dev.benfranklin.itune.ui.Snack(if (now) "Saved to Library" else "Removed from Library", "Undo") { apply(!now) }
            }
        },
    ) {
        if (a != null && a.albums.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)); SectionTitle("Albums & singles"); Spacer(Modifier.height(10.dp)) }
            item { AlbumRow(s, a.albums) }
        }
        if (a != null && a.similar.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)); SectionTitle("Fans also like"); Spacer(Modifier.height(10.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(a.similar, key = { k, v -> "$k:" + v.hashCode() }) { i, x ->
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
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(albums, key = { k, v -> "$k:" + v.hashCode() }) { i, a ->
            Column(Modifier.width(130.dp).enter(i).press(scaleTo = 0.96f, onLongClick = { s.sheet = Sheet.AlbumMenu(a) }) { s.push(Dest.Album(a.id, dev.benfranklin.itune.ui.Peek(a.title, a.artists, a.thumb))) }) {
                Artwork(a.thumb, 130.dp, 10.dp)
                Text(a.title, style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(listOfNotNull(a.year?.toString(), a.kind).joinToString(" · "), style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1)
            }
        }
    }
}

@Composable
fun DownloadsScreen(s: AppState) {
    val c = LocalColors.current
    var items by remember { mutableStateOf<List<Track>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { items = dev.benfranklin.itune.data.io { App.core.downloads().map { it.track } }.orEmpty() }
    val shown = items?.filter { it.id !in hidden }
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        s.undoable(
            if (ids.size == 1) "Removed download" else "Removed ${ids.size} downloads",
            apply = { hidden = hidden + ids },
            undo = { hidden = hidden - ids },
            commit = { dev.benfranklin.itune.data.Bg.run { App.core.removeDownloads(ids.toList()) }; dev.benfranklin.itune.data.Library.refresh() },
        )
        selecting = false
        sel = emptySet()
    }
    if (!selecting) {
        TrackListPage(
            s, "Downloads", "${shown?.size ?: 0} songs · play offline", shown?.firstOrNull()?.thumb ?: "", shown, "Downloads",
            actions = {
                if (!shown.isNullOrEmpty()) Box(Modifier.size(44.dp).glass(CircleShape, dark = 0.34f, sheen = 0.08f, rim = 0.65f).press { selecting = true }.testTag("dl_select"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Checklist, "Select", tint = c.txt, modifier = Modifier.size(20.dp))
                }
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            BackBar(s, "${sel.size} selected") {
                Text("Cancel", style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(end = 10.dp).press { selecting = false; sel = emptySet() })
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dev.benfranklin.itune.ui.components.ChipButton(if (sel.size == shown?.size) "Clear" else "Select all") { sel = if (sel.size == shown?.size) emptySet() else shown.orEmpty().map { it.id }.toSet() }
                dev.benfranklin.itune.ui.components.ChipButton("Remove", selected = sel.isNotEmpty(), modifier = Modifier.testTag("dl_remove")) { remove(sel) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dev.benfranklin.itune.ui.components.bottomInset())) {
                itemsIndexed(shown.orEmpty(), key = { _, t -> t.id }) { i, t ->
                    val on = t.id in sel
                    Row(Modifier.fillMaxWidth().animateItem().press(scaleTo = 0.98f) { sel = if (on) sel - t.id else sel + t.id }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(end = 12.dp).size(22.dp).clip(CircleShape).background(if (on) c.txt else c.surf3), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.Check, null, tint = c.bg, modifier = Modifier.size(14.dp))
                        }
                        Artwork(t.thumb, 46.dp, 7.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(t.title, style = T.ui(14.5.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Local audio files from MediaStore, mixed into the same queue as streaming tracks. */
@Composable
fun LocalFilesScreen(s: AppState) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    var items by remember { mutableStateOf<List<Track>?>(null) }
    val scope = rememberCoroutineScope()
    fun scan() {
        scope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scanMediaStore(ctx) }
            dev.benfranklin.itune.data.io { App.core.setLocalTracks(list) }
            items = list
            App.prefs.localFiles.value = true
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scan() else items = emptyList() }
    LaunchedEffect(Unit) {
        dev.benfranklin.itune.data.io { App.core.localTracks() }?.takeIf { it.isNotEmpty() }?.let { if (items == null) items = it }
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
