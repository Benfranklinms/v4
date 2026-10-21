package dev.benfranklin.itune.ui.screens

import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import dev.benfranklin.itune.ui.components.GlassIconButton
import dev.benfranklin.itune.ui.components.glass

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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

internal sealed interface IsaState {
    data object Idle : IsaState
    data class Thinking(val prompt: String) : IsaState
    data class Ready(val prompt: String, val result: IsaResult) : IsaState
    data class Failed(val prompt: String, val message: String) : IsaState
}

internal val LENGTHS = listOf("Any length" to 0, "30 min" to 30, "1 hour" to 60, "2 hours" to 120)

/** Isa's orb: glass sphere + a waveform; animation values are read only while drawing (no recomposition per frame). */
@Composable
internal fun IsaOrbGlyph(modifier: Modifier, pulse: () -> Float, wave: () -> Float, thinking: Boolean = false) {
    val c = LocalColors.current
    Canvas(modifier) {
        val pu = pulse()
        val wv = wave()
        val r = size.minDimension / 2 / 1.3f
        drawCircle(Brush.radialGradient(listOf(c.txt.copy(alpha = 0.16f + 0.10f * pu), Color.Transparent), radius = r * (1.2f + 0.1f * pu)), radius = r * 1.3f)
        drawCircle(Brush.radialGradient(listOf(Color.White, c.t3, c.surf4), center = Offset(center.x - r * 0.25f, center.y - r * 0.35f), radius = r * 1.1f), radius = r * 0.92f)
        val p = Path()
        val amp = r * (if (thinking) 0.20f else 0.10f)
        for (k in 0..24) {
            val x = center.x - r * 0.62f + r * 1.24f * k / 24f
            val y = center.y + r * 0.38f + amp * sin((k / 24f * 2 * PI + wv * 2 * PI).toFloat())
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, c.bg.copy(alpha = 0.8f), style = Stroke(width = (r * 0.09f).coerceAtLeast(2f), cap = StrokeCap.Round))
    }
}

@Composable
internal fun ThinkingRows(shimmer: androidx.compose.runtime.State<Float>, reduce: Boolean) {
    val c = LocalColors.current
    Column(Modifier.padding(top = 16.dp).testTag("isa_thinking")) {
        Text("Isa is picking songs…", style = T.ui(12.5.sp, FontWeight.SemiBold, c.t3), modifier = Modifier.padding(bottom = 10.dp))
        repeat(3) { i ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).graphicsLayer { alpha = if (reduce) 0.75f else (0.6f + 0.3f * ((shimmer.value + i * 0.3f) % 1f)).coerceIn(0.3f, 1f) }.clip(RoundedCornerShape(7.dp)).background(c.surf3))
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
internal fun Preview(s: AppState, r: IsaResult, onPlayed: () -> Unit = {}) {
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
            PillButton(Icons.Rounded.PlayArrow, "Play", primary = true, tag = "isa_play") {
                dev.benfranklin.itune.data.Recents.register(r.plan.title, dev.benfranklin.itune.data.Recents.Origin("mix", r.plan.title, r.plan.title, "Made with Isa", r.tracks.firstOrNull()?.thumb ?: ""))
                Playback.play(ctx, r.tracks, 0, r.plan.title); onPlayed()
            }
            PillButton(Icons.Rounded.Shuffle, "Shuffle", tag = "isa_shuffle") {
                dev.benfranklin.itune.data.Recents.register(r.plan.title, dev.benfranklin.itune.data.Recents.Origin("mix", r.plan.title, r.plan.title, "Made with Isa", r.tracks.firstOrNull()?.thumb ?: ""))
                Playback.playShuffled(ctx, r.tracks, r.plan.title); onPlayed()
            }
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
    Canvas(modifier.glass(RoundedCornerShape(18.dp), dark = 0.34f, sheen = 0.08f, rim = 0.65f)) {
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
