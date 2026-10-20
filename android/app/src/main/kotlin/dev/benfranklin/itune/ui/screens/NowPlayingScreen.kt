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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
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
    val dlIds by dev.benfranklin.itune.data.Library.downloadIds.collectAsState()
    val downloaded = tr.id in dlIds
    val showLyrics by App.prefs.npLyrics.state.collectAsState()
    val sleepAt by Playback.sleepAt.collectAsState()
    var heartBurst by remember { mutableStateOf(0) }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduce = LocalReduceMotion.current
    val h = LocalHapticFeedback.current
    val cs = rememberCoroutineScope()

    // Swipe down to minimise: a drag down anywhere (once the content is at the top) moves the sheet with the
    // finger while it scales and rounds toward the mini player; released past 25% of the height or flung down,
    // it collapses with the same shared-element animation as the chevron, otherwise it springs back.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var heightPx by remember { mutableFloatStateOf(1f) }
    val swipe = remember(reduce, s) { NpSwipe(drag, cs, reduce, h, density.density, isOpen = { s.nowPlaying }) { s.nowPlaying = false } }
    swipe.heightPx = heightPx
    val scroll = rememberScrollState()
    // the title and art morph to the mini player only while the page is at the top; scrolled, they stay plain
    // content (a shared element is drawn in the transition overlay at its last bounds, which pinned the title
    // over the controls while the page scrolled under it)
    val atTop by remember { androidx.compose.runtime.derivedStateOf { scroll.value <= 1 } }
    // every open looks the same: top of the page, no leftover pull or collapse state. AnimatedContent reuses this
    // composition when Now Playing is reopened while its exit is still running (tap the mini player right after a
    // swipe down): before, NpSwipe stayed "collapsing" and swallowed every scroll, swipe and Back ("can't move").
    LaunchedEffect(s.nowPlaying) {
        if (s.nowPlaying) {
            swipe.reset()
            if (scroll.value != 0) scroll.scrollTo(0)
        }
    }
    // predictive back drives the same motion (the system back gesture previews the minimise)
    androidx.activity.compose.PredictiveBackHandler(enabled = s.sheet == null && s.nowPlaying) { progress ->
        try {
            progress.collect { e -> if (!reduce) swipe.set(e.progress * heightPx * 0.12f) }
            swipe.collapse()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            swipe.springBack()
            throw e
        }
    }

    with(shared) {
        Box(
            Modifier.fillMaxSize()
                .androidx_onHeight { heightPx = it }
                .sharedBounds(rememberSharedContentState("player-bg"), scope, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .graphicsLayer {
                    val d = drag.value.coerceAtLeast(0f)
                    translationY = d
                    if (!reduce && d > 0f) {
                        val p = (d / heightPx).coerceIn(0f, 1f)
                        val k = 1f - 0.12f * p
                        scaleX = k; scaleY = k
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        shape = RoundedCornerShape((36f * (p * 4f).coerceAtMost(1f)).dp)
                        clip = true
                    }
                }
                .background(c.bg)
                .androidx_nestedScroll(swipe.connection)
                .testTag("now_playing"),
        ) {
            // full-screen album art, blurred into bokeh: the adaptive glow behind the art, title and glass controls
            // (dropped by mistake in 0.2.1, which left Now Playing flat black)
            AnimatedContent(tr.thumb, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "backdrop") { url ->
                dev.benfranklin.itune.ui.components.BlurredArt(url, Modifier.fillMaxSize())
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val artSize = minOf(maxWidth - 48.dp, maxHeight * 0.40f, 312.dp)
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(scroll).testTag("np_scroll")) {
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
                    val artKey = rememberSharedContentState("art")
                    val titleKey = rememberSharedContentState("title")
                    val swipeA by animateFloatAsState(swipe, spring(dampingRatio = 0.8f, stiffness = 400f), label = "sw")
                    Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .then(if (atTop) Modifier.sharedElement(artKey, scope, boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 300f) }) else Modifier)
                                .graphicsLayer {
                                    translationX = swipeA; rotationZ = swipeA / 90f
                                    val dp = if (reduce) 0f else (drag.value / heightPx).coerceIn(0f, 0.5f)
                                    val k = (if (playing || reduce) 1f else 0.92f) * (1f - 0.36f * dp)
                                    scaleX = k; scaleY = k
                                }
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
                    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // ONE shared element for the title (on the container, not on each animated copy of the text)
                        AnimatedContent(
                            tr.title, transitionSpec = { (fadeIn(tween(260)) + androidx.compose.animation.slideInVertically { it / 3 }) togetherWith fadeOut(tween(160)) }, label = "t",
                            modifier = (if (atTop) Modifier.sharedBounds(titleKey, scope) else Modifier).testTag("np_title"),
                        ) { title ->
                            Text(title, style = T.ui(22.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                        GlassIconButton(Icons.Rounded.FormatQuote, "Lyrics", size = 48.dp, iconSize = 22.dp, selected = showLyrics, tag = "np_lyrics") { App.prefs.npLyrics.value = !showLyrics }
                        GlassIconButton(Icons.Rounded.QueueMusic, "Queue", size = 48.dp, iconSize = 22.dp, tag = "np_queue") { s.sheet = Sheet.Queue }
                        GlassIconButton(
                            if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (downloaded) "Remove download" else "Download",
                            size = 48.dp, iconSize = 22.dp, selected = downloaded, tag = "np_download",
                        ) {
                            if (downloaded) {
                                val ids = listOf(tr.id)
                                s.undoable(
                                    "Removed download",
                                    apply = { dev.benfranklin.itune.data.Library.hideDownloads(ids) },
                                    undo = { dev.benfranklin.itune.data.Library.restoreDownloads() },
                                    commit = { dev.benfranklin.itune.data.Library.commitRemoveDownloads(ids) },
                                )
                            } else cs.launch {
                                s.toast("Downloading…")
                                dev.benfranklin.itune.data.Library.download(tr).onSuccess { s.toast("Downloaded") }.onFailure { s.toast("Couldn't download. Check your connection.") }
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
                    // scroll on: lyrics, then up next (the art can also flip to lyrics with the quote button)
                    if (!showLyrics) LyricsCard(tr, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("np_lyrics_inline"))
                    UpNext(s, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/** The next few songs, inline under the controls; "See queue" opens the full sheet. */
@Composable
private fun UpNext(s: AppState, modifier: Modifier) {
    val c = LocalColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val q by Playback.queue.collectAsState()
    val st = q ?: return
    val next = st.tracks.drop(st.index + 1).take(6)
    if (next.isEmpty()) return
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), dark = 0.40f, sheen = 0.06f, rim = 0.55f).padding(vertical = 10.dp).testTag("np_upnext")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text("See queue", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t2), maxLines = 1, modifier = Modifier.press { s.sheet = Sheet.Queue }.padding(6.dp))
        }
        next.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().press(scaleTo = 0.98f) { Playback.play(ctx, st.tracks, st.index + 1 + i, st.sourceTitle ?: "") }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dev.benfranklin.itune.ui.components.Artwork(t.thumb, 40.dp, 6.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Synced lyrics, karaoke style: the active line fills word by word; the others are slightly smaller and dimmer.
 * Each line is ONE plain text layer (no per-line RenderEffect blur: on phones with live blur that drew the
 * neighbouring lines as smeared, ghosted copies). The frame clock only drives the active line's word fill; the
 * card itself recomposes when the active line changes, not every frame. Auto-scroll pauses while the user drags
 * the lyrics and for a few seconds after.
 */
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
    // frame-smooth position between the 250 ms player ticks (a State, read only where it is needed)
    val pos = Playback.position.collectAsState()
    val playing by Playback.playing.collectAsState()
    val smooth = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val lines = lyrics?.lines.orEmpty()
    val synced = lyrics?.synced == true
    val karaoke = synced && lines.any { it.words.isNotEmpty() }
    LaunchedEffect(pos.value, playing, karaoke) {
        val base = pos.value
        val t0 = System.nanoTime()
        smooth.longValue = base
        // per-frame updates only for word-by-word fill; line-synced lyrics are fine on the player ticks
        while (playing && karaoke) {
            withFrameMillis { }
            smooth.longValue = base + (System.nanoTime() - t0) / 1_000_000
        }
    }
    val active by remember(lines, synced) {
        androidx.compose.runtime.derivedStateOf { if (synced) lines.indexOfLast { it.startMs.toLong() <= smooth.longValue + 150 } else -1 }
    }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var userAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) userAt = Long.MAX_VALUE else if (userAt != 0L) userAt = System.currentTimeMillis() }
    LaunchedEffect(active) {
        if (active < 0) return@LaunchedEffect
        // the user is reading elsewhere: don't yank the list back under their finger
        if (dragged || list.isScrollInProgress || System.currentTimeMillis() - userAt < 4000) return@LaunchedEffect
        val target = (active - 1).coerceAtLeast(0)
        runCatching { if (reduce) list.scrollToItem(target) else list.animateScrollToItem(target) }
    }
    Column(modifier.fillMaxWidth().height(330.dp).glass(RoundedCornerShape(20.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = T.ui(14.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Shimmers() }
            lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No English lyrics for this one.", style = T.ui(13.sp, FontWeight.Medium, c.t3))
            }
            else -> LazyColumn(
                state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
                // a drag inside the lyrics scrolls the lyrics (then the page with what is left over), but a pull past
                // their top never minimises Now Playing (the swipe-down lives on the rest of the screen)
                modifier = Modifier.fillMaxSize().nestedScroll(LyricsScrollGuard).testTag("np_lyrics_list"),
            ) {
                itemsIndexed(lines, key = { i, _ -> i }) { i, l ->
                    val d = if (active < 0) 0 else kotlin.math.abs(i - active)
                    val isActive = i == active
                    val sc by animateFloatAsState(if (isActive || !synced) 1f else 0.94f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "ls")
                    val color = when {
                        !synced -> c.t2
                        isActive -> c.txt
                        i < active -> c.t3.copy(alpha = 0.8f)
                        d == 1 -> c.t3
                        else -> c.t4
                    }
                    val style = T.ui(((if (isActive) 20f else 17f) * scale).sp, FontWeight.Bold)
                    val mod = Modifier.padding(vertical = 6.dp)
                        .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .press(haptic = false, scaleTo = 0.98f) { if (synced) Playback.seek(l.startMs.toLong()) }
                    if (isActive && l.words.isNotEmpty()) KaraokeLine(l, smooth, style, mod)
                    else Text(l.text, style = style.copy(color = color), modifier = mod)
                }
            }
        }
    }
}

/** The active line, filled word by word from the frame clock (the only part that recomposes per frame). */
@Composable
private fun KaraokeLine(l: dev.benfranklin.itune.core.LyricLine, smooth: androidx.compose.runtime.MutableLongState, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    val c = LocalColors.current
    val now = smooth.longValue
    val txt = buildAnnotatedString {
        l.words.forEach { w ->
            val p = ((now - w.startMs.toLong()).toFloat() / (w.endMs.toLong() - w.startMs.toLong()).coerceAtLeast(1)).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = lerp(c.t3, c.txt, p))) { append(w.text + " ") }
        }
    }
    Text(txt, style = style, modifier = modifier)
}

/**
 * Marks drags that happen inside the lyrics list (see [LyricsCard]): the page may still scroll with what the lyrics
 * leave over, but such a drag never pulls Now Playing down to minimise it ([NpSwipe] checks [lyricsDragAt]).
 */
@Volatile private var lyricsDragAt = 0L
private val LyricsScrollGuard = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) lyricsDragAt = android.os.SystemClock.uptimeMillis()
        return Offset.Zero
    }
}
private fun inLyricsDrag(): Boolean = android.os.SystemClock.uptimeMillis() - lyricsDragAt < 150


private fun Modifier.androidx_onHeight(onHeight: (Float) -> Unit): Modifier =
    this.then(Modifier.onSizeChanged { if (it.height > 0) onHeight(it.height.toFloat()) })

private fun Modifier.androidx_nestedScroll(c: androidx.compose.ui.input.nestedscroll.NestedScrollConnection): Modifier =
    this.then(Modifier.nestedScroll(c))

/**
 * Drag-to-minimise for Now Playing, fed by the content's nested scroll: only a downward drag the content can't
 * use (it is at the top) moves the sheet, and while the sheet is pulled down an upward drag first pushes it
 * back before the content scrolls. Release decides: past [THRESHOLD] of the height or a downward fling collapses.
 */
private class NpSwipe(
    val drag: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val scope: kotlinx.coroutines.CoroutineScope,
    val reduce: Boolean,
    val haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val density: Float,
    /** Whether Now Playing is (still / again) the target: a collapse only blocks input while it is closing. */
    val isOpen: () -> Boolean,
    val onCollapse: () -> Unit,
) {
    var heightPx = 1f
    /** Current pull (px); the source of truth, mirrored into [drag] for drawing. */
    private var pos = 0f
    private var armed = false
    private var collapsing = false

    fun set(v: Float) {
        val nv = v.coerceAtLeast(0f)
        pos = nv
        scope.launch { drag.snapTo(nv) }
        val over = nv > heightPx * THRESHOLD
        if (over != armed) {
            armed = over
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /** Fresh state for a (re)open: no pull, not collapsing. */
    fun reset() {
        collapsing = false
        armed = false
        pos = 0f
        scope.launch { drag.snapTo(0f) }
    }

    /** True only while a collapse is actually under way (Now Playing no longer the target). */
    private val blocking: Boolean get() = collapsing && !isOpen()

    fun collapse() {
        if (collapsing && !isOpen()) return
        collapsing = true
        onCollapse()
        pos = 0f
        // the shared bounds shrink to the mini player while the drag offset eases out: one continuous motion
        scope.launch { if (reduce) drag.snapTo(0f) else drag.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 300f)) }
    }

    private fun settle(velocityY: Float) {
        val d = pos
        if (d <= 0f) return
        if (d > heightPx * THRESHOLD || (velocityY > FLING_DP * density && d > 12f * density)) collapse()
        else {
            springBack()
        }
    }

    fun springBack() {
        armed = false
        pos = 0f
        scope.launch { drag.animateTo(0f, dev.benfranklin.itune.ui.components.glassSpring(reduce)) }
    }

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            if (blocking) return available
            val d = pos
            if (d > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                // sheet is pulled down: it takes the whole drag (up pushes it back first, down keeps pulling)
                val nv = (d + available.y).coerceAtLeast(0f)
                set(nv)
                return Offset(0f, nv - d)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
            if (blocking) return available
            if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && pos == 0f && inLyricsDrag()) return Offset.Zero
            if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                set(pos + available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            if (blocking) return available
            if (pos > 0f) {
                settle(available.y)
                return available
            }
            return androidx.compose.ui.unit.Velocity.Zero
        }
    }

    companion object {
        const val THRESHOLD = 0.25f
        const val FLING_DP = 1100f
    }
}
