package dev.benfranklin.itune.ui.screens

import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.focus.focusRequester
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

private class FilterHits(val list: List<Track>, val idx: List<Int>)

/** Glass search pill pinned in a list's top bar next to Back (same look as the Search tab field, smaller). */
@Composable
private fun ListSearchField(q: String, onChange: (String) -> Unit, autoFocus: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val c = LocalColors.current
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { fr.requestFocus() } }
    Row(
        modifier.fillMaxWidth().height(40.dp).glass(RoundedCornerShape(20.dp), dark = 0.40f, sheen = 0.07f, rim = 0.65f).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Search, null, tint = c.t2, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (q.isEmpty()) Text("Search", style = T.ui(13.5.sp, FontWeight.Medium, c.t3))
            androidx.compose.foundation.text.BasicTextField(
                q, onChange, singleLine = true, textStyle = T.ui(13.5.sp, FontWeight.Medium), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.txt),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr).testTag("list_search"),
            )
        }
        // × clears the text; on an empty field it tucks the pill away again
        Icon(androidx.compose.material.icons.Icons.Rounded.Close, if (q.isEmpty()) "Close search" else "Clear", tint = c.t3, modifier = Modifier.size(28.dp).padding(4.dp).press { if (q.isEmpty()) onClose() else onChange("") }.testTag("list_search_clear"))
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
    searchable: Boolean = true,
    extra: LazyListScope.() -> Unit = {},
) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val reduce = dev.benfranklin.itune.ui.theme.LocalReduceMotion.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // in-list search: filtered off the main thread (keys cached per track list), rows keep their keys so the
    // list springs between full and filtered; a tap plays the FULL list from that song
    var query by androidx.compose.runtime.saveable.rememberSaveable(source) { mutableStateOf("") }
    // the pill stays tucked away (no layout change) until the search button in the header opens it
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable(source) { mutableStateOf(false) }
    val showSearch = searchable && !tracks.isNullOrEmpty() && (searchOpen || query.isNotEmpty())
    val scope = rememberCoroutineScope()
    // where the page was when search opened: the header folds away while searching and the scroll comes back on close
    var savedScroll by remember(source) { mutableStateOf<Pair<Int, Int>?>(null) }
    fun openSearch() {
        savedScroll = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        searchOpen = true
        scope.launch { list.scrollToItem(0) }
    }
    fun closeSearch() {
        query = ""; searchOpen = false; focus.clearFocus()
        val back = savedScroll; savedScroll = null
        scope.launch { list.scrollToItem(back?.first ?: 0, back?.second ?: 0) }
    }
    // Back closes search first (after the keyboard), never the page; sheets / Now Playing keep their own Back
    androidx.activity.compose.BackHandler(enabled = showSearch && s.sheet == null && !s.nowPlaying) { closeSearch() }
    // keyboard height: rows end above the keyboard while it is up, above the mini player / nav otherwise
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    val imeDp = with(density) { imePx.toDp() }
    val navInset = dev.benfranklin.itune.ui.components.bottomInset()
    val bottomPad = if (imePx > 0) (if (showSearch) imeDp + 12.dp else androidx.compose.ui.unit.max(imeDp + 12.dp, navInset)) else navInset
    if (showSearch) LaunchedEffect(imePx > 0) { android.util.Log.i("itune-filter", "ime=$imePx search=top") }
    val keyCache = remember { java.util.concurrent.atomic.AtomicReference<Pair<List<Track>, List<String>>?>(null) }
    val hits by androidx.compose.runtime.produceState<FilterHits?>(null, tracks, query) {
        val tl = tracks
        val q = query
        if (tl == null || q.isBlank()) { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val cached = keyCache.get()
            val keys = if (cached != null && cached.first === tl) cached.second
                else tl.map { dev.benfranklin.itune.ui.components.TrackFilter.key(it.title, it.artists, it.album) }.also { keyCache.set(tl to it) }
            FilterHits(tl, dev.benfranklin.itune.ui.components.TrackFilter.match(keys, q).orEmpty())
        }
        android.util.Log.i("itune-filter", "len=${q.length} hits=${value?.idx?.size} of=${tl.size}")
    }
    val filtered = hits?.takeIf { query.isNotBlank() }
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
    val hiddenMap by dev.benfranklin.itune.ui.PendingRemovals.hidden.collectAsState()
    val hiddenRows = playlistId?.let { hiddenMap[it] }.orEmpty()
    // paged playlists / mixes: ask for the next page when the user nears the end of what is loaded
    if (onNearEnd != null) {
        val near by remember { derivedStateOf { val li = list.layoutInfo; val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0; li.totalItemsCount > 0 && last >= li.totalItemsCount - 12 } }
        LaunchedEffect(near, tracks?.size) { if (near) onNearEnd() }
    }
    Box(Modifier.fillMaxSize()) {
        // the header's glow leaves with the header while searching (rows have a solid backing and would show as a band over it)
        val glowA by androidx.compose.animation.core.animateFloatAsState(if (showSearch) 0f else 0.28f, if (reduce) androidx.compose.animation.core.snap<Float>() else androidx.compose.animation.core.tween<Float>(260), label = "glowA")
        Glow(c.glow, Modifier.fillMaxWidth().height(420.dp), alpha = glowA, centerY = 0.2f)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPad)) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  // while searching the art / title / buttons fold up under the top bar so the rows sit right under the pill
                  androidx.compose.animation.AnimatedVisibility(
                    !showSearch,
                    enter = if (reduce) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.expandVertically(dev.benfranklin.itune.ui.components.glassSpring(), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(),
                    exit = if (reduce) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.shrinkVertically(dev.benfranklin.itune.ui.components.glassSpring(), shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(),
                  ) {
                  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        if (searchable && !tracks.isNullOrEmpty()) {
                            Box(
                                Modifier.size(44.dp).glass(CircleShape, dark = 0.34f, sheen = 0.08f, rim = 0.65f)
                                    .then(if (showSearch) Modifier.background(c.txt.copy(alpha = 0.12f), CircleShape) else Modifier)
                                    .press { if (showSearch) closeSearch() else openSearch() }
                                    .testTag("list_search_open"),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Search, "Search", tint = c.txt, modifier = Modifier.size(20.dp)) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                  }
                  }
                  if (showSearch) Spacer(Modifier.height(8.dp))
                }
            }
            if (tracks == null) item { Shimmers() }
            if (tracks != null && tracks.isEmpty()) item {
                EmptyState("Nothing here yet", if (playlistId != null) "Add songs from any song's menu (⋯ › Add to playlist)." else "No songs to show.", "Search") { s.select(dev.benfranklin.itune.ui.Tab.Search) }
            }
            if (filtered != null) {
                val fl = filtered.list
                if (filtered.idx.isEmpty()) item(key = "no_matches") {
                    Text("No matches", style = T.ui(14.sp, FontWeight.SemiBold, c.t2), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp).testTag("list_no_matches"))
                }
                // while filtering: no remove-from-playlist (positions are the full list's), rows keep their keys
                items(filtered.idx, key = { i -> "$i${fl[i].id}" }) { i ->
                    val t = fl[i]
                    if (playlistId != null && i in hiddenRows) return@items
                    Box(if (reduce) Modifier else Modifier.animateItem()) {
                        dev.benfranklin.itune.ui.components.SwipeTrackRow(s, t) {
                            TrackRow(t, i, onClick = { focus.clearFocus(); android.util.Log.i("itune-filter", "play idx=$i of=${fl.size}"); Playback.play(ctx, fl, i, source) }, onMore = { s.sheet = Sheet.TrackMenu(t) }, trailing = fmtDur(t.durationS.toInt()), showArt = !albumMode)
                        }
                    }
                }
            } else tracks?.let { tl ->
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
        val barA = androidx.compose.animation.core.animateFloatAsState(if (collapsed || showSearch) 1f else 0f, dev.benfranklin.itune.ui.components.glassSpring(), label = "dbar")
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = barA.value }.glass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), dark = 0.62f, sheen = 0.04f, rim = 0.5f))
            // the search pill lives up here, next to Back, so it and the rows under it stay above the keyboard
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                dev.benfranklin.itune.ui.components.GlassIconButton(Icons.Rounded.ArrowBack, "Back", modifier = Modifier.padding(start = 8.dp, end = 10.dp), size = 36.dp, iconSize = 20.dp, tag = "back") { if (showSearch) closeSearch() else s.pop() }
                androidx.compose.animation.AnimatedContent(
                    showSearch, Modifier.weight(1f),
                    transitionSpec = {
                        if (reduce) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        else (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.scaleIn(dev.benfranklin.itune.ui.components.glassSpring(), initialScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f))) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "listSearchBar",
                ) { open ->
                    if (open) ListSearchField(query, { query = it }, autoFocus = query.isEmpty(), onClose = { closeSearch() }, modifier = Modifier.padding(end = 8.dp)) { focus.clearFocus() }
                    else Text(if (collapsed) title else "", style = T.ui(15.sp, FontWeight.Bold), modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
    val likedList by dev.benfranklin.itune.data.Library.likedTracks.collectAsState()
    LaunchedEffect(id) { saved = dev.benfranklin.itune.data.io { App.core.isSaved("playlist", id) } ?: false }
    // Liked Songs follows the library flow: a like/unlike anywhere shows here at once (no reload)
    LaunchedEffect(id, likedList, page == null) {
        val l = likedList
        val cur = page
        if (id == "liked" && l != null && cur != null && cur.tracks != l) page = cur.copy(tracks = l, playlist = cur.playlist.copy(trackCount = l.size.toULong()))
    }
    LaunchedEffect(id, changes) {
        if (id == "liked") { likedList?.let { l -> if (page == null) page = dev.benfranklin.itune.core.PlaylistPage(dev.benfranklin.itune.core.Playlist("liked", "Liked Songs", "Playlist", l.firstOrNull()?.thumb ?: "", l.size.toULong(), "local"), l) } }
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
            if (id == "liked") dev.benfranklin.itune.data.Library.likedTracks.value?.let { l -> page = page?.copy(tracks = l) }
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
        s, a?.artist?.name ?: "", a?.artist?.subtitle ?: "", a?.artist?.thumb ?: "", a?.topTracks, a?.artist?.name ?: "Artist", circleArt = true, searchable = false,
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
    val items by dev.benfranklin.itune.data.Library.downloadTracks.collectAsState()
    var selecting by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { dev.benfranklin.itune.data.Library.refresh() }
    val shown = items
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        s.undoable(
            if (ids.size == 1) "Removed download" else "Removed ${ids.size} downloads",
            apply = { dev.benfranklin.itune.data.Library.hideDownloads(ids) },
            undo = { dev.benfranklin.itune.data.Library.restoreDownloads() },
            commit = { dev.benfranklin.itune.data.Library.commitRemoveDownloads(ids) },
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
