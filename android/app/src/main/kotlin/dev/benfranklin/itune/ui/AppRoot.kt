package dev.benfranklin.itune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import dev.benfranklin.itune.App
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.components.systemReduceMotion
import dev.benfranklin.itune.ui.screens.*
import dev.benfranklin.itune.ui.theme.ItuneTheme
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.delay

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
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(Size(96, 96)).build())
        val bmp = (r as? SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap } ?: return@LaunchedEffect
        val pal = Palette.from(bmp).maximumColorCount(16).generate()
        val sw = pal.vibrantSwatch ?: pal.lightVibrantSwatch ?: pal.dominantSwatch ?: pal.mutedSwatch
        sw?.let { s.glow = Color(it.rgb) }
    }

    ItuneTheme(theme, follow, black, s.glow, font, customPath, customNum, scale, lscale, reduce) {
        val c = LocalColors.current
        BackHandler(enabled = s.sheet != null || s.nowPlaying || s.stack.isNotEmpty()) {
            when {
                s.sheet != null -> s.sheet = null
                s.nowPlaying -> s.nowPlaying = false
                else -> s.pop()
            }
        }
        // Crossfade the whole UI when the font changes (no jump).
        AnimatedContent(targetState = "$font|$customPath|$customNum", transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) }, label = "font") { _ ->
            SharedTransitionLayout(Modifier.fillMaxSize().background(c.bg)) {
                Box(Modifier.fillMaxSize()) {
                    Content(s)
                    PlayerLayer(s, this@SharedTransitionLayout)
                    SheetHost(s)
                    Toast(s)
                    FpsLayer()
                }
            }
        }
    }
}

@Composable
private fun Content(s: AppState) {
    val key: Any = s.stack.lastOrNull() ?: s.tab
    val reduce = LocalReduceMotion.current
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
        Box(Modifier.fillMaxSize()) {
            when (k) {
                Tab.Home -> HomeScreen(s)
                Tab.Search -> SearchScreen(s)
                Tab.Library -> LibraryScreen(s)
                Tab.Stats -> StatsScreen(s)
                is Dest.Album -> AlbumScreen(s, k.id)
                is Dest.Artist -> ArtistScreen(s, k.id)
                is Dest.Playlist -> PlaylistScreen(s, k.id)
                is Dest.Mix -> MixScreen(s, k.title, k.tracks)
                is Dest.Isa -> IsaScreen(s, k.prompt)
                Dest.Settings -> SettingsScreen(s)
                Dest.Appearance -> AppearanceScreen(s)
                Dest.Import -> ImportScreen(s)
                Dest.Downloads -> DownloadsScreen(s)
                Dest.Local -> LocalFilesScreen(s)
                Dest.Wrapped -> WrappedScreen(s)
            }
        }
    }
}

/** Mini player + bottom nav, or the full Now Playing; the artwork morphs between them (shared element). */
@Composable
private fun PlayerLayer(s: AppState, shared: SharedTransitionScope) {
    val current by Playback.current.collectAsState()
    AnimatedContent(
        targetState = s.nowPlaying && current != null,
        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(200)) },
        modifier = Modifier.fillMaxSize(),
        label = "player",
    ) { expanded ->
        if (expanded) {
            NowPlayingScreen(s, shared, this)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = current != null,
                        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = 380f)) { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        MiniPlayer(s, shared, this@AnimatedContent)
                    }
                    BottomNav(s)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(s: AppState, shared: SharedTransitionScope, scope: AnimatedVisibilityScope) {
    val c = LocalColors.current
    val t by Playback.current.collectAsState()
    val playing by Playback.playing.collectAsState()
    val pos by Playback.position.collectAsState()
    val dur by Playback.duration.collectAsState()
    val tr = t ?: return
    var liked by remember(tr.id) { mutableStateOf(App.core.isLiked(tr.id)) }
    with(shared) {
        Box(
            Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth()
                .height(62.dp)
                .sharedBounds(rememberSharedContentState("player-bg"), scope, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surf2)
                .drawBehind {
                    // subtle album tint
                    drawRect(Brush.horizontalGradient(0f to c.glow.copy(alpha = 0.22f), 0.6f to c.glow.copy(alpha = 0.04f), 1f to Color.Transparent))
                }
                .border(0.5.dp, c.line3, RoundedCornerShape(14.dp))
                .press(scaleTo = 0.98f) { s.nowPlaying = true },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.sharedElement(rememberSharedContentState("art"), scope, boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 300f) })) {
                    Artwork(tr.thumb, 44.dp, 8.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        tr.title, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState("title"), scope),
                    )
                    Text(tr.artists, style = T.ui(12.sp, FontWeight.Medium, c.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = c.txt,
                    modifier = Modifier.size(40.dp).padding(9.dp).press { liked = !liked; App.core.setLiked(tr, liked) },
                )
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = c.txt,
                    modifier = Modifier.size(42.dp).padding(8.dp).press { Playback.toggle() },
                )
                Icon(Icons.Rounded.SkipNext, null, tint = c.txt, modifier = Modifier.size(40.dp).padding(8.dp).press { Playback.next() })
            }
            // progress with a halo in the album colour
            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp).fillMaxWidth().height(2.dp).drawBehind {
                drawRect(c.surf4)
                drawRect(c.glow.copy(alpha = 0.6f), size = androidx.compose.ui.geometry.Size(size.width * f, size.height + 2f), topLeft = Offset(0f, -1f))
                drawRect(c.txt, size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
            })
        }
    }
}

@Composable
private fun BottomNav(s: AppState) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().background(c.side)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line2))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val on = s.tab == t && s.stack.isEmpty()
                val icon = when (t) {
                    Tab.Home -> Icons.Rounded.Home
                    Tab.Search -> Icons.Rounded.Search
                    Tab.Library -> Icons.Rounded.LibraryMusic
                    Tab.Stats -> Icons.Rounded.BarChart
                }
                Column(Modifier.weight(1f).press { s.select(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val pillA by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "pill")
                    Box(Modifier.size(56.dp, 30.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 0.6f + 0.4f * pillA; alpha = pillA }.clip(RoundedCornerShape(15.dp)).background(c.surf4))
                        Icon(icon, null, tint = if (on) c.accentUi else c.t2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, style = T.ui(11.5.sp, if (on) FontWeight.Bold else FontWeight.SemiBold, if (on) c.accentUi else c.t3))
                }
            }
        }
    }
}

@Composable
private fun Toast(s: AppState) {
    val c = LocalColors.current
    val msg = s.toast
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(2400)
            s.toast = null
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 170.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = msg != null, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
            Text(
                msg ?: "", style = T.ui(13.sp, FontWeight.SemiBold, c.bg),
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.txt).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Suppress("unused")
private fun Color.argb() = toArgb()
