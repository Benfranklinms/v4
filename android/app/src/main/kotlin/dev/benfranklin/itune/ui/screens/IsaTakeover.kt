package dev.benfranklin.itune.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.data.ioResult
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.EDGE
import dev.benfranklin.itune.ui.NAV_H
import dev.benfranklin.itune.ui.components.GlassIconButton
import dev.benfranklin.itune.ui.components.LocalHaze
import dev.benfranklin.itune.ui.components.LocalChromeTint
import dev.benfranklin.itune.ui.components.chromeGlass
import dev.benfranklin.itune.ui.components.glass
import dev.benfranklin.itune.ui.components.glassStyle
import dev.benfranklin.itune.ui.components.liveBlur
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Isa's state outside any composition: the draft, the length and the last result outlive the takeover (and tab
 * switches) for 10 minutes; the draft also survives a process kill (prefs). The request runs in the app scope, so
 * closing the takeover mid-request keeps the answer for the next open.
 */
internal object IsaSession {
    private const val KEEP_MS = 10 * 60_000L
    var text by mutableStateOf("")
    var state by mutableStateOf<IsaState>(IsaState.Idle)
    private var job: Job? = null
    private var restored = false

    fun touch() { App.prefs.lastIsaAt.value = System.currentTimeMillis() }

    /** On open: restore a fresh draft, or forget everything older than 10 minutes. */
    fun onOpen() {
        val at = App.prefs.lastIsaAt.value
        val stale = at > 0 && System.currentTimeMillis() - at > KEEP_MS
        if (stale) {
            job?.cancel(); text = ""; state = IsaState.Idle
            App.prefs.isaLength.value = 0
            App.prefs.lastIsaDraft.value = ""
        } else if (!restored && text.isEmpty()) text = App.prefs.lastIsaDraft.value
        restored = true
        touch()
    }

    fun onClose() {
        App.prefs.lastIsaDraft.value = text
        touch()
    }

    fun run(prompt: String, onReady: () -> Unit) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        touch()
        state = IsaState.Thinking(p)
        job?.cancel()
        job = App.scope.launch(Dispatchers.Main) {
            val mins = LENGTHS[App.prefs.isaLength.value.coerceIn(0, LENGTHS.lastIndex)].second
            ioResult { App.core.isaQueue(p, mins.toUInt()) }
                .onSuccess { state = IsaState.Ready(p, it); android.util.Log.i("itune-isa", "ready n=${it.tracks.size}"); onReady() }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    state = IsaState.Failed(p, (e.message ?: "").substringAfter(": ").ifBlank { "Isa couldn't reach the music service. Check your connection." })
                    android.util.Log.i("itune-isa", "failed")
                }
        }
    }

    fun clear() { job?.cancel(); state = IsaState.Idle; text = ""; touch() }
}

/** The line under "What should we play?": time of day + the last thing played. Computed once per app open. */
object IsaLine {
    private var cached: String? = null
    fun get(): String = cached ?: run {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val last = dev.benfranklin.itune.data.Recents.items.value.firstOrNull()?.title
        val base = when (h) {
            in 5..11 -> "Morning — something easy to start the day?"
            in 12..16 -> "Afternoon — keep the energy up?"
            in 17..21 -> "Evening — wind down or turn it up?"
            else -> "Late night — want something slow?"
        }
        (if (last != null && h in 12..21) "More like $last, or something new?" else base).also { cached = it }
    }
}

internal val ISA_MOODS = listOf(
    Triple("Focus", "no vocals", "focus, no vocals, calm"),
    Triple("Drive", "upbeat", "night drive, upbeat"),
    Triple("Rain", "slow · mellow", "rainy day, lo-fi and mellow"),
    Triple("Hype", "loud", "hype workout bangers"),
)

/** Circle (circular reveal from the orb) as a clip shape; the radius is read at draw time. */
private class RevealShape(val center: () -> Offset, val radius: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = center().takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height)
        val r = radius()
        return Outline.Generic(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) })
    }
}

/**
 * Isa full-screen glass takeover (approved option D with F's motion). Tap the nav orb: a ripple spreads from the
 * orb, glass droplets rise and merge, the glass layer reveals from the orb; the nav pill becomes the Ask Isa input.
 * Dismiss: ×, swipe down, Back / predictive Back (reverses into the orb). Play collapses it into the mini player.
 * Reduce Motion: 150 ms crossfade. All motion values are read in draw / layer lambdas only.
 */
@Composable
fun IsaTakeover(s: AppState) {
    val reduce = LocalReduceMotion.current
    val anim = s.isaAnim
    var shown by remember { mutableStateOf(false) }
    val back = remember { mutableFloatStateOf(0f) }
    val drag = remember { mutableFloatStateOf(0f) }
    val focus = LocalFocusManager.current
    LaunchedEffect(s.isaOpen) {
        if (s.isaOpen) {
            // every open starts clean: no drag / back offset left from a previous gesture (0.2.3 stuck-flag lesson)
            back.floatValue = 0f; drag.floatValue = 0f
            shown = true
            IsaSession.onOpen()
            anim.animateTo(1f, if (reduce) tween(150) else spring(dampingRatio = 0.85f, stiffness = 300f))
        } else if (shown) {
            focus.clearFocus()
            IsaSession.onClose()
            anim.animateTo(0f, if (reduce) tween(150) else if (s.isaToMini) spring(dampingRatio = 0.85f, stiffness = 420f) else spring(dampingRatio = 0.9f, stiffness = 600f))
            shown = false
            back.floatValue = 0f; drag.floatValue = 0f
        }
    }
    if (!shown && !s.isaOpen) return
    PredictiveBackHandler(enabled = s.isaOpen) { events ->
        try {
            events.collect { back.floatValue = it.progress }
            s.closeIsa()
        } catch (e: CancellationException) {
            back.floatValue = 0f
            throw e
        }
    }
    TakeoverBody(s, reduce, back, drag)
}

@Composable
private fun TakeoverBody(s: AppState, reduce: Boolean, back: androidx.compose.runtime.MutableFloatState, drag: androidx.compose.runtime.MutableFloatState) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val anim = s.isaAnim
    val haze = LocalHaze.current
    val tint = LocalChromeTint.current
    val open = s.isaOpen
    val st = IsaSession.state
    val text = IsaSession.text
    val lengthIdx by App.prefs.isaLength.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    var ideas by remember { mutableStateOf(IsaIdeas.cached() ?: IsaIdeas.generic) }
    var ideaSet by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()
    LaunchedEffect(open, lengthIdx) { if (open) io { IsaIdeas.build(lengthIdx) }?.let { ideas = it } }
    val thinking = st is IsaState.Thinking
    fun ready() { if (App.prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun run(p: String) {
        if (p.isBlank()) return
        if (App.prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        focus.clearFocus(); sugg = emptyList()
        IsaSession.run(p) { ready() }
        // the answer shows under the title: bring it into view
        scope.launch { if (reduce) scroll.scrollTo(0) else scroll.animateScrollTo(0) }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { IsaSession.text = said; run(said) }
    }
    LaunchedEffect(text) {
        if (text.isBlank()) { sugg = emptyList(); return@LaunchedEffect }
        delay(120)
        sugg = io { App.core.isaSuggestions(text) }.orEmpty()
    }
    // orb / shimmer motion only while open and not reduced; values read in draw lambdas
    val live = open && !reduce
    val inf = rememberInfiniteTransition(label = "isa-t")
    val pulse = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (thinking) 900 else 2000), RepeatMode.Reverse), label = "pulse")
    val wave = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400)), label = "wave")
    val shimmer = inf.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300)), label = "shimmer")
    val still = remember { mutableFloatStateOf(0f) }
    val pulseS = if (live) pulse else still
    val waveS = if (live) wave else still
    val shimS = if (live && thinking) shimmer else still

    // swipe down (from the top of the scroll) dismisses; rubber-band 0.5 below the threshold
    val dismissPx = with(density) { 96.dp.toPx() }
    val flingPx = with(density) { 1200.dp.toPx() }
    val conn = remember(dismissPx, flingPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (drag.floatValue > 0f && available.y < 0f) {
                    val d = maxOf(available.y, -drag.floatValue)
                    drag.floatValue += d
                    return Offset(0f, d)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput && s.isaOpen) {
                    drag.floatValue += available.y * 0.5f
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val d = drag.floatValue
                if (d <= 0f) return Velocity.Zero
                if (d > dismissPx || available.y > flingPx) s.closeIsa()
                else animate(d, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> drag.floatValue = v }
                return available
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            // while open the takeover owns every touch; while it animates closed, touches go through (no ghost scrim)
            .pointerInput(open) { if (open) awaitEachGesture { while (true) { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final); e.changes.forEach { it.consume() }; if (e.changes.all { !it.pressed }) break } } }
            .testTag(if (open) "isa_takeover" else "isa_takeover_closing"),
    ) {
        // glass layer: live Haze blur of Home + album tint, revealed from the orb (crossfade under Reduce Motion)
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = anim.value
                    if (reduce || s.isaToMini && !s.isaOpen) {
                        alpha = p
                    } else {
                        val o = s.isaOrigin.takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height)
                        val far = hypot(maxOf(o.x, size.width - o.x), maxOf(o.y, size.height - o.y))
                        clip = true
                        shape = RevealShape({ o }, { (far * 1.05f * ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)) })
                        alpha = (p * 3f).coerceIn(0f, 1f)
                    }
                }
                .then(
                    if (haze != null && liveBlur) Modifier.hazeEffect(haze, glassStyle(c, 0.58f, 26.dp)).background(tint.copy(alpha = 0.22f))
                    else Modifier.background(c.bg.copy(alpha = 0.94f)).background(tint.copy(alpha = 0.30f)),
                ),
        )
        // ripple rings + rising droplets (F): only while the spring is in flight
        if (!reduce) Canvas(Modifier.fillMaxSize()) {
            val p = anim.value
            if (p <= 0.001f || p >= 0.999f || (s.isaToMini && !s.isaOpen)) return@Canvas
            val o = s.isaOrigin.takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height - 60.dp.toPx())
            val maxR = size.width * 0.9f
            for (k in 0..2) {
                val t = ((p - k * 0.08f) / 0.7f).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                drawCircle(c.txt.copy(alpha = 0.22f * (1f - t)), radius = maxR * t, center = o, style = Stroke(1.4.dp.toPx()))
            }
            for (i in 0 until 7) {
                val t = ((p - i * 0.035f) / 0.55f).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                val tx = size.width * (0.18f + 0.11f * i)
                val ty = size.height * (0.62f - 0.07f * ((i * 3) % 5))
                val x = o.x + (tx - o.x) * t
                val y = o.y + (ty - o.y) * t
                val r = (6.dp.toPx() + 30.dp.toPx() * t) * (1f - 0.3f * (i % 3) / 2f)
                val a = sin(PI * t).toFloat()
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f * a), c.txt.copy(alpha = 0.05f * a), Color.Transparent), center = Offset(x - r * 0.3f, y - r * 0.3f), radius = r * 1.3f), radius = r, center = Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.22f * a), radius = r, center = Offset(x, y), style = Stroke(1.dp.toPx()))
            }
        }
        // content: header, orb, title, results, tiles, length, Try
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = anim.value
                    val k = if (reduce) p else ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    alpha = k
                    val b = back.floatValue
                    val toMini = s.isaToMini && !s.isaOpen
                    val sc = (1f - 0.1f * b) * (if (toMini) 0.55f + 0.45f * p else 1f)
                    scaleX = sc; scaleY = sc
                    transformOrigin = TransformOrigin(0.5f, if (toMini) 1f else 0.4f)
                    translationY = (if (reduce) 0f else (1f - k) * 24.dp.toPx()) + drag.floatValue + (if (toMini) (1f - p) * size.height * 0.35f else 0f)
                }
                .statusBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).height(48.dp)) {
                Text("ISA", style = T.num(12.sp, FontWeight.Medium, c.t2).copy(letterSpacing = 3.sp), modifier = Modifier.align(Alignment.Center))
                GlassIconButton(Icons.Rounded.Close, "Close", size = 44.dp, iconSize = 20.dp, tag = "isa_close", modifier = Modifier.align(Alignment.CenterEnd)) { s.closeIsa() }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().nestedScroll(conn).verticalScroll(scroll)
                    .padding(horizontal = 16.dp).padding(bottom = (NAV_H + 32).dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(132.dp)) {
                        val pu = pulseS.value
                        drawCircle(c.txt.copy(alpha = 0.10f + 0.06f * pu), radius = size.minDimension / 2 * (0.92f + 0.04f * pu), style = Stroke(1.dp.toPx()))
                        drawCircle(c.txt.copy(alpha = 0.07f), radius = size.minDimension / 2 * 0.78f, style = Stroke(1.dp.toPx()))
                    }
                    IsaOrbGlyph(Modifier.size(84.dp).graphicsLayer { val k = 1f + 0.05f * pulseS.value; scaleX = k; scaleY = k }.testTag("isa_orb_big"), { pulseS.value }, { waveS.value }, thinking)
                }
                val title = when (st) {
                    is IsaState.Thinking -> st.prompt
                    is IsaState.Ready -> st.prompt
                    is IsaState.Failed -> st.prompt
                    else -> "What should we play?"
                }
                Text(title, style = T.ui(24.sp, FontWeight.Bold), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                Text(IsaLine.get(), style = T.ui(14.sp, FontWeight.Medium, c.t2), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("isa_line"))
                // live suggestions while typing
                AnimatedVisibility(sugg.isNotEmpty() && focused, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 14.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), dark = 0.30f, sheen = 0.06f, rim = 0.6f).padding(horizontal = 16.dp, vertical = 6.dp).testTag("isa_suggestions")) {
                        sugg.take(4).forEach { sgt ->
                            Row(Modifier.fillMaxWidth().press(scaleTo = 0.98f) { IsaSession.text = sgt; run(sgt) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.North, null, tint = c.t3, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = -45f })
                                Text(sgt, style = T.ui(14.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // result: thinking shimmer / queue preview / error + retry
                AnimatedContent(st, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) }, contentKey = { it::class }, label = "isa-st") { x ->
                    when (x) {
                        is IsaState.Thinking -> Box(Modifier.padding(top = 10.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), dark = 0.30f, sheen = 0.06f, rim = 0.6f).drawBehind {
                            val sx = size.width * shimS.value
                            if (sx != 0f) drawRect(Brush.linearGradient(listOf(Color.Transparent, c.txt.copy(alpha = 0.06f), Color.Transparent), start = Offset(sx - 220f, 0f), end = Offset(sx + 220f, size.height)))
                        }.padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 12.dp)) { ThinkingRows(shimS, reduce) }
                        is IsaState.Ready -> Box(Modifier.padding(top = 10.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), dark = 0.30f, sheen = 0.06f, rim = 0.6f).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                            Preview(s, x.result) { s.closeIsa(toMini = true) }
                        }
                        is IsaState.Failed -> Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(x.message, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.weight(1f))
                            Row(
                                Modifier.padding(start = 10.dp).height(38.dp).glass(RoundedCornerShape(19.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f).press { run(x.prompt) }.padding(horizontal = 14.dp).testTag("isa_retry"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Refresh, null, tint = c.txt, modifier = Modifier.size(16.dp))
                                Text("Retry", style = T.ui(13.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        else -> Spacer(Modifier.height(0.dp))
                    }
                }
                if (st !is IsaState.Idle) {
                    Text("New request", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(top = 10.dp).press { IsaSession.clear() }.padding(vertical = 6.dp).testTag("isa_clear"))
                }
                // 2×2 mood tiles
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ISA_MOODS.chunked(2).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { k, (label, sub, prompt) ->
                                Row(
                                    Modifier.weight(1f).height(64.dp).glass(RoundedCornerShape(20.dp), dark = 0.30f, sheen = 0.07f, rim = 0.65f)
                                        .press(scaleTo = 0.96f) { IsaSession.text = ""; run(prompt) }.padding(7.dp).testTag("mood_$label"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MoodArt(r * 2 + k, Modifier.size(50.dp))
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text(label, style = T.ui(14.sp, FontWeight.SemiBold), maxLines = 1)
                                        Text(sub, style = T.ui(11.5.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                // length pill
                Row(
                    Modifier.padding(top = 16.dp).height(34.dp).clip(RoundedCornerShape(17.dp)).border(0.6.dp, c.t4, RoundedCornerShape(17.dp))
                        .press { App.prefs.isaLength.value = (lengthIdx + 1) % LENGTHS.size; IsaSession.touch() }.padding(horizontal = 13.dp).testTag("isa_length"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Schedule, null, tint = c.t3, modifier = Modifier.size(14.dp))
                    AnimatedContent(LENGTHS[lengthIdx.coerceIn(0, LENGTHS.lastIndex)].first, transitionSpec = { (fadeIn(tween(160)) + androidx.compose.animation.slideInVertically { it / 2 }) togetherWith fadeOut(tween(100)) }, label = "len") { l ->
                        Text(l, style = T.ui(12.5.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(start = 7.dp))
                    }
                }
                // FOR YOU: ideas from the listener's stats / history (generic when history is thin)
                Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("FOR YOU", style = T.num(10.5.sp, FontWeight.Medium, c.t3).copy(letterSpacing = 2.sp), modifier = Modifier.weight(1f))
                    Text("Shuffle ideas", style = T.ui(13.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.press(scaleTo = 0.95f) {
                        if (App.prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        ideaSet += 1
                    }.padding(vertical = 6.dp, horizontal = 2.dp).testTag("isa_shuffle_ideas"))
                }
                val pool = ideas
                val nSets = maxOf(1, pool.size / 3)
                AnimatedContent(
                    ideaSet % nSets,
                    transitionSpec = {
                        if (reduce) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        else (fadeIn(spring(stiffness = 300f)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 380f), initialScale = 0.96f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 1.02f))
                    },
                    label = "ideas",
                ) { set ->
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pool.drop(set * 3).take(3).forEachIndexed { i, idea ->
                            Row(
                                Modifier.fillMaxWidth().height(44.dp).glass(RoundedCornerShape(22.dp), dark = 0.30f, sheen = 0.07f, rim = 0.6f)
                                    .press(scaleTo = 0.98f) { IsaSession.text = ""; run(idea.prompt) }.padding(start = 6.dp, end = 6.dp).testTag("isa_foryou_$i"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (idea.art.isNotEmpty()) dev.benfranklin.itune.ui.components.Artwork(idea.art, 32.dp, circle = true)
                                else IsaOrbGlyph(Modifier.size(32.dp), { 0.5f }, { 0f })
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(idea.idea, style = T.ui(13.5.sp, FontWeight.SemiBold).copy(lineHeight = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(idea.reason, style = T.ui(11.5.sp, FontWeight.Medium, c.t3).copy(lineHeight = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.size(32.dp).glass(CircleShape, dark = 0.28f, sheen = 0.1f, rim = 0.7f), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.NorthEast, null, tint = c.txt, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // the nav pill becomes the Ask Isa input (same place, same chrome glass), riding above the keyboard
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(bottom = 8.dp)
                .graphicsLayer {
                    val p = anim.value
                    alpha = if (reduce) p else ((p - 0.1f) / 0.5f).coerceIn(0f, 1f)
                    val toMini = s.isaToMini && !s.isaOpen
                    if (!reduce && !toMini) { val k = 0.92f + 0.08f * p; scaleX = k }
                    translationY = drag.floatValue * 0.3f
                },
        ) {
            Row(
                Modifier.padding(horizontal = EDGE.dp).fillMaxWidth().height(NAV_H.dp).chromeGlass(RoundedCornerShape(32.dp)).padding(start = 22.dp, end = 8.dp).testTag("isa_input"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("Ask Isa", style = T.ui(16.sp, FontWeight.Medium, dev.benfranklin.itune.ui.components.ChromeOnMuted))
                    BasicTextField(
                        text, { IsaSession.text = it.take(200); IsaSession.touch() }, singleLine = true,
                        textStyle = T.ui(16.sp, FontWeight.Medium, dev.benfranklin.itune.ui.components.ChromeOn),
                        cursorBrush = SolidColor(dev.benfranklin.itune.ui.components.ChromeOn),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { run(text) }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("isa_field"),
                    )
                }
                AnimatedVisibility(
                    text.isNotBlank(),
                    enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.4f) + fadeIn(tween(140)) + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 420f)),
                    exit = scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(100)) + shrinkHorizontally(tween(160)),
                ) {
                    GlassIconButton(Icons.Rounded.ArrowUpward, "Send", size = 44.dp, iconSize = 20.dp, tag = "isa_send", modifier = Modifier.padding(start = 6.dp)) { run(text) }
                }
                Box(
                    Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape).background(c.txt).press(scaleTo = 0.9f) {
                        try {
                            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we play?"))
                        } catch (_: ActivityNotFoundException) {
                            s.toast("Voice input isn't available on this phone")
                        }
                    }.testTag("isa_mic"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Mic, "Speak", tint = c.bg, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** The glowing Isa orb in the centre of the nav: breathes on a 4 s cycle; still in battery saver / Reduce Motion. */
@Composable
fun NavIsaOrb(s: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val reduce = LocalReduceMotion.current
    val saver by rememberPowerSave()
    val breathe = !reduce && !saver && !s.isaOpen
    val still = remember { mutableFloatStateOf(0.5f) }
    val zero = remember { mutableFloatStateOf(0f) }
    val b: androidx.compose.runtime.State<Float>
    val w: androidx.compose.runtime.State<Float>
    if (breathe) {
        val inf = rememberInfiniteTransition(label = "orb")
        // 4 s full cycle: 2 s in, 2 s out (sine-like ease)
        b = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
        w = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = androidx.compose.animation.core.LinearEasing)), label = "wave")
    } else { b = still; w = zero }
    Box(
        modifier
            .onGloballyPositioned { s.isaOrigin = it.boundsInRoot().center }
            .press(scaleTo = 0.9f) {
                if (App.prefs.haptics.value) view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                s.openIsa()
            }
            .testTag("nav_isa"),
        contentAlignment = Alignment.Center,
    ) {
        IsaOrbGlyph(
            Modifier.size(52.dp).graphicsLayer { val k = 1f + 0.04f * b.value; scaleX = k; scaleY = k },
            { b.value }, { w.value },
        )
    }
}

@Composable
private fun rememberPowerSave(): androidx.compose.runtime.State<Boolean> {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
    val st = remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val rc = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) { st.value = pm?.isPowerSaveMode == true }
        }
        runCatching { ctx.registerReceiver(rc, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) }
        onDispose { runCatching { ctx.unregisterReceiver(rc) } }
    }
    return st
}
