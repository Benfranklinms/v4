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
import dev.benfranklin.itune.App
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
fun SRow(title: String, sub: String? = null, i: Int = 0, onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().enter(i, 22).then(if (onClick != null || onLong != null) Modifier.press(scaleTo = 0.985f, onLongClick = onLong) { onClick?.invoke() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        health = App.core.streamingHealth()
        cacheBytes = withContext(Dispatchers.IO) { DataSources.cachedBytes(ctx) }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (p.signedIn.value) scope.launch {
            s.toast("Syncing your library…")
            runCatching { App.core.syncLibrary() }.onSuccess { s.toast("Synced ${it.liked} liked songs, ${it.playlists} playlists") }.onFailure { s.toast("Sync failed") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(s, "Settings")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
            item {
                Group("Account") {
                    SRow(
                        if (signedIn) "Signed in to YouTube Music" else "Sign in (optional)",
                        if (signedIn) "Liked songs and playlists sync into your library" else "Everything works without an account. Sign in to sync your library and reduce bot checks.",
                        0, onClick = {
                            if (signedIn) scope.launch {
                                runCatching { App.core.syncLibrary() }.onSuccess { s.toast("Synced ${it.liked} liked songs") }.onFailure { s.toast("Sync failed: ${it.message}") }
                            } else signIn.launch(Intent(ctx, SignInActivity::class.java))
                        },
                    ) { if (signedIn) Text("Sync", style = T.ui(13.sp, FontWeight.SemiBold, c.t2)) else Chevron() }
                    if (signedIn) SRow("Sign out", null, 1, onClick = { scope.launch { App.core.signOut(); p.signedIn.value = false; android.webkit.CookieManager.getInstance().removeAllCookies(null) } })
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
                        Toggle(autoplay) { p.autoplay.value = it; App.core.setAutoplay(it) }
                    }
                    SRow("Normalize volume", "Even out loud and quiet tracks", 1) { Toggle(normalize) { p.normalize.value = it } }
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                    SRow("Battery", if (unrestricted) "Unrestricted · background playback is safe" else "Set to Unrestricted so OnePlus doesn't stop music in the background", 2, onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                            .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                    }) { if (!unrestricted) Chevron() }
                }
            }
            item {
                Group("Streaming") {
                    val h = health
                    val ok = h != null && h.workingClient.isNotEmpty() && (h.lastErrorAt <= h.lastOkAt)
                    SRow(
                        "Streaming health",
                        when {
                            h == null -> "…"
                            h.workingClient.isEmpty() && h.lastError.isEmpty() -> "Not tested yet · clients: ${h.clients.joinToString(" → ")} → NewPipe"
                            ok -> "Working · client ${h.workingClient}"
                            else -> "Last error: ${h.lastError.take(160)}"
                        },
                        0,
                    ) {
                        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surf3).press {
                            if (!testing) scope.launch {
                                testing = true
                                health = runCatching { App.core.streamingSelftest() }.getOrNull() ?: App.core.streamingHealth()
                                testing = false
                            }
                        }.padding(horizontal = 12.dp, vertical = 6.dp)) { Text(if (testing) "Testing…" else "Retry", style = T.ui(12.sp, FontWeight.SemiBold)) }
                    }
                    SRow("Offline cache", "${cacheBytes / (1024 * 1024)} MB of $cacheMb MB · next songs and recent plays", 1)
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = cacheMb.toFloat(), onValueChange = { p.cacheMb.value = (it / 256f).toInt().coerceAtLeast(1) * 256 }, valueRange = 256f..8192f,
                            onValueChangeFinished = { App.core.setCacheLimitMb(p.cacheMb.value.toUInt()) },
                            colors = SliderDefaults.colors(thumbColor = c.txt, activeTrackColor = c.txt, inactiveTrackColor = c.surf4), modifier = Modifier.weight(1f),
                        )
                    }
                    SRow("Clear cache", null, 2, onClick = { scope.launch(Dispatchers.IO) { DataSources.clear(ctx); App.core.clearCache(); cacheBytes = 0 } })
                }
            }
            item {
                Group("Library") {
                    SRow("Import playlists", "From a public Spotify or Apple Music link", 0, onClick = { s.push(Dest.Import) }) { Chevron() }
                    SRow("Local files", "Music on this phone, mixed with streaming", 1, onClick = { s.push(Dest.Local) }) { Chevron() }
                    SRow("Downloads", null, 2, onClick = { s.push(Dest.Downloads) }) { Chevron() }
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
                                is Updater.Result.Failed -> updateStatus = "Couldn't check: ${r.msg.take(80)}"
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
                        SRow("Version", "iTune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · core ${App.core.version()}", 0, onLong = {
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
