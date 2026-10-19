package dev.benfranklin.itune.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.benfranklin.itune.core.HomeFeed
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.PulseMark
import dev.benfranklin.itune.ui.components.RiseText
import dev.benfranklin.itune.ui.components.SectionTitle
import dev.benfranklin.itune.ui.components.TrackRow
import dev.benfranklin.itune.ui.components.enter
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T
import java.util.TimeZone

object HomeCache {
    var feed: HomeFeed? = null
    var at = 0L
}

fun tzMin() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

@Composable
fun HomeScreen(s: AppState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf(HomeCache.feed) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (feed == null || System.currentTimeMillis() - HomeCache.at > 20 * 60_000) {
            runCatching { App.core.home(tzMin()) }
                .onSuccess { feed = it; HomeCache.feed = it; HomeCache.at = System.currentTimeMillis(); error = null }
                .onFailure { error = it.message }
        }
    }
    val name by App.prefs.userName.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PulseMark(Modifier.size(18.dp))
                Text("iTune", style = T.ui(15.sp, FontWeight.Bold), modifier = Modifier.padding(start = 8.dp).weight(1f))
                Icon(Icons.Rounded.Settings, null, tint = c.t2, modifier = Modifier.size(36.dp).padding(8.dp).press { s.push(Dest.Settings) })
                Box(Modifier.size(30.dp).clip(CircleShape).background(c.surf3).press { s.push(Dest.Settings) }, contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), style = T.ui(13.sp, FontWeight.Bold))
                }
            }
        }
        item {
            val greet = feed?.greeting ?: "Hello"
            RiseText("$greet, $name", T.ui(22.sp, FontWeight.Bold), Modifier.padding(start = 20.dp, top = 22.dp, end = 20.dp))
        }
        item { IsaPill(s, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                val sug = feed?.suggestions ?: listOf("rainy night drive, Malayalam + lo-fi", "focus, no vocals")
                itemsIndexed(sug) { i, label ->
                    ChipButton(label, Icons.Rounded.AutoAwesome, Modifier.enter(i)) { s.push(Dest.Isa(label)) }
                }
            }
        }
        val f = feed
        if (f == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(error?.let { "Can't reach YouTube Music right now." } ?: "", style = T.ui(13.sp, FontWeight.Medium, c.t3))
                }
            }
            if (error == null) item { Shimmers() }
        } else {
            if (f.mixes.isNotEmpty()) {
                item { Spacer(Modifier.height(28.dp)); SectionTitle("Daily Mixes") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                        itemsIndexed(f.mixes) { i, m ->
                            Column(Modifier.width(138.dp).enter(i).press(scaleTo = 0.96f) { s.push(Dest.Mix(m.title, m.tracks)) }) {
                                Box {
                                    Artwork(m.thumb, 138.dp, 12.dp)
                                    Text("%02d".format(i + 1), style = T.num(11.sp, FontWeight.Medium), modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(c.txt)
                                            .press { Playback.play(ctx, m.tracks, 0, m.title) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Rounded.PlayArrow, null, tint = c.bg, modifier = Modifier.size(18.dp)) }
                                }
                                Text(m.title, style = T.ui(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp))
                                Text(m.subtitle, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (f.recent.isNotEmpty()) {
                item { Spacer(Modifier.height(28.dp)); SectionTitle("Recently played"); Spacer(Modifier.height(6.dp)) }
                itemsIndexed(f.recent.take(8)) { i, t ->
                    TrackRow(t, i, onClick = { Playback.play(ctx, f.recent, i, "Recently played") }, onMore = { s.sheet = Sheet.TrackMenu(t) })
                }
            }
            if (f.trending.isNotEmpty()) {
                item { Spacer(Modifier.height(28.dp)); SectionTitle("Trending"); Spacer(Modifier.height(6.dp)) }
                itemsIndexed(f.trending.take(10)) { i, t ->
                    TrackRow(t, i, onClick = { Playback.play(ctx, f.trending, i, "Trending") }, onMore = { s.sheet = Sheet.TrackMenu(t) }, leadingNumber = i + 1)
                }
            }
        }
    }
}

@Composable
fun IsaPill(s: AppState, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun go() {
        if (text.isNotBlank()) {
            s.push(Dest.Isa(text.trim()))
            text = ""
            focus.clearFocus()
        }
    }
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(c.surf).border(0.6.dp, c.line3, RoundedCornerShape(26.dp)).padding(start = 18.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = c.txt, modifier = Modifier.size(17.dp))
        Text("Isa", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.padding(start = 10.dp))
        Box(Modifier.padding(horizontal = 12.dp).width(0.75.dp).height(18.dp).background(c.line3))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Ask Isa…", style = T.ui(14.sp, FontWeight.Medium, c.t3))
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = T.ui(14.sp, FontWeight.Medium),
                cursorBrush = SolidColor(c.txt),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accentUi).press { go() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowUpward, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun Shimmers() {
    val c = LocalColors.current
    val a = androidx.compose.animation.core.rememberInfiniteTransition(label = "sh")
    val alpha by a.animateFloat(0.35f, 0.8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { Box(Modifier.size(138.dp).clip(RoundedCornerShape(12.dp)).background(c.surf3.copy(alpha = alpha))) }
    }
}
