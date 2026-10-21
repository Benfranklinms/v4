package dev.benfranklin.itune.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.testTag

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.Album
import dev.benfranklin.itune.core.Playlist
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.data.Account
import dev.benfranklin.itune.data.Bg
import dev.benfranklin.itune.data.Library
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun shareText(ctx: Context, title: String, url: String) {
    runCatching {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun isLocalList(p: Playlist) = p.id.startsWith("local:") || p.id.startsWith("import:") || p.id.startsWith("isa:")

/** Playlist mutations shared by menus, the Library and the playlist page. All optimistic, with undo. */
object PlaylistEdits {
    /** Notified when a playlist's tracks change (playlist page reloads its diffed list). */
    val trackChanges get() = Library.trackChanges

    fun delete(s: AppState, lists: List<Playlist>) {
        if (lists.isEmpty()) return
        val ids = lists.map { it.id }
        val msg = if (lists.size == 1) "Deleted ${lists[0].title}" else "Deleted ${lists.size} playlists"
        // leave the playlist page if it's the one being deleted
        (s.stack.lastOrNull() as? Dest.Playlist)?.let { if (it.id in ids) s.pop() }
        s.undoable(msg, apply = { Library.hide(ids) }, undo = { Library.unhide(ids) }, commit = { Library.commitDelete(ids) })
    }

    fun removeTrack(s: AppState, playlistId: String, position: Int, t: Track) {
        PendingRemovals.hide(playlistId, position)
        s.undoable(
            "Removed ${t.title}",
            apply = {},
            undo = { PendingRemovals.unhide(playlistId, position) },
            commit = {
                Library.write {
                    App.core.removeFromPlaylist(playlistId, position.toUInt())
                    PendingRemovals.clear(playlistId)
                    trackChanges.value++
                }
                Library.playlists.value = Library.playlists.value?.map { if (it.id == playlistId) it.copy(trackCount = ((it.trackCount ?: 1uL) - 1uL)) else it }
            },
        )
    }
}

/** Track rows hidden on a playlist page while their removal waits for the undo window. */
object PendingRemovals {
    val hidden = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    fun hide(id: String, pos: Int) { hidden.value = hidden.value + (id to (hidden.value[id].orEmpty() + pos)) }
    fun unhide(id: String, pos: Int) { hidden.value = hidden.value + (id to (hidden.value[id].orEmpty() - pos)) }
    fun clear(id: String) { hidden.value = hidden.value - id }
}

@Composable
internal fun PlaylistMenu(s: AppState, p: Playlist) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    val pinned by Library.pinned.collectAsState()
    val local = isLocalList(p)
    fun withTracks(block: (List<Track>) -> Unit) {
        App.scope.launch(Dispatchers.Main) {
            val t = io { App.core.playlist(p.id).tracks }.orEmpty()
            if (t.isEmpty()) s.toast("This playlist is empty") else block(t)
        }
    }
    Column(Modifier.testTag("playlist_menu")) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(p.thumb, 52.dp, 8.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(p.title, style = T.ui(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dev.benfranklin.itune.ui.components.withSongs(p.subtitle, p.trackCount?.toInt()), style = T.ui(12.5.sp, FontWeight.Medium, c.t2), maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        dev.benfranklin.itune.data.Recents.register(p.title, dev.benfranklin.itune.data.Recents.Origin(if (p.id == "liked") "liked" else "playlist", p.id, p.title, p.subtitle, p.thumb))
        MenuRow(Icons.Rounded.PlayArrow, "Play", 0) { s.sheet = null; withTracks { Playback.play(ctx, it, 0, p.title) } }
        MenuRow(Icons.Rounded.Shuffle, "Shuffle", 1) { s.sheet = null; withTracks { Playback.playShuffled(ctx, it, p.title) } }
        MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", 2) {
            s.sheet = null
            withTracks { l -> l.forEach { Playback.addToQueue(ctx, it) }; s.toast("Added ${l.size} songs to the queue") }
        }
        MenuRow(Icons.Rounded.PushPin, if (p.id in pinned) "Unpin" else "Pin to top", 3) { Library.togglePin(p.id); s.sheet = null }
        if (local) {
            MenuRow(Icons.Rounded.Edit, "Rename", 4) { s.sheet = Sheet.NamePlaylist(edit = p) }
        }
        MenuRow(Icons.Rounded.Download, "Download", 5) {
            s.sheet = null
            withTracks { l ->
                s.toast("Downloading ${l.size} songs…")
                App.scope.launch(Dispatchers.Main) {
                    var ok = 0
                    l.forEach { t -> if (Library.download(t).isSuccess) ok++ }
                    s.toast(if (ok == l.size) "Downloaded ${p.title}" else "Downloaded $ok of ${l.size}")
                }
            }
        }
        MenuRow(Icons.Rounded.FileDownloadOff, "Remove download", 6) {
            s.sheet = null
            withTracks { l -> Library.removeDownloads(l.map { it.id }); s.toast("Removed downloads") }
        }
        MenuRow(Icons.Rounded.Share, "Share", 7) {
            s.sheet = null
            shareText(ctx, p.title, if (local) p.title else "https://music.youtube.com/playlist?list=" + p.id)
        }
        MenuRow(Icons.Rounded.Delete, if (local) "Delete" else "Remove from Library", 8) {
            s.sheet = null
            PlaylistEdits.delete(s, listOf(p))
        }
    }
}

@Composable
internal fun AlbumMenu(s: AppState, a: Album) {
    val ctx = LocalContext.current
    val c = LocalColors.current
    fun withTracks(block: (List<Track>) -> Unit) {
        App.scope.launch(Dispatchers.Main) {
            val t = io { App.core.album(a.id).tracks }.orEmpty()
            if (t.isEmpty()) s.toast("Couldn't load this album") else block(t)
        }
    }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(a.id) { saved = io { App.core.isSaved("album", a.id) } ?: false }
    Column {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(a.thumb, 52.dp, 8.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(a.title, style = T.ui(15.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.artists, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        dev.benfranklin.itune.data.Recents.register(a.title, dev.benfranklin.itune.data.Recents.Origin("album", a.id, a.title, a.artists, a.thumb))
        MenuRow(Icons.Rounded.PlayArrow, "Play", 0) { s.sheet = null; withTracks { Playback.play(ctx, it, 0, a.title) } }
        MenuRow(Icons.Rounded.Shuffle, "Shuffle", 1) { s.sheet = null; withTracks { Playback.playShuffled(ctx, it, a.title) } }
        MenuRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", 2) { s.sheet = null; withTracks { l -> l.forEach { Playback.addToQueue(ctx, it) }; s.toast("Added to queue") } }
        MenuRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", 3) { withTracks { s.sheet = Sheet.NamePlaylist(it) } }
        MenuRow(Icons.Rounded.PushPin, if (saved) "Remove from Library" else "Save to Library", 4) {
            val now = !saved
            saved = now
            Bg.run { App.core.saveAlbum(a, now) }
            Library.albums.value = if (now) listOf(a) + Library.albums.value.filter { it.id != a.id } else Library.albums.value.filter { it.id != a.id }
            s.sheet = null
            s.toast(if (now) "Saved to Library" else "Removed from Library")
        }
        MenuRow(Icons.Rounded.Download, "Download", 5) {
            s.sheet = null
            withTracks { l -> s.toast("Downloading ${l.size} songs…"); App.scope.launch(Dispatchers.Main) { l.forEach { Library.download(it) }; s.toast("Downloaded ${a.title}") } }
        }
        MenuRow(Icons.Rounded.FileDownloadOff, "Remove download", 6) { s.sheet = null; withTracks { l -> Library.removeDownloads(l.map { it.id }); s.toast("Removed downloads") } }
        MenuRow(Icons.Rounded.Share, "Share", 7) { s.sheet = null; shareText(ctx, a.title + " · " + a.artists, "https://music.youtube.com/browse/" + a.id) }
    }
}

/** Name dialog for a new playlist (or rename). Nothing is created on cancel or with an empty name. */
@Composable
internal fun NamePlaylistSheet(s: AppState, tracks: List<Track>, edit: Playlist?) {
    val c = LocalColors.current
    var name by remember { mutableStateOf(edit?.title ?: "") }
    var desc by remember { mutableStateOf(edit?.subtitle?.takeIf { it != "Playlist" && !it.startsWith("Imported") } ?: "") }
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // focus the name once the sheet has sprung in, and ask for the keyboard explicitly
        kotlinx.coroutines.delay(120)
        runCatching { fr.requestFocus() }
        kb?.show()
    }
    val ok = name.isNotBlank()
    fun save() {
        if (!ok) return
        val n = name.trim()
        s.sheet = null
        if (edit != null) {
            Library.replace(edit.copy(title = n, subtitle = desc.trim().ifEmpty { edit.subtitle }))
            Bg.run { App.core.editPlaylist(edit.id, n, desc.trim().ifEmpty { edit.subtitle }, "") }
            s.toast("Renamed to $n")
        } else {
            App.scope.launch(Dispatchers.Main) {
                val p = io { App.core.createPlaylistNamed(n, desc, "", tracks) }
                if (p == null) { s.toast("Couldn't create the playlist"); return@launch }
                Library.upsert(p)
                s.toast(if (tracks.isEmpty()) "Created $n" else "Added to $n")
                s.push(Dest.Playlist(p.id))
            }
        }
    }
    Column(Modifier.padding(horizontal = 24.dp).testTag("name_sheet")) {
        Text(if (edit != null) "Rename playlist" else "New playlist", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(vertical = 8.dp))
        Field(name, { name = it.take(80) }, "Playlist name", Modifier.focusRequester(fr).testTag("name_field"), onDone = { save() })
        Spacer(Modifier.height(10.dp))
        Field(desc, { desc = it.take(200) }, "Description (optional)", Modifier.testTag("desc_field"), onDone = { save() })
        Row(Modifier.padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(48.dp).press { s.sheet = null }.glass(RoundedCornerShape(24.dp), dark = 0.30f, sheen = 0.10f, rim = 0.7f).testTag("name_cancel"), contentAlignment = Alignment.Center) {
                Text("Cancel", style = T.ui(15.sp, FontWeight.SemiBold))
            }
            Box(
                Modifier.weight(1f).height(48.dp).press { save() }.then(if (ok) Modifier.clip(RoundedCornerShape(24.dp)).background(c.accentUi) else Modifier.glass(RoundedCornerShape(24.dp), dark = 0.45f, sheen = 0.04f, rim = 0.4f)).testTag("name_save"),
                contentAlignment = Alignment.Center,
            ) { Text(if (edit != null) "Save" else "Create", style = T.ui(15.sp, FontWeight.Bold, if (ok) c.onAccent else c.t3)) }
        }
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val c = LocalColors.current
    Box(
        Modifier.fillMaxWidth().height(50.dp).glass(RoundedCornerShape(14.dp), dark = 0.38f, sheen = 0.05f, rim = 0.6f).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, style = T.ui(14.5.sp, FontWeight.Medium, c.t3))
        BasicTextField(
            value, onChange, singleLine = true, textStyle = T.ui(14.5.sp, FontWeight.SemiBold), cursorBrush = SolidColor(c.txt),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Avatar → Account: Google profile, listening snapshot, Wrapped, Last.fm, sign in/out. The gear opens Settings. */
@Composable
internal fun AccountSheet(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val info by Account.info.collectAsState()
    val signedIn by App.prefs.signedIn.state.collectAsState()
    val name by App.prefs.userName.state.collectAsState()
    var minutes by remember { mutableStateOf<Int?>(null) }
    var topArtist by remember { mutableStateOf<String?>(null) }
    var lastfm by remember { mutableStateOf<dev.benfranklin.itune.core.LastfmState?>(null) }
    LaunchedEffect(Unit) {
        if (signedIn) Account.load(refresh = info == null)
        io { App.core.stats("month", dev.benfranklin.itune.ui.screens.tzMin()) }?.let { st ->
            minutes = st.minutes.toInt()
            topArtist = st.topArtists.firstOrNull()?.name
        }
        lastfm = io { App.core.lastfmState() }
    }
    Column(Modifier.testTag("account_sheet")) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val av = info?.avatar
            if (!av.isNullOrEmpty()) Artwork(av, 56.dp, circle = true)
            else Box(Modifier.size(56.dp).glass(CircleShape, dark = 0.30f, sheen = 0.12f, rim = 0.8f), contentAlignment = Alignment.Center) {
                Text((info?.name ?: name).take(1).uppercase(), style = T.ui(22.sp, FontWeight.Bold))
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(info?.name?.takeIf { it.isNotBlank() } ?: name, style = T.ui(17.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        info?.handle?.isNotBlank() == true -> info!!.handle
                        signedIn -> "Signed in with Google"
                        else -> "Not signed in"
                    },
                    style = T.ui(12.5.sp, FontWeight.Medium, c.t2), maxLines = 1,
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), dark = 0.38f, sheen = 0.07f, rim = 0.6f).padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("This month", style = T.ui(11.5.sp, FontWeight.SemiBold, c.t3))
                Text(minutes?.let { "$it min" } ?: "–", style = T.num(18.sp, FontWeight.Medium))
            }
            Column(Modifier.weight(1f)) {
                Text("Top artist", style = T.ui(11.5.sp, FontWeight.SemiBold, c.t3))
                Text(topArtist ?: "–", style = T.ui(15.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MenuRow(Icons.Rounded.AutoAwesome, "Your month (Wrapped)", 0) { s.sheet = null; s.push(Dest.Wrapped) }
        MenuRow(Icons.Rounded.BarChart, "Stats", 1) { s.sheet = null; s.select(Tab.Stats) }
        lastfm?.takeIf { it.available }?.let { l ->
            MenuRow(Icons.Rounded.Person, if (l.username.isNotEmpty()) "Last.fm · ${l.username}" else "Connect Last.fm", 2) { s.sheet = null; s.push(Dest.Settings) }
        }
        MenuRow(Icons.Rounded.Settings, "Settings", 3, tag = "acct_settings") { s.sheet = null; s.push(Dest.Settings) }
        if (signedIn) {
            MenuRow(Icons.Rounded.Login, "Switch account", 4) {
                s.sheet = null
                runCatching { ctx.startActivity(Intent(ctx, SignInActivity::class.java)) }
            }
            MenuRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", 5) {
                s.sheet = null
                App.prefs.signedIn.value = false
                Account.info.value = null
                runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                App.scope.launch { io { App.core.signOut() } }
                s.toast("Signed out")
            }
        } else {
            MenuRow(Icons.Rounded.Login, "Sign in with Google", 4) {
                s.sheet = null
                runCatching { ctx.startActivity(Intent(ctx, SignInActivity::class.java)) }
            }
        }
    }
}

@Composable
internal fun SleepTimerSheet(s: AppState) {
    val c = LocalColors.current
    val at by Playback.sleepAt.collectAsState()
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Rounded.Bedtime, null, tint = c.txt, modifier = Modifier.size(20.dp))
            Text("Sleep timer", style = T.ui(17.sp, FontWeight.Bold), modifier = Modifier.padding(start = 10.dp))
        }
        if (at != 0L) {
            val label = if (at == Playback.END_OF_TRACK) "Stops at the end of this song" else "Stops in ${((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min"
            Text(label, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            listOf(5, 15, 30, 45, 60).forEach { m ->
                ChipButton("${m}m", mono = true) { Playback.sleepAt.value = System.currentTimeMillis() + m * 60_000L; s.sheet = null; s.toast("Sleep timer: $m min") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            ChipButton("End of song") { Playback.sleepAt.value = Playback.END_OF_TRACK; s.sheet = null; s.toast("Stops after this song") }
            if (at != 0L) ChipButton("Turn off") { Playback.sleepAt.value = 0L; s.sheet = null; s.toast("Sleep timer off") }
        }
    }
}


/** Marker for Settings text fields: the IME closes itself on Done (ImeAction.Done). */
internal fun hideKeyboardHint() {}
