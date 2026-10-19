package dev.benfranklin.itune.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.ui.platform.testTag
import dev.benfranklin.itune.core.Lyrics
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChipButton
import dev.benfranklin.itune.ui.components.Glow
import dev.benfranklin.itune.ui.components.Toggle
import dev.benfranklin.itune.ui.components.fmtTime
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalLyricsScale
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.launch
import dev.benfranklin.itune.ui.components.GlassIconButton
import dev.benfranklin.itune.ui.components.glass

@Composable
fun NowPlayingScreen(s: AppState, shared: SharedTransitionScope, scope: AnimatedVisibilityScope) {
    val c = LocalColors.current
    val t by Playback.current.collectAsState()
    val playing by Playback.playing.collectAsState()
    val q by Playback.queue.collectAsState()
    val speed by Playback.speed.collectAsState()
    val tr = t ?: return
    val likedIds by dev.benfranklin.itune.data.Likes.ids.collectAsState()
    val liked = tr.id in likedIds
    var downloaded by remember(tr.id) { mutableStateOf(false) }
    LaunchedEffect(tr.id) { downloaded = dev.benfranklin.itune.data.io { App.core.isDownloaded(tr.id) } ?: false }
    var showLyrics by remember { mutableStateOf(false) }
    val sleepAt by Playback.sleepAt.collectAsState()
    var heartBurst by remember { mutableStateOf(0) }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduce = LocalReduceMotion.current
    val h = LocalHapticFeedback.current
    val cs = rememberCoroutineScope()

    with(shared) {
        Box(
            Modifier.fillMaxSize()
                .sharedBounds(rememberSharedContentState("player-bg"), scope, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .graphicsLayer { translationY = drag.value.coerceAtLeast(0f); val k = 1f - (drag.value / 3000f).coerceIn(0f, 0.08f); scaleX = k; scaleY = k }
                .background(c.bg)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag.value > 260f) s.nowPlaying = false
                            else cs.launch { drag.animateTo(0f, dev.benfranklin.itune.ui.components.glassSpring(reduce)) }
                        },
                        onDragCancel = { cs.launch { drag.animateTo(0f, dev.benfranklin.itune.ui.components.glassSpring(reduce)) } },
                    ) { _, d -> cs.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } }
                }
                .testTag("now_playing"),
        ) {
            // full-screen album art, blurred into bokeh: the only colour on this screen
            AnimatedContent(tr.thumb, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "backdrop") { url ->
                dev.benfranklin.itune.ui.components.BlurredArt(url, Modifier.fillMaxSize())
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val artSize = minOf(maxWidth - 48.dp, maxHeight * 0.40f, 312.dp)
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                    // top row: collapse / source / more as small glass buttons
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Close", size = 36.dp, iconSize = 22.dp, tag = "np_close") { s.nowPlaying = false }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PLAYING FROM", style = T.num(9.sp, FontWeight.Medium, c.t2).copy(letterSpacing = 1.sp))
                            Text(q?.sourceTitle ?: "", style = T.ui(13.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        GlassIconButton(Icons.Rounded.MoreHoriz, "More", size = 36.dp, iconSize = 20.dp, tag = "np_more") { s.sheet = Sheet.TrackMenu(tr, nowPlaying = true) }
                    }
                    // artwork (shared element from the mini player); swipe for next/previous; lyrics replace it
                    var swipe by remember { mutableFloatStateOf(0f) }
                    val swipeA by animateFloatAsState(swipe, spring(dampingRatio = 0.8f, stiffness = 400f), label = "sw")
                    Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .sharedElement(rememberSharedContentState("art"), scope, boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 300f) })
                                .graphicsLayer { translationX = swipeA; rotationZ = swipeA / 90f; val k = if (playing || reduce) 1f else 0.92f; scaleX = k; scaleY = k }
                                .shadow(28.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black, spotColor = Color.Black)
                                .pointerInput(tr.id) {
                                    detectTapGestures(onDoubleTap = {
                                        if (!dev.benfranklin.itune.data.Likes.isLiked(tr.id)) dev.benfranklin.itune.data.Likes.set(tr, true)
                                        heartBurst++
                                        h.performHapticFeedback(HapticFeedbackType.LongPress)
                                    })
                                }
                                .pointerInput(tr.id) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            when {
                                                swipe < -180f -> { h.performHapticFeedback(HapticFeedbackType.TextHandleMove); Playback.next() }
                                                swipe > 180f -> { h.performHapticFeedback(HapticFeedbackType.TextHandleMove); Playback.prev() }
                                            }
                                            swipe = 0f
                                        },
                                        onDragCancel = { swipe = 0f },
                                    ) { _, d -> swipe += d }
                                },
                        ) {
                            AnimatedContent(if (showLyrics) "lyrics" else tr.thumb, transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut(tween(200)) + scaleOut(targetScale = 1.02f)) }, label = "art") { url ->
                                if (url == "lyrics") Box(Modifier.size(artSize).clip(RoundedCornerShape(20.dp)).glass(RoundedCornerShape(20.dp), dark = 0.38f)) { LyricsCard(tr, Modifier.fillMaxSize()) }
                                else Artwork(url, artSize, 20.dp)
                            }
                            HeartBurst(heartBurst, Modifier.align(Alignment.Center))
                        }
                    }
                    // title / artist · album
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 30.dp)) {
                        AnimatedContent(tr.title, transitionSpec = { (fadeIn(tween(260)) + androidx.compose.animation.slideInVertically { it / 3 }) togetherWith fadeOut(tween(160)) }, label = "t") { title ->
                            Text(title, style = T.ui(22.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sharedBounds(rememberSharedContentState("title"), scope))
                        }
                        Text(
                            listOfNotNull(tr.artists.split(",")[0], tr.album).joinToString(" · "), style = T.ui(13.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp).press(haptic = false) { tr.artistId?.let { s.push(Dest.Artist(it)) } ?: tr.albumId?.let { s.push(Dest.Album(it)) } },
                        )
                    }
                    // glass controls card: progress, times, transport
                    Column(
                        Modifier.padding(horizontal = 16.dp).padding(top = 20.dp).fillMaxWidth()
                            .glass(RoundedCornerShape(28.dp), dark = 0.34f, sheen = 0.08f, rim = 0.6f)
                            .padding(horizontal = 22.dp, vertical = 16.dp)
                            .testTag("np_controls"),
                    ) {
                        Scrubber(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val shuffle = q?.shuffle == true
                            Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) c.txt else c.t2, modifier = Modifier.size(44.dp).press { Playback.setShuffle(!shuffle) }.padding(11.dp).testTag("np_shuffle"))
                            Icon(Icons.Rounded.SkipPrevious, "Previous", tint = c.txt, modifier = Modifier.size(52.dp).press { Playback.prev() }.padding(10.dp))
                            val ps by animateFloatAsState(if (playing) 1f else 0.92f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "pp")
                            Box(Modifier.size(62.dp).graphicsLayer { scaleX = ps; scaleY = ps }.clip(CircleShape).background(c.acc).press { Playback.toggle() }.testTag("np_play"), contentAlignment = Alignment.Center) {
                                AnimatedContent(playing, transitionSpec = { scaleIn(spring(dampingRatio = 0.6f)) togetherWith scaleOut() }, label = "pi") { p ->
                                    Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play", tint = c.bg, modifier = Modifier.size(32.dp))
                                }
                            }
                            Icon(Icons.Rounded.SkipNext, "Next", tint = c.txt, modifier = Modifier.size(52.dp).press { Playback.next() }.padding(10.dp))
                            val rep = q?.repeat?.toInt() ?: 0
                            Icon(if (rep == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (rep > 0) c.txt else c.t2, modifier = Modifier.size(44.dp).press { Playback.cycleRepeat() }.padding(11.dp))
                        }
                    }
                    // glass icon row: lyrics, queue, download, like
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(Icons.Rounded.FormatQuote, "Lyrics", size = 48.dp, iconSize = 22.dp, selected = showLyrics, tag = "np_lyrics") { showLyrics = !showLyrics }
                        GlassIconButton(Icons.Rounded.QueueMusic, "Queue", size = 48.dp, iconSize = 22.dp, tag = "np_queue") { s.sheet = Sheet.Queue }
                        GlassIconButton(
                            if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (downloaded) "Remove download" else "Download",
                            size = 48.dp, iconSize = 22.dp, selected = downloaded, tag = "np_download",
                        ) {
                            if (downloaded) {
                                downloaded = false
                                dev.benfranklin.itune.data.Bg.run { App.core.removeDownload(tr.id) }
                                s.snack = dev.benfranklin.itune.ui.Snack("Removed download", "Undo") { downloaded = true; App.scope.launch { dev.benfranklin.itune.data.io { App.core.download(tr) } } }
                            } else cs.launch {
                                s.toast("Downloading…")
                                dev.benfranklin.itune.data.ioResult { App.core.download(tr) }.onSuccess { downloaded = true; s.toast("Downloaded") }.onFailure { s.toast("Couldn't download. Check your connection.") }
                            }
                        }
                        val hs by animateFloatAsState(if (liked) 1f else 0.9f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "heart")
                        GlassIconButton(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like",
                            modifier = Modifier.graphicsLayer { scaleX = hs * 1.08f; scaleY = hs * 1.08f }, size = 48.dp, iconSize = 21.dp, tag = "np_like",
                        ) { dev.benfranklin.itune.data.Likes.toggle(tr) }
                    }
                    // active speed / sleep timer show as small glass pills (set them from ⋯)
                    if (speed != 1f || sleepAt != 0L) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (speed != 1f) Text(
                                "%.2g×".format(speed), style = T.num(12.sp, FontWeight.Medium),
                                modifier = Modifier.padding(horizontal = 4.dp).press { s.sheet = Sheet.Speed }.glass(RoundedCornerShape(14.dp), dark = 0.3f).padding(horizontal = 12.dp, vertical = 6.dp).testTag("np_speed_pill"),
                            )
                            if (sleepAt != 0L) Row(
                                Modifier.padding(horizontal = 4.dp).press { s.sheet = Sheet.SleepTimer }.glass(RoundedCornerShape(14.dp), dark = 0.3f).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Bedtime, null, tint = c.txt, modifier = Modifier.size(13.dp))
                                Text(if (sleepAt == Playback.END_OF_TRACK) " End of song" else " Sleep timer on", style = T.ui(12.sp, FontWeight.SemiBold))
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HeartBurst(key: Int, modifier: Modifier) {
    val c = LocalColors.current
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) {
        if (key == 0) return@LaunchedEffect
        a.snapTo(0f)
        a.animateTo(1f, tween(650))
    }
    if (a.value in 0.001f..0.999f) {
        Icon(
            Icons.Rounded.Favorite, null, tint = c.txt,
            modifier = modifier.size(96.dp).graphicsLayer {
                val k = if (a.value < 0.3f) a.value / 0.3f * 1.2f else 1.2f - (a.value - 0.3f) * 0.3f
                scaleX = k; scaleY = k; alpha = if (a.value < 0.6f) 1f else 1f - (a.value - 0.6f) / 0.4f
            },
        )
    }
}

/** Progress bar with drag-to-seek; the played part carries a halo in the album colour. */
@Composable
fun Scrubber(modifier: Modifier) {
    val c = LocalColors.current
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val f = dragging ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val thick by animateFloatAsState(if (dragging != null) 8f else 4f, spring(stiffness = 600f), label = "th")
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { Playback.seek((it * dur).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { ch, _ -> dragging = (ch.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(dur) { detectTapGestures { o -> Playback.seek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } }
            .drawBehind {
                val y = size.height / 2
                val th = thick.dp.toPx() / 2
                drawRoundRect(c.surf4, Offset(0f, y - th / 2), Size(size.width, th), CornerRadius(th))
                drawRoundRect(Brush.horizontalGradient(listOf(c.glow.copy(alpha = 0f), c.glow.copy(alpha = 0.7f)), endX = size.width * f), Offset(0f, y - th * 1.6f), Size(size.width * f, th * 3.2f), CornerRadius(th * 2))
                drawRoundRect(c.acc, Offset(0f, y - th / 2), Size(size.width * f, th), CornerRadius(th))
                drawCircle(c.txt, radius = (if (dragging != null) 8.dp else 6.5.dp).toPx(), center = Offset(size.width * f, y))
            }.testTag("np_scrubber")) {}
        Row {
            Text(fmtTime(dragging?.let { (it * dur).toLong() } ?: pos), style = T.num(11.sp, color = c.t3), modifier = Modifier.weight(1f))
            Text("-" + fmtTime((dur - (dragging?.let { (it * dur).toLong() } ?: pos)).coerceAtLeast(0)), style = T.num(11.sp, color = c.t3))
        }
    }
}

/** Synced lyrics, karaoke style: the active line fills word by word; others are scaled down and blurred. */
@Composable
fun LyricsCard(tr: Track, modifier: Modifier) {
    val c = LocalColors.current
    val scale = LocalLyricsScale.current
    val reduce = LocalReduceMotion.current
    var lyrics by remember(tr.id) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(tr.id) { mutableStateOf(false) }
    LaunchedEffect(tr.id) {
        lyrics = dev.benfranklin.itune.data.io { App.core.lyrics(tr) }
        loaded = true
    }
    // frame-smooth position between the 250 ms player ticks
    val pos by Playback.position.collectAsState()
    val playing by Playback.playing.collectAsState()
    var smooth by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pos, playing) {
        val base = pos
        val t0 = System.nanoTime()
        smooth = base
        while (playing) {
            withFrameMillis { }
            smooth = base + (System.nanoTime() - t0) / 1_000_000
        }
    }
    val lines = lyrics?.lines.orEmpty()
    val synced = lyrics?.synced == true
    val active = if (synced) lines.indexOfLast { it.startMs.toLong() <= smooth + 150 } else -1
    val list = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) {
            if (reduce) list.scrollToItem((active - 1).coerceAtLeast(0)) else list.animateScrollToItem((active - 1).coerceAtLeast(0))
        }
    }
    Column(modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(20.dp)).background(c.surf.copy(alpha = 0.85f)).border(0.5.dp, c.line2, RoundedCornerShape(20.dp)).padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Shimmers() }
            lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No English lyrics for this one.", style = T.ui(13.sp, FontWeight.Medium, c.t3))
            }
            else -> LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines, key = { i, l -> "$i${l.startMs}" }) { i, l ->
                    val d = if (active < 0) 0 else kotlin.math.abs(i - active)
                    val isActive = i == active
                    val sc by animateFloatAsState(if (isActive || !synced) 1f else 0.94f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "ls")
                    val blur by animateFloatAsState(if (isActive || !synced || reduce) 0f else (d.coerceAtMost(3) * 1.2f), tween(300), label = "lb")
                    val txt = buildAnnotatedString {
                        if (isActive && l.words.isNotEmpty()) {
                            l.words.forEach { w ->
                                val p = ((smooth - w.startMs.toLong()).toFloat() / (w.endMs.toLong() - w.startMs.toLong()).coerceAtLeast(1)).coerceIn(0f, 1f)
                                withStyle(SpanStyle(color = lerp(c.t3, c.txt, p))) { append(w.text + " ") }
                            }
                        } else {
                            withStyle(SpanStyle(color = when {
                                !synced -> c.t2
                                i < active -> c.t3.copy(alpha = 0.8f)
                                d == 1 -> c.t3
                                else -> c.t4
                            })) { append(l.text) }
                        }
                    }
                    Text(
                        txt,
                        style = T.ui(((if (isActive) 20f else 17f) * scale).sp, FontWeight.Bold),
                        modifier = Modifier.padding(vertical = 6.dp)
                            .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .then(if (Build.VERSION.SDK_INT >= 31 && blur > 0.05f) Modifier.blur(blur.dp) else Modifier)
                            .press(haptic = false, scaleTo = 0.98f) { if (synced) Playback.seek(l.startMs.toLong()) },
                    )
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") Color.Unspecified
}
