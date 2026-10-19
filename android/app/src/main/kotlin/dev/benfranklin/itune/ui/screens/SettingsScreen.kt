package dev.benfranklin.itune.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import dev.benfranklin.itune.App
import androidx.compose.ui.platform.testTag
import dev.benfranklin.itune.BuildConfig
import dev.benfranklin.itune.core.StreamingHealth
import dev.benfranklin.itune.player.DataSources
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.FpsOverlay
import dev.benfranklin.itune.ui.SignInActivity
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.PulseMark
import dev.benfranklin.itune.ui.components.Toggle
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.FontChoices
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.Palettes
import dev.benfranklin.itune.ui.theme.T
import dev.benfranklin.itune.ui.theme.fontFamilyFor
import dev.benfranklin.itune.update.TokenStore
import dev.benfranklin.itune.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Text(title.uppercase(), style = T.ui(10.5.sp, FontWeight.Bold, c.t3, 1.sp), modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.surf).border(0.5.dp, c.line, RoundedCornerShape(16.dp))) { content() }
}

@Composable
fun SRow(title: String, sub: String? = null, i: Int = 0, onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null, lead: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().enter(i, 22).then(if (onClick != null || onLong != null) Modifier.press(scaleTo = 0.985f, onLongClick = onLong) { onClick?.invoke() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = T.ui(14.5.sp, FontWeight.SemiBold))
            if (sub != null) Text(sub, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = LocalColors.current.t3, modifier = Modifier.size(20.dp))

@Composable
fun SettingsScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = App.prefs
    val autoplay by p.autoplay.state.collectAsState()
    val normalize by p.normalize.state.collectAsState()
    val cacheMb by p.cacheMb.state.collectAsState()
    val needsKey by p.updateNeedsKey.state.collectAsState()
    val signedIn by p.signedIn.state.collectAsState()
    val fps by FpsOverlay.enabled.collectAsState()
    var health by remember { mutableStateOf<StreamingHealth?>(null) }
    var testing by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var keyEntry by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(Storage()) }
    var lastfm by remember { mutableStateOf<dev.benfranklin.itune.core.LastfmState?>(null) }
    var lfToken by remember { mutableStateOf<String?>(null) }
    val crossfade by p.crossfadeSec.state.collectAsState()
    val haptics by p.haptics.state.collectAsState()
    LaunchedEffect(Unit) {
        health = dev.benfranklin.itune.data.io { App.core.streamingHealth() }
        lastfm = dev.benfranklin.itune.data.io { App.core.lastfmState() }
        storage = withContext(Dispatchers.IO) { Storage.measure(ctx) }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (p.signedIn.value) scope.launch {
            s.toast("Syncing your library…")
            dev.benfranklin.itune.data.ioResult { App.core.syncLibrary() }.onSuccess { s.toast("Synced ${it.liked} liked songs, ${it.playlists} playlists"); dev.benfranklin.itune.data.Library.refresh() }.onFailure { s.toast("Couldn't sync right now") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Settings")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Group("Account") {
                    SRow(
                        if (signedIn) "Signed in with Google" else "Sign in with Google (optional)",
                        if (signedIn) "Your liked songs and playlists sync into your library" else "Everything works without an account. Sign in to bring your library over.",
                        0, onClick = {
                            if (signedIn) scope.launch {
                                dev.benfranklin.itune.data.ioResult { App.core.syncLibrary() }.onSuccess { s.toast("Synced ${it.liked} liked songs"); dev.benfranklin.itune.data.Library.refresh() }.onFailure { s.toast("Couldn't sync right now") }
                            } else signIn.launch(Intent(ctx, SignInActivity::class.java))
                        },
                    ) { if (signedIn) Text("Sync", style = T.ui(13.sp, FontWeight.SemiBold, c.t2)) else Chevron() }
                    if (signedIn) SRow("Sign out", null, 1, onClick = {
                        p.signedIn.value = false
                        dev.benfranklin.itune.data.Account.info.value = null
                        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
                        scope.launch { dev.benfranklin.itune.data.io { App.core.signOut() } }
                        s.toast("Signed out")
                    })
                }
            }
            item {
                Group("Appearance") {
                    SRow("Theme, text and fonts", Palettes.byId(p.theme.value).name + " · " + (FontChoices.all.firstOrNull { it.first == p.font.value }?.second ?: "Custom"), 0, onClick = { s.push(Dest.Appearance) }) { Chevron() }
                }
            }
            item {
                Group("Playback") {
                    SRow("Autoplay", "Keep playing similar songs when the queue ends, tuned to what you skip and replay", 0) {
                        Toggle(autoplay) { v -> p.autoplay.value = v; dev.benfranklin.itune.data.Bg.run { App.core.setAutoplay(v) } }
                    }
                    SRow("Normalize volume", "Even out loud and quiet tracks", 1) { Toggle(normalize) { p.normalize.value = it } }
                    SRow("Fade between songs", if (crossfade == 0) "Off · songs play back to back with no gap" else "$crossfade s fade out and in", 1)
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = crossfade.toFloat(), steps = 11, valueRange = 0f..12f,
                            onValueChange = { p.crossfadeSec.value = it.roundToInt() },
                            colors = SliderDefaults.colors(thumbColor = c.txt, activeTrackColor = c.txt, inactiveTrackColor = c.surf4), modifier = Modifier.weight(1f).testTag("crossfade"),
                        )
                    }
                    SRow("Haptics", "Gentle taps on key actions", 1) { Toggle(haptics) { p.haptics.value = it } }
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    SRow("Battery", if (unrestricted) "Unrestricted · background playback is safe" else "Set to Unrestricted so OnePlus doesn't stop music in the background", 2, onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                            .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                    }) { if (!unrestricted) Chevron() }
                }
            }
            item {
                Group("Playback status") {
                    val h = health
                    val ok = h != null && (h.workingClient.isNotEmpty() || h.lastError.isEmpty()) && (h.lastErrorAt <= h.lastOkAt || h.lastError.isEmpty())
                    val label = when {
                        testing || h == null -> "Checking…"
                        ok -> "Playback ready"
                        else -> "Can't reach streams, tap to fix"
                    }
                    val fix = {
                        if (!testing) scope.launch {
                            testing = true
                            health = dev.benfranklin.itune.data.io { App.core.streamingSelftest() } ?: dev.benfranklin.itune.data.io { App.core.streamingHealth() }
                            testing = false
                        }
                        Unit
                    }
                    SRow(label, if (!ok && !testing && h != null) "Tries every way of reaching the music again" else null, 0, onClick = if (!ok && !testing) fix else null, lead = {
                        Box(Modifier.padding(end = 12.dp).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (ok && !testing) c.txt else c.t3))
                    }) {
                        if (!ok && !testing && h != null) Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surf3).press { fix() }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("playback_fix")) {
                            Text("Fix", style = T.ui(12.sp, FontWeight.SemiBold))
                        }
                    }
                    SRow("Diagnostics", "Technical details and crash reports", 1, onClick = { s.push(Dest.Diagnostics) }) { Chevron() }
                }
            }
            item {
                Group("Storage") {
                    val steps = listOf(0, 128, 256, 512, 1024)
                    fun label(mb: Int) = when { mb == 0 -> "Off"; mb >= 1024 -> "1 GB"; else -> "$mb MB" }
                    SRow("Stream cache", "${label(cacheMb)} max · upcoming songs count inside it", 0)
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = steps.indexOf(cacheMb).let { if (it < 0) 2 else it }.toFloat(), steps = 3, valueRange = 0f..4f,
                            onValueChange = { p.cacheMb.value = steps[it.roundToInt().coerceIn(0, 4)] },
                            onValueChangeFinished = {
                                val mb = p.cacheMb.value.toUInt()
                                scope.launch(Dispatchers.IO) { runCatching { App.core.setCacheLimitMb(mb) }; DataSources.applyCap(ctx); storage = Storage.measure(ctx) }
                            },
                            colors = SliderDefaults.colors(thumbColor = c.txt, activeTrackColor = c.txt, inactiveTrackColor = c.surf4), modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        StorageStat("Cache", storage.cache); StorageStat("Images", storage.images)
                        StorageStat("Downloads", storage.downloads); StorageStat("Data", storage.data)
                    }
                    SRow("Clear cache", "Downloads are kept", 2, onClick = {
                        scope.launch(Dispatchers.IO) {
                            DataSources.clear(ctx); runCatching { App.core.clearCache() }
                            runCatching { coil.Coil.imageLoader(ctx).let { it.diskCache?.clear(); it.memoryCache?.clear() } }
                            storage = Storage.measure(ctx)
                            withContext(Dispatchers.Main) { s.toast("Cache cleared") }
                        }
                    })
                    var confirmAll by remember { mutableStateOf(false) }
                    SRow(if (confirmAll) "Tap again to remove all downloads" else "Remove all downloads", "Frees " + android.text.format.Formatter.formatShortFileSize(ctx, storage.downloads), 3, onClick = {
                        if (!confirmAll) { confirmAll = true; return@SRow }
                        confirmAll = false
                        s.undoable("Removed all downloads", apply = {}, undo = {}, commit = {
                            scope.launch(Dispatchers.IO) { runCatching { App.core.removeAllDownloads() }; storage = Storage.measure(ctx); dev.benfranklin.itune.data.Library.refresh() }
                        })
                    })
                }
            }
            item {
                Group("Library") {
                    SRow("Import playlists", "From a public Spotify or Apple Music link", 0, onClick = { s.push(Dest.Import) }) { Chevron() }
                    SRow("Local files", "Music on this phone, mixed with streaming", 1, onClick = { s.push(Dest.Local) }) { Chevron() }
                    SRow("Downloads", null, 2, onClick = { s.push(Dest.Downloads) }) { Chevron() }
                }
            }
            if (lastfm?.available == true) item {
                Group("Last.fm") {
                    val lf = lastfm!!
                    if (lf.username.isEmpty()) {
                        SRow(
                            if (lfToken == null) "Connect Last.fm" else "I've approved it",
                            if (lfToken == null) "Scrobble what you play" else "Finish connecting after approving in the browser", 0,
                            onClick = {
                                scope.launch {
                                    val tok = lfToken
                                    if (tok == null) {
                                        dev.benfranklin.itune.data.ioResult { App.core.lastfmBeginAuth() }.onSuccess { a ->
                                            lfToken = a.token
                                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(a.url))) }
                                        }.onFailure { s.toast("Couldn't reach Last.fm") }
                                    } else {
                                        dev.benfranklin.itune.data.ioResult { App.core.lastfmCompleteAuth(tok) }
                                            .onSuccess { name -> lfToken = null; s.toast("Scrobbling as $name") }
                                            .onFailure { s.toast("Not approved yet") }
                                    }
                                    lastfm = dev.benfranklin.itune.data.io { App.core.lastfmState() }
                                }
                            },
                        ) { Chevron() }
                    } else {
                        SRow("Scrobbling", "${lf.username}${if (lf.pending > 0u) " · ${lf.pending} queued" else ""}", 0) {
                            Toggle(lf.enabled) { on -> scope.launch { dev.benfranklin.itune.data.io { App.core.lastfmSetEnabled(on) }; lastfm = dev.benfranklin.itune.data.io { App.core.lastfmState() } } }
                        }
                        SRow("Disconnect", null, 1, onClick = { scope.launch { dev.benfranklin.itune.data.io { App.core.lastfmSignOut() }; lastfm = dev.benfranklin.itune.data.io { App.core.lastfmState() } } })
                    }
                }
            }
            item {
                Group("Updates") {
                    SRow("Check for updates", updateStatus, 0, onClick = {
                        scope.launch {
                            updateStatus = "Checking…"
                            when (val r = Updater.check()) {
                                is Updater.Result.Available -> { updateStatus = "Version ${r.info.version} available"; s.sheet = Sheet.Update(r.info) }
                                Updater.Result.UpToDate -> updateStatus = "You're up to date"
                                Updater.Result.NeedsKey -> updateStatus = if (TokenStore.has()) "The access key didn't work" else null
                                is Updater.Result.Failed -> updateStatus = "Couldn't check right now. Try again later."
                            }
                        }
                    })
                    // Only when the repo went private and no key is saved.
                    AnimatedVisibility(needsKey && !TokenStore.has(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        SRow("Add access key", "Needed to keep receiving updates", 1, onClick = { keyEntry = true })
                    }
                    AnimatedVisibility(keyEntry, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surf3).padding(12.dp)) {
                                if (keyText.isEmpty()) Text("Paste key", style = T.ui(13.sp, FontWeight.Medium, c.t3))
                                BasicTextField(keyText, { keyText = it }, singleLine = true, textStyle = T.num(13.sp), cursorBrush = SolidColor(c.txt), modifier = Modifier.fillMaxWidth())
                            }
                            Text("Save", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.padding(start = 14.dp).press {
                                TokenStore.set(keyText)
                                keyText = ""
                                keyEntry = false
                                scope.launch {
                                    when (Updater.check()) {
                                        Updater.Result.NeedsKey -> { TokenStore.set(null); p.updateNeedsKey.value = true; s.toast("That key didn't work") }
                                        else -> { p.updateNeedsKey.value = false; s.toast("Saved") }
                                    }
                                }
                            })
                        }
                    }
                }
            }
            item {
                Group("About") {
                    Box {
                        SRow("Version", "iTune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 0, onLong = {
                            FpsOverlay.enabled.value = !FpsOverlay.enabled.value
                            s.toast(if (FpsOverlay.enabled.value) "FPS overlay on" else "FPS overlay off")
                        }) {
                            Icon(Icons.Rounded.MoreVert, null, tint = c.t3, modifier = Modifier.size(36.dp).padding(8.dp).press { menu = true })
                        }
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.background(c.surf3)) {
                            DropdownMenuItem(text = { Text(if (TokenStore.has()) "Replace access key" else "Set access key", style = T.ui(14.sp)) }, onClick = { menu = false; keyEntry = true })
                            if (TokenStore.has()) DropdownMenuItem(text = { Text("Remove access key", style = T.ui(14.sp)) }, onClick = { menu = false; TokenStore.set(null) })
                            DropdownMenuItem(text = { Text(if (fps) "Hide FPS overlay" else "Show FPS overlay", style = T.ui(14.sp)) }, onClick = { menu = false; FpsOverlay.enabled.value = !fps })
                        }
                    }
                    SRow("Free and unrestricted", "No ads, unlimited skips, background play, downloads. Lyrics from LRCLIB; music from YouTube Music.", 1)
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { PulseMark(Modifier.size(28.dp), c.t4) }
                }
            }
        }
    }
}

@Composable
fun AppearanceScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val p = App.prefs
    val theme by p.theme.state.collectAsState()
    val follow by p.followSystem.state.collectAsState()
    val black by p.pureBlack.state.collectAsState()
    val scale by p.textScale.state.collectAsState()
    val lscale by p.lyricsScale.state.collectAsState()
    val font by p.font.state.collectAsState()
    val customName by p.customFontName.state.collectAsState()
    val customNum by p.customFontNumbers.state.collectAsState()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Custom font"
                val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "custom-${System.currentTimeMillis()}" + if (name.endsWith(".otf", true)) ".otf" else ".ttf")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                android.graphics.Typeface.createFromFile(f) // validate
                withContext(Dispatchers.Main) {
                    p.customFontPath.value = f.absolutePath
                    p.customFontName.value = name.substringBeforeLast('.')
                    p.font.value = "custom"
                }
            }.onFailure { withContext(Dispatchers.Main) { s.toast("That font couldn't be loaded") } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Appearance")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Group("Theme") {
                    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(Palettes.all, key = { _, pl -> pl.id }) { i, pl ->
                            val sel = pl.id == theme
                            Column(Modifier.enter(i, 20).press { p.theme.value = pl.id }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(64.dp, 92.dp).clip(RoundedCornerShape(14.dp)).background(pl.bg)
                                        .border(if (sel) 2.dp else 0.6.dp, if (sel) c.txt else c.line3, RoundedCornerShape(14.dp)).padding(8.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(5.dp)).background(pl.surf3))
                                        Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(pl.txt))
                                        Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(pl.t3))
                                        Spacer(Modifier.weight(1f))
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (pl.id == "adaptive") c.glow else pl.acc))
                                    }
                                }
                                Text(pl.name, style = T.ui(11.sp, if (sel) FontWeight.Bold else FontWeight.Medium, if (sel) c.txt else c.t3), modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    SRow("Follow system", "Switch between a dark and a light theme with the system", 1) { Toggle(follow) { p.followSystem.value = it } }
                    SRow("Pure black", "True black background for dark themes", 2) { Toggle(black) { p.pureBlack.value = it } }
                }
            }
            item {
                Group("Text size") {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Good evening, Ben", style = T.ui(18.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                            Text("${(scale * 100).toInt()}%", style = T.num(12.sp, color = c.t2))
                        }
                        Text("Daily Mix 1 · Sushin Shyam, Hesham", style = T.ui(12.sp, FontWeight.Medium, c.t3))
                        Slider(scale, { p.textScale.value = (it * 20).toInt() / 20f }, valueRange = 0.85f..1.3f, steps = 8,
                            colors = SliderDefaults.colors(thumbColor = c.txt, activeTrackColor = c.txt, inactiveTrackColor = c.surf4, activeTickColor = c.bg, inactiveTickColor = c.t4))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Lyrics", style = T.ui((17 * lscale).sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                            Text("${(lscale * 100).toInt()}%", style = T.num(12.sp, color = c.t2))
                        }
                        Slider(lscale, { p.lyricsScale.value = (it * 20).toInt() / 20f }, valueRange = 0.8f..1.5f, steps = 13,
                            colors = SliderDefaults.colors(thumbColor = c.txt, activeTrackColor = c.txt, inactiveTrackColor = c.surf4, activeTickColor = c.bg, inactiveTickColor = c.t4))
                    }
                }
            }
            item {
                Group("Font") {
                    FontChoices.all.forEachIndexed { i, (id, name) ->
                        val fam = remember(id) { fontFamilyFor(id, "") }
                        Row(Modifier.fillMaxWidth().enter(i, 22).press(scaleTo = 0.985f) { p.font.value = id }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = T.ui(15.sp, FontWeight.SemiBold).copy(fontFamily = fam), modifier = Modifier.weight(1f))
                            if (font == id) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accentUi))
                        }
                    }
                    SRow(if (customName.isNotEmpty()) "Custom · $customName" else "Import a font", ".ttf or .otf from your files", 8, onClick = {
                        if (customName.isNotEmpty() && font != "custom") p.font.value = "custom" else pick.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"))
                    }) { if (font == "custom") Box(Modifier.size(10.dp).clip(CircleShape).background(c.accentUi)) }
                    if (customName.isNotEmpty()) SRow("Use custom font for numbers", "Otherwise numbers stay in JetBrains Mono", 9) { Toggle(customNum) { p.customFontNumbers.value = it } }
                }
            }
        }
    }
}


/** Live storage breakdown for Settings › Storage. */
data class Storage(val cache: Long = 0, val images: Long = 0, val downloads: Long = 0, val data: Long = 0) {
    companion object {
        fun measure(ctx: android.content.Context): Storage {
            fun size(f: java.io.File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val dl = runCatching { App.core.downloads().sumOf { it.bytes.toLong() } }.getOrDefault(0L)
            val media = DataSources.cachedBytes(ctx)
            val coreCache = runCatching { App.core.streamingHealth().cacheBytes.toLong() }.getOrDefault(0L)
            val images = size(ctx.cacheDir.resolve("images"))
            val data = (size(ctx.filesDir) - dl - coreCache).coerceAtLeast(0) + size(java.io.File(ctx.applicationInfo.dataDir, "databases"))
            return Storage(media + coreCache, images, dl, data)
        }
    }
}

@Composable
private fun StorageStat(label: String, bytes: Long) {
    val c = LocalColors.current
    Column {
        Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes), style = T.num(13.sp), color = c.txt)
        Text(label, style = T.ui(11.sp), color = c.t3)
    }
}


/** Settings › Diagnostics: technical details (stream clients, errors) and crash reports, with Copy. */
@Composable
fun DiagnosticsScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("Loading…") }
    LaunchedEffect(Unit) {
        val h = dev.benfranklin.itune.data.io { App.core.streamingHealth() }
        val crashes = withContext(Dispatchers.IO) { dev.benfranklin.itune.CrashLog.history(ctx) }
        text = buildString {
            append("iTune ").append(dev.benfranklin.itune.BuildConfig.VERSION_NAME).append(" (").append(dev.benfranklin.itune.BuildConfig.VERSION_CODE).append(")\n")
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
            append("core ").append(dev.benfranklin.itune.data.Bg.let { runCatching { App.core.version() }.getOrDefault("?") }).append('\n')
            if (h != null) {
                append("\nStreams\n")
                append("working client: ").append(h.workingClient.ifEmpty { "-" }).append('\n')
                append("client order: ").append(h.clients.joinToString(" → ")).append(" → NewPipe\n")
                append("signed in: ").append(h.signedIn).append('\n')
                if (h.lastError.isNotEmpty()) append("last error: ").append(h.lastError).append('\n')
            }
            append("player resolver: ").append(dev.benfranklin.itune.player.Resolver.lastError.ifEmpty { "-" }).append('\n')
            append("\nCrash reports\n").append(crashes.ifBlank { "None" })
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Diagnostics") {
            Text("Copy", style = T.ui(13.5.sp, FontWeight.SemiBold), modifier = Modifier.padding(end = 12.dp).press {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("iTune diagnostics", text))
                s.toast("Copied")
            }.testTag("diag_copy"))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp)) {
            item { Text(text, style = T.num(11.5.sp, color = c.t2)) }
            item {
                Text("Clear crash reports", style = T.ui(13.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(top = 18.dp).press {
                    dev.benfranklin.itune.CrashLog.clearHistory(ctx); s.toast("Cleared"); s.pop()
                })
            }
        }
    }
}
