package dev.benfranklin.itune.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.App
import dev.benfranklin.itune.core.IsaResult
import dev.benfranklin.itune.data.Library
import dev.benfranklin.itune.data.io
import dev.benfranklin.itune.data.ioResult
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.components.Artwork
import dev.benfranklin.itune.ui.components.press
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private sealed interface IsaState {
    data object Idle : IsaState
    data class Thinking(val prompt: String) : IsaState
    data class Ready(val prompt: String, val result: IsaResult) : IsaState
    data class Failed(val prompt: String, val message: String) : IsaState
}

private val LENGTHS = listOf("Any length" to 0, "30 min" to 30, "1 hour" to 60, "2 hours" to 120)

/** Isa command card (approved design B): orb + ISA label, big prompt, length pill, mic, send; queue preview below. */
@Composable
fun IsaCommandCard(s: AppState, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var lengthIdx by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<IsaState>(IsaState.Idle) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun tick() { if (App.prefs.haptics.value) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    fun run(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        tick()
        focus.clearFocus()
        sugg = emptyList()
        state = IsaState.Thinking(p)
        job?.cancel()
        job = scope.launch {
            val mins = LENGTHS[lengthIdx].second
            ioResult { App.core.isaQueue(p, mins.toUInt()) }
                .onSuccess { state = IsaState.Ready(p, it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                .onFailure { state = IsaState.Failed(p, (it.message ?: "").substringAfter(": ").ifBlank { "Isa couldn't reach the music service. Check your connection." }) }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { text = said; run(said) }
    }
    LaunchedEffect(text) {
        if (text.isBlank()) { sugg = emptyList(); return@LaunchedEffect }
        delay(120)
        sugg = io { App.core.isaSuggestions(text) }.orEmpty()
    }

    val thinking = state is IsaState.Thinking
    val inf = rememberInfiniteTransition(label = "isa")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (thinking) 900 else 2600), RepeatMode.Reverse), label = "pulse")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "wave")
    val blink by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1060), RepeatMode.Restart), label = "blink")
    val shimmer by inf.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "shimmer")
    val expanded = focused || text.isNotEmpty() || state !is IsaState.Idle

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(if (reduce) tween(0) else spring(dampingRatio = 0.8f, stiffness = 380f))
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(c.surf2, c.surf)))
                .border(0.7.dp, if (focused) c.line3.copy(alpha = 1f) else c.line2, RoundedCornerShape(26.dp))
                .drawBehind {
                    if (thinking && !reduce) {
                        val x = size.width * shimmer
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, c.txt.copy(alpha = 0.06f), Color.Transparent), start = Offset(x - 220f, 0f), end = Offset(x + 220f, size.height)))
                    }
                }
                .padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)
                .testTag("isa_card"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Orb(if (reduce) 0.5f else pulse, if (reduce) 0f else wave, thinking)
                Text("ISA", style = T.num(12.sp, FontWeight.Medium, c.t2).copy(letterSpacing = 3.sp), modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(state !is IsaState.Idle, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Rounded.Close, "Clear", tint = c.t3, modifier = Modifier.size(32.dp).padding(7.dp).press { job?.cancel(); state = IsaState.Idle; text = "" }.testTag("isa_clear"))
                }
            }
            // prompt
            Box(Modifier.fillMaxWidth().padding(top = if (expanded) 16.dp else 20.dp, bottom = 14.dp)) {
                val big = if (expanded) 22.sp else 26.sp
                if (text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (val st = state) {
                                is IsaState.Thinking -> st.prompt
                                is IsaState.Ready -> st.prompt
                                is IsaState.Failed -> st.prompt
                                else -> "What should we play?"
                            },
                            style = T.ui(big, FontWeight.SemiBold, c.t2), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (!focused && state is IsaState.Idle) Box(Modifier.padding(start = 2.dp).width(2.dp).height(28.dp).graphicsLayer { alpha = if (reduce || blink < 0.5f) 1f else 0f }.background(c.txt))
                    }
                }
                BasicTextField(
                    text, { text = it.take(200) }, singleLine = false, maxLines = 3,
                    textStyle = T.ui(big, FontWeight.SemiBold),
                    cursorBrush = SolidColor(c.txt),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { run(text) }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("isa_field"),
                )
            }
            // live suggestions
            AnimatedVisibility(sugg.isNotEmpty() && focused, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(bottom = 10.dp)) {
                    sugg.forEach { sgt ->
                        Row(Modifier.fillMaxWidth().press(scaleTo = 0.98f) { text = sgt; run(sgt) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.North, null, tint = c.t3, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = -45f })
                            Text(sgt, style = T.ui(14.sp, FontWeight.Medium, c.t2), modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(c.surf3).press { lengthIdx = (lengthIdx + 1) % LENGTHS.size }
                        .padding(horizontal = 16.dp).testTag("isa_length"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Schedule, null, tint = c.t2, modifier = Modifier.size(16.dp))
                    AnimatedContent(LENGTHS[lengthIdx].first, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "len") { l ->
                        Text(l, style = T.ui(13.5.sp, FontWeight.SemiBold, c.t2), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(c.surf3).press {
                        try {
                            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we play?"))
                        } catch (_: ActivityNotFoundException) {
                            s.toast("Voice input isn't available on this phone")
                        }
                    }.semantics { contentDescription = "Speak" }.testTag("isa_mic"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Mic, null, tint = c.txt, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (text.isNotBlank()) c.accentUi else c.txt.copy(alpha = 0.9f)).press {
                        if (text.isNotBlank()) run(text) else if (state is IsaState.Failed) run((state as IsaState.Failed).prompt) else s.toast("Tell Isa what you're in the mood for")
                    }.semantics { contentDescription = "Send" }.testTag("isa_send"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ArrowUpward, null, tint = if (text.isNotBlank()) c.onAccent else c.bg, modifier = Modifier.size(22.dp)) }
            }
            // result area
            AnimatedContent(
                state, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                contentKey = { it::class }, label = "isa-state",
            ) { st ->
                when (st) {
                    is IsaState.Thinking -> ThinkingRows(shimmer)
                    is IsaState.Ready -> Preview(s, st.result)
                    is IsaState.Failed -> Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.message, style = T.ui(13.sp, FontWeight.Medium, c.t2), modifier = Modifier.weight(1f))
                        Row(
                            Modifier.padding(start = 10.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.surf3).press { run(st.prompt) }.padding(horizontal = 14.dp).testTag("isa_retry"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = c.txt, modifier = Modifier.size(16.dp))
                            Text("Retry", style = T.ui(13.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
        }
        // mood tiles
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Focus" to "focus, no vocals, calm", "Drive" to "night drive, upbeat", "Rain" to "rainy day, lo-fi and mellow", "Hype" to "hype workout bangers").forEachIndexed { i, (label, prompt) ->
                Column(Modifier.weight(1f).press(scaleTo = 0.95f) { text = ""; run(prompt) }.testTag("mood_$label")) {
                    MoodArt(i, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(label, style = T.ui(14.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Orb(pulse: Float, wave: Float, thinking: Boolean) {
    val c = LocalColors.current
    Canvas(Modifier.size(28.dp)) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(c.txt.copy(alpha = 0.18f + 0.22f * pulse), Color.Transparent), radius = r * (1.2f + 0.3f * pulse)), radius = r * 1.3f)
        drawCircle(Brush.radialGradient(listOf(Color.White, c.t3, c.surf4), center = Offset(center.x - r * 0.25f, center.y - r * 0.35f), radius = r * 1.1f), radius = r * 0.82f)
        // waveform across the lower half
        val p = Path()
        val amp = r * (if (thinking) 0.22f else 0.12f)
        for (k in 0..24) {
            val x = center.x - r * 0.7f + r * 1.4f * k / 24f
            val y = center.y + r * 0.35f + amp * sin((k / 24f * 2 * PI + wave * 2 * PI).toFloat())
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, c.bg.copy(alpha = 0.8f), style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun ThinkingRows(shimmer: Float) {
    val c = LocalColors.current
    Column(Modifier.padding(top = 16.dp).testTag("isa_thinking")) {
        Text("Isa is picking songs…", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(bottom = 10.dp))
        repeat(3) { i ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)).background(c.surf3.copy(alpha = 0.6f + 0.3f * ((shimmer + i * 0.3f) % 1f))))
                Column(Modifier.padding(start = 12.dp)) {
                    Box(Modifier.width((150 - i * 24).dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surf3))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width((90 - i * 10).dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surf3.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun Preview(s: AppState, r: IsaResult) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val total = r.tracks.sumOf { it.durationS.toLong().let { d -> if (d > 0) d else 210L } }
    val mins = (total / 60).toInt()
    var saved by remember(r) { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp).testTag("isa_preview")) {
        Row(Modifier.fillMaxWidth().press(scaleTo = 0.98f) { s.push(Dest.Mix(r.plan.title, r.tracks)) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(86.dp, 64.dp)) {
                r.tracks.take(3).forEachIndexed { i, t ->
                    Box(Modifier.offset(x = (i * 11).dp, y = (i * 2).dp).graphicsLayer { alpha = 1f - i * 0.12f }) { Artwork(t.thumb, 58.dp, 9.dp) }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(r.plan.title, style = T.ui(16.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.tracks.size} songs · ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"}", style = T.num(12.sp, color = c.t2))
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(Icons.Rounded.PlayArrow, "Play", primary = true, tag = "isa_play") { Playback.play(ctx, r.tracks, 0, r.plan.title) }
            PillButton(Icons.Rounded.Shuffle, "Shuffle", tag = "isa_shuffle") { Playback.playShuffled(ctx, r.tracks, r.plan.title) }
            PillButton(Icons.AutoMirrored.Rounded.PlaylistAdd, if (saved) "Saved" else "Save", tag = "isa_save") {
                if (saved) return@PillButton
                scope.launch {
                    val p = io { App.core.createPlaylistNamed(r.plan.title, "Made with Isa", "", r.tracks) }
                    if (p != null) { saved = true; Library.upsert(p); s.toast("Saved as ${p.title}") } else s.toast("Couldn't save the playlist")
                }
            }
        }
    }
}

@Composable
internal fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, tag: String = "", onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (primary) c.accentUi else c.surf3).press(onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) c.onAccent else c.txt, modifier = Modifier.size(17.dp))
        Text(label, style = T.ui(13.sp, FontWeight.Bold, if (primary) c.onAccent else c.txt), modifier = Modifier.padding(start = 6.dp))
    }
}

/** Mono pattern art for the mood tiles: rings (Focus), stripes (Drive), waves (Rain), dots (Hype). */
@Composable
fun MoodArt(kind: Int, modifier: Modifier) {
    val c = LocalColors.current
    Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(c.surf).border(0.6.dp, c.line2, RoundedCornerShape(18.dp))) {
        val w = size.width
        val h = size.height
        when (kind) {
            0 -> for (k in 1..14) drawCircle(c.t3.copy(alpha = 0.55f), radius = w * 0.05f * k, center = Offset(w * 0.45f, h * 0.55f), style = Stroke(1.2f))
            1 -> {
                drawRect(c.surf3)
                for (k in -4..8) {
                    val x = k * w * 0.16f
                    val p = Path().apply { moveTo(x, h); lineTo(x + w * 0.08f, h); lineTo(x + w * 0.08f + h, 0f); lineTo(x + h, 0f); close() }
                    drawPath(p, if (k == 4) c.t2 else if (k % 2 == 0) c.t4 else c.surf4)
                }
            }
            2 -> {
                drawRect(Brush.verticalGradient(listOf(c.t3, c.surf3)))
                for (k in 0..4) {
                    val p = Path()
                    val y0 = h * (0.32f + k * 0.17f)
                    p.moveTo(0f, h)
                    for (i in 0..30) {
                        val x = w * i / 30f
                        p.lineTo(x, y0 + h * 0.05f * sin((i / 30f * 2.4f * PI + k).toFloat()))
                    }
                    p.lineTo(w, h); p.close()
                    drawPath(p, c.surf.copy(alpha = 0.35f + k * 0.12f))
                }
            }
            else -> {
                val n = 7
                for (i in 0 until n) for (j in 0 until n) {
                    val big = (i * 3 + j * 5) % 4 == 0
                    val hot = i == 4 && j == 5
                    drawCircle(if (hot) c.txt else c.t3.copy(alpha = if (big) 0.8f else 0.5f), radius = w * (if (hot) 0.055f else if (big) 0.034f else 0.022f), center = Offset(w * (i + 0.5f) / n, h * (j + 0.5f) / n))
                }
            }
        }
    }
}
