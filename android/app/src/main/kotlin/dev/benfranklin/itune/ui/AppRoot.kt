package dev.benfranklin.itune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dev.benfranklin.itune.App
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.components.AmbientWash
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.ChromeState
import dev.benfranklin.itune.ui.components.LocalHaze
import dev.benfranklin.itune.ui.components.PulseMark
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.glassSpring
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.components.systemReduceMotion
import dev.benfranklin.itune.ui.screens.*
import dev.benfranklin.itune.ui.theme.ItuneTheme
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.benfranklin.itune.ui.components.chromeGlass
import dev.benfranklin.itune.ui.components.ChromeOn
import dev.benfranklin.itune.ui.components.ChromeOnMuted
import dev.benfranklin.itune.ui.components.ChromeTextShadow
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

/** Bottom chrome geometry (dp). */
internal const val NAV_H = 64
internal const val MINI_H = 60
internal const val EDGE = 12

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(s: AppState) {
    val p = App.prefs
    val ctx = LocalContext.current
    val theme by p.theme.state.collectAsState()
    val follow by p.followSystem.state.collectAsState()
    val black by p.pureBlack.state.collectAsState()
    val scale by p.textScale.state.collectAsState()
    val lscale by p.lyricsScale.state.collectAsState()
    val font by p.font.state.collectAsState()
    val customPath by p.customFontPath.state.collectAsState()
    val customNum by p.customFontNumbers.state.collectAsState()
    val current by Playback.current.collectAsState()
    val reduce = remember { systemReduceMotion(ctx) }

    // Adaptive glow: dominant/vibrant colour of the current artwork, crossfaded by the theme.
    LaunchedEffect(current?.thumb) {
        val url = current?.thumb ?: run { s.glow = NoTrackGlow; return@LaunchedEffect }
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(coil.size.Size(96, 96)).build())
        val bmp = (r as? SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } ?: return@LaunchedEffect
        val pal = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = pal.vibrantSwatch ?: pal.lightVibrantSwatch ?: pal.dominantSwatch ?: pal.mutedSwatch
        sw?.let { s.glow = Color(it.rgb) }
    }

    ItuneTheme(theme, follow, black, s.glow, font, customPath, customNum, scale, lscale, reduce) {
        val c = LocalColors.current
        val density = LocalDensity.current
        val haze = remember { HazeState() }
        val sheetHaze = remember { HazeState() }
        val chrome = remember(density) { with(density) { ChromeState(thresholdPx = 72.dp.toPx(), hidePx = 18.dp.toPx(), showPx = 10.dp.toPx()) } }
        val key: Any = s.stack.lastOrNull() ?: s.tab
        LaunchedEffect(key) { chrome.reset() }
        LaunchedEffect(s.nowPlaying) { if (!s.nowPlaying) chrome.show() }
        BackHandler(enabled = s.sheet != null || s.nowPlaying || s.stack.isNotEmpty()) {
            // one level per Back: the top sheet only, then Now Playing, then the page
            when {
                s.sheet != null -> s.closeSheet()
                s.nowPlaying -> s.nowPlaying = false
                else -> s.pop()
            }
        }
        // album-tinted chrome glass: darkened, desaturated art colour (near-mono on Pure Mono), eased on track change
        val chromeTarget = Color(dev.benfranklin.itune.ui.components.ChromeTint.of(
            if (current == null || s.glow == NoTrackGlow) null else s.glow.toArgb(), mono = theme == "mono",
        ))
        val chromeTint by androidx.compose.animation.animateColorAsState(chromeTarget, tween(if (reduce) 0 else 400), label = "chromeTint")
        // where the user is (tab + open pages) is kept for the next launch, whatever ends this one
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { s.tab to s.stack.toList() }.collect { dev.benfranklin.itune.data.UiStore.save(s) }
        }
        androidx.compose.runtime.CompositionLocalProvider(dev.benfranklin.itune.ui.components.LocalChromeTint provides chromeTint) {
        CompositionLocalProviderHaze(haze, chrome) {
            // Crossfade the whole UI when the font changes (no jump).
            AnimatedContent(targetState = "$font|$customPath|$customNum", transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) }, label = "font") { _ ->
                SharedTransitionLayout(Modifier.fillMaxSize().background(c.bg).semantics { testTagsAsResourceId = true }) {
                    Box(Modifier.fillMaxSize()) {
                        // everything under the sheets (pages, chrome, Now Playing) is the sheets' haze source
                        Box(Modifier.fillMaxSize().then(if (dev.benfranklin.itune.ui.components.liveBlur) Modifier.hazeSource(sheetHaze) else Modifier)) {
                            // everything that scrolls is the haze source: the floating glass blurs it.
                            // zIndex -1: Haze only lets an effect draw areas whose compound zIndex is BELOW the source it
                            // sits in. The chrome (mini player, nav, top bar, Now Playing) sits inside the sheets' source
                            // (compound 0), so this inner area must be < 0 or the chrome blurs nothing and shows the
                            // content crisp (0.2.1 regression). Glass inside the content (compound -1) still skips it.
                            Box(Modifier.fillMaxSize().then(if (dev.benfranklin.itune.ui.components.liveBlur) Modifier.hazeSource(haze, zIndex = -1f) else Modifier).nestedScroll(chrome.connection)) {
                                AmbientWash(Modifier.fillMaxSize())
                                Content(s)
                            }
                            StatusEdge(chrome, haze)
                            TopGlassBar(s, chrome)
                            OfflineBanner()
                            PlayerLayer(s, chrome, this@SharedTransitionLayout)
                        }
                        androidx.compose.runtime.CompositionLocalProvider(dev.benfranklin.itune.ui.components.LocalSheetHaze provides sheetHaze) { SheetHost(s) }
                        Toast(s, chrome)
                        CrashReportDialog(s)
                        FpsLayer()
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun CompositionLocalProviderHaze(h: HazeState, ch: ChromeState, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides h, dev.benfranklin.itune.ui.components.LocalChrome provides ch, content = content)

@Composable
private fun Content(s: AppState) {
    val key: Any = s.stack.lastOrNull() ?: s.tab
    val reduce = LocalReduceMotion.current
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            if (reduce) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else if (targetState is Tab && initialState is Tab) {
                (fadeIn(tween(220, 60)) + scaleIn(spring(stiffness = 600f), initialScale = 0.985f)) togetherWith fadeOut(tween(140))
            } else if (s.forward) {
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { it / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(spring(stiffness = 420f)) { -it / 8 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f)) { -it / 8 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(spring(stiffness = 420f)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "content",
    ) { k ->
        // each tab / page keeps its rememberSaveable state (LazyListState of the column and every row)
        holder.SaveableStateProvider(stateKey(k)) {
        Box(Modifier.fillMaxSize()) {
            when (k) {
                Tab.Home -> HomeScreen(s)
                Tab.Search -> SearchScreen(s)
                Tab.Library -> LibraryScreen(s)
                Tab.Stats -> StatsScreen(s)
                is Dest.Album -> AlbumScreen(s, k.id, k.peek)
                is Dest.Artist -> ArtistScreen(s, k.id)
                is Dest.Playlist -> PlaylistScreen(s, k.id, k.peek)
                is Dest.Mix -> MixScreen(s, k.title, k.tracks)
                is Dest.Isa -> IsaScreen(s, k.prompt)
                Dest.Settings -> SettingsScreen(s)
                Dest.Appearance -> AppearanceScreen(s)
                Dest.Import -> ImportScreen(s)
                Dest.Downloads -> DownloadsScreen(s)
                Dest.Local -> LocalFilesScreen(s)
                Dest.Wrapped -> WrappedScreen(s)
                Dest.Diagnostics -> DiagnosticsScreen(s)
            }
        }
        }
    }
}

private fun stateKey(k: Any): String = when (k) {
    is Tab -> "tab:" + k.name
    is Dest.Album -> "album:" + k.id
    is Dest.Artist -> "artist:" + k.id
    is Dest.Playlist -> "playlist:" + k.id
    is Dest.Mix -> "mix:" + k.title
    else -> k::class.java.simpleName
}

/** A soft progressive blur under the status bar once content scrolls beneath it (never a hard clip). */
@Composable
private fun StatusEdge(chrome: ChromeState, haze: HazeState) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val a = animateFloatAsState(if (chrome.scrolled) 1f else 0f, glassSpring(reduce), label = "edge")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (!dev.benfranklin.itune.ui.components.liveBlur) return
    Box(
        Modifier.fillMaxWidth().height(top + 22.dp)
            .graphicsLayer { alpha = a.value }
            .hazeEffect(haze, dev.benfranklin.itune.ui.components.glassStyle(c, 0.30f, 14.dp)) {
                progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            },
    )
}

/**
 * Glass top bar for the four tabs: appears once the screen header has scrolled away, slides out while
 * scrolling down, springs back on a small scroll up.
 */
@Composable
private fun TopGlassBar(s: AppState, chrome: ChromeState) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val onTab = s.stack.isEmpty() && !s.nowPlaying && s.tab != Tab.Search
    val show = onTab && chrome.scrolled && !chrome.hidden
    val v = animateFloatAsState(if (show) 1f else 0f, glassSpring(reduce), label = "topbar")
    if (!onTab) return
    val account by dev.benfranklin.itune.data.Account.info.collectAsState()
    val acctName by App.prefs.userName.state.collectAsState()
    val callName by App.prefs.callName.state.collectAsState()
    val name = callName.trim().ifEmpty { acctName }
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationY = -size.height * (1f - v.value) * 1.1f; alpha = (v.value * 1.4f).coerceIn(0f, 1f) }
            .chromeGlass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .testTag("top_bar"),
    ) {
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseMark(Modifier.size(20.dp))
            Text(s.tab.label, style = T.ui(16.sp, FontWeight.Bold, ChromeOn).copy(shadow = ChromeTextShadow), modifier = Modifier.padding(start = 10.dp).weight(1f))
            if (s.tab != Tab.Search) Icon(Icons.Rounded.Search, "Search", tint = ChromeOn, modifier = Modifier.size(40.dp).padding(10.dp).press { s.select(Tab.Search) })
            Icon(Icons.Rounded.Settings, "Settings", tint = ChromeOn, modifier = Modifier.size(40.dp).padding(10.dp).press { s.push(Dest.Settings) })
            Box(Modifier.padding(start = 4.dp).size(30.dp).glass(CircleShape, dark = 0.5f, sheen = 0.1f).press { s.sheet = Sheet.Account }, contentAlignment = Alignment.Center) {
                val av = account?.avatar
                if (!av.isNullOrEmpty()) Artwork(av, 30.dp, circle = true)
                else Text(name.take(1).uppercase(), style = T.ui(12.sp, FontWeight.Bold))
            }
        }
    }
}

/** Floating glass mini player + glass nav pill, or the full Now Playing; the artwork morphs between them. */
@Composable
private fun PlayerLayer(s: AppState, chrome: ChromeState, shared: SharedTransitionScope) {
    val current by Playback.current.collectAsState()
    val reduce = LocalReduceMotion.current
    val hide = animateFloatAsState(if (chrome.hidden && !s.nowPlaying) 1f else 0f, glassSpring(reduce), label = "chrome")
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AnimatedContent(
        targetState = s.nowPlaying && current != null,
        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
        modifier = Modifier.fillMaxSize(),
        label = "player",
    ) { expanded ->
        if (expanded) {
            NowPlayingScreen(s, shared, this)
        } else {
            Box(Modifier.fillMaxSize().padding(bottom = nav + 8.dp), contentAlignment = Alignment.BottomCenter) {
                val density = LocalDensity.current
                // mini player: sits above the nav, drops into the nav's place while the nav is hidden
                AnimatedVisibility(
                    visible = current != null,
                    enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it * 2 } + fadeIn(),
                    exit = slideOutVertically { it * 2 } + fadeOut(),
                    modifier = Modifier.padding(bottom = (NAV_H + 10).dp).graphicsLayer { translationY = with(density) { (NAV_H + 10).dp.toPx() } * hide.value },
                ) {
                    MiniPlayer(s, shared, this@AnimatedContent)
                }
                BottomNav(s, Modifier.graphicsLayer { translationY = with(density) { (NAV_H + 24).dp.toPx() + nav.toPx() } * hide.value; alpha = 1f - hide.value * 0.6f })
            }
        }
    }
}

@Composable
private fun MiniPlayer(s: AppState, shared: SharedTransitionScope, scope: AnimatedVisibilityScope) {
    val c = LocalColors.current
    val t by Playback.current.collectAsState()
    val playing by Playback.playing.collectAsState()
    val pos = Playback.position.collectAsState()
    val dur = Playback.duration.collectAsState()
    val tr = t ?: return
    val likedIds by dev.benfranklin.itune.data.Likes.ids.collectAsState()
    val liked = tr.id in likedIds
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val hap = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val dx = animateFloatAsState(dragX, if (dragX == 0f) glassSpring(reduce) else spring(stiffness = 10_000f), label = "mdx")
    with(shared) {
        Box(
            Modifier
                .padding(horizontal = EDGE.dp)
                .fillMaxWidth()
                .height(MINI_H.dp)
                .graphicsLayer { translationX = dx.value * 0.5f; translationY = dragY.coerceAtMost(0f) * 0.4f; alpha = 1f - (kotlin.math.abs(dx.value) / 900f).coerceIn(0f, 0.4f) }
                .sharedBounds(rememberSharedContentState("player-bg"), scope, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .chromeGlass(RoundedCornerShape(30.dp))
                .pointerInput(tr.id) {
                    // swipe left/right = next/previous, swipe up = open Now Playing
                    detectDragGestures(
                        onDragEnd = {
                            when {
                                dragY < -120f -> s.nowPlaying = true
                                dragX < -160f -> { hap.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); Playback.next() }
                                dragX > 160f -> { hap.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); Playback.prev() }
                            }
                            dragX = 0f; dragY = 0f
                        },
                        onDragCancel = { dragX = 0f; dragY = 0f },
                    ) { ch, d -> ch.consume(); dragX += d.x; dragY += d.y }
                }
                .press(scaleTo = 0.98f) { s.nowPlaying = true }
                .testTag("mini_player"),
        ) {
            Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.sharedElement(rememberSharedContentState("art"), scope, boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 300f) })) {
                    Artwork(tr.thumb, 42.dp, circle = true)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        tr.title, style = T.ui(14.sp, FontWeight.SemiBold, ChromeOn).copy(shadow = ChromeTextShadow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState("title"), scope).testTag("mini_title"),
                    )
                    Text(tr.artists, style = T.ui(12.sp, FontWeight.Medium, ChromeOnMuted).copy(shadow = ChromeTextShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val hs by animateFloatAsState(if (liked) 1f else 0.9f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "mheart")
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", tint = ChromeOn,
                    modifier = Modifier.size(40.dp).graphicsLayer { scaleX = hs * 1.08f; scaleY = hs * 1.08f }.press { dev.benfranklin.itune.data.Likes.toggle(tr) }.padding(10.dp),
                )
                AnimatedContent(playing, transitionSpec = { scaleIn(spring(dampingRatio = 0.6f)) togetherWith scaleOut(tween(90)) }, label = "mpp") { pl ->
                    Icon(
                        if (pl) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (pl) "Pause" else "Play", tint = ChromeOn,
                        modifier = Modifier.size(42.dp).press { Playback.toggle() }.padding(8.dp),
                    )
                }
                Icon(Icons.Rounded.SkipNext, "Next", tint = ChromeOn, modifier = Modifier.size(40.dp).press { Playback.next() }.padding(9.dp))
            }
            // progress hairline hugging the bottom of the capsule (drawn from state, no recomposition per tick)
            Box(Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 30.dp, bottom = 2.dp).fillMaxWidth().height(1.6.dp).drawBehind {
                val f = if (dur.value > 0) (pos.value.toFloat() / dur.value).coerceIn(0f, 1f) else 0f
                drawRect(Color.White.copy(alpha = 0.22f))
                drawRect(ChromeOn, size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
            })
        }
    }
}

@Composable
private fun BottomNav(s: AppState, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val idx = if (s.stack.isEmpty()) s.tab.ordinal else -1
    val lens = animateFloatAsState(if (idx < 0) s.tab.ordinal.toFloat() else idx.toFloat(), glassSpring(reduce), label = "lens")
    val lensA = animateFloatAsState(if (idx < 0) 0.45f else 1f, glassSpring(reduce), label = "lensA")
    BoxWithConstraints(
        modifier.padding(horizontal = EDGE.dp).fillMaxWidth().height(NAV_H.dp)
            .chromeGlass(RoundedCornerShape(32.dp)),
    ) {
        val cell = (maxWidth - 12.dp) / Tab.entries.size
        // the selected tab is a brighter glass lens that slides between tabs
        Box(
            Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp).width(cell - 4.dp).fillMaxHeight()
                .graphicsLayer { translationX = lens.value * cell.toPx(); alpha = lensA.value }
                .glass(RoundedCornerShape(26.dp), dark = 0.0f, sheen = 0.16f, rim = 0.8f)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val on = idx == t.ordinal
                val icon = when (t) {
                    Tab.Home -> Icons.Rounded.Home
                    Tab.Search -> Icons.Rounded.Search
                    Tab.Library -> Icons.Rounded.LibraryMusic
                    Tab.Stats -> Icons.Rounded.BarChart
                }
                Column(Modifier.weight(1f).fillMaxHeight().press(scaleTo = 0.92f) { s.select(t) }.testTag("tab_${t.label}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, t.label, tint = if (on) ChromeOn else ChromeOnMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = T.ui(11.5.sp, if (on) FontWeight.Bold else FontWeight.SemiBold, if (on) ChromeOn else ChromeOnMuted).copy(shadow = ChromeTextShadow))
                }
            }
        }
    }
}

@Composable
private fun Toast(s: AppState, chrome: ChromeState) {
    val c = LocalColors.current
    val reduce = LocalReduceMotion.current
    val msg = s.toast
    val snack = s.snack
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(2400)
            s.toast = null
        }
    }
    LaunchedEffect(snack?.id) {
        if (snack != null) {
            delay(if (snack.action != null) 6500 else 4000)
            if (s.snack?.id == snack.id) s.snack = null
        }
    }
    val lift = animateFloatAsState(if (chrome.hidden || s.nowPlaying) 0f else 1f, glassSpring(reduce), label = "toastlift")
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().padding(bottom = 100.dp).graphicsLayer { translationY = -with(density) { 72.dp.toPx() } * lift.value },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = snack != null, enter = slideInVertically(glassSpring(reduce)) { it } + fadeIn(), exit = fadeOut() + slideOutVertically { it / 2 }) {
            val sn = snack ?: s.snack
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), dark = 0.38f, backdrop = true, sheen = 0.1f, rim = 0.7f)
                    .padding(start = 18.dp, end = 6.dp).height(52.dp).testTag("snack"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sn?.msg ?: "", style = T.ui(13.5.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sn?.action != null) Text(
                    sn.action, style = T.ui(13.5.sp, FontWeight.Bold, c.bg),
                    modifier = Modifier.padding(end = 2.dp).press { sn.onAction?.invoke(); s.snack = null }
                        .background(c.acc, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("snack_action"),
                )
            }
        }
        AnimatedVisibility(visible = msg != null && snack == null, enter = slideInVertically(glassSpring(reduce)) { it } + fadeIn(), exit = fadeOut()) {
            Text(
                msg ?: "", style = T.ui(13.sp, FontWeight.SemiBold),
                modifier = Modifier.glass(RoundedCornerShape(22.dp), dark = 0.38f, backdrop = true, sheen = 0.1f, rim = 0.7f).padding(horizontal = 18.dp, vertical = 11.dp).testTag("toast"),
            )
        }
    }
}

/** "You're offline" strip; downloads keep playing (Library › Downloads). */
@Composable
private fun OfflineBanner() {
    val c = LocalColors.current
    val online by dev.benfranklin.itune.data.Net.online.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(!online, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            Row(
                Modifier.statusBarsPadding().padding(top = 6.dp).glass(RoundedCornerShape(16.dp), dark = 0.4f, backdrop = true).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(c.t3, CircleShape))
                Text("Offline · downloads still play", style = T.ui(12.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Suppress("unused")
private fun unusedOffset() = Offset.Zero


/** Last session's crash report as a glass dialog (Details opens Diagnostics, Copy puts it on the clipboard). */
@Composable
private fun CrashReportDialog(s: AppState) {
    val txt = s.crashReport ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    dev.benfranklin.itune.ui.components.GlassDialog(
        title = "iTune closed unexpectedly last time",
        onDismiss = { s.crashReport = null },
        tag = "crash_dialog",
        actions = listOf(
            "Close" to { s.crashReport = null },
            "Details" to { s.crashReport = null; s.push(Dest.Diagnostics) },
            "Copy" to {
                runCatching {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt))
                }
                s.crashReport = null
                s.toast("Copied")
            },
        ),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item {
                Text(txt, style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = LocalColors.current.t2))
            }
        }
    }
}
