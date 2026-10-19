package dev.benfranklin.itune.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import java.text.NumberFormat

object Springs {
    val snappy = spring<Float>(dampingRatio = 0.78f, stiffness = 520f)
    val soft = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    val bouncy = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)
}

fun systemReduceMotion(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** Press-scale + haptic tick. */
fun Modifier.press(haptic: Boolean = true, scaleTo: Float = 0.94f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val s by animateFloatAsState(if (pressed && !reduce) scaleTo else 1f, Springs.bouncy, label = "press")
    val h = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .combinedClickable(
            interactionSource = src,
            indication = null,
            onLongClick = onLongClick?.let { lc -> { if (dev.benfranklin.itune.App.prefs.haptics.value) h.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
        ) {
            if (haptic && dev.benfranklin.itune.App.prefs.haptics.value) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

fun Modifier.plainClick(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** Staggered entrance: fade + rise, delayed by index. */
fun Modifier.enter(index: Int, step: Int = 38, rise: Float = 18f): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay((index.coerceAtMost(14) * step).toLong())
            a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 260f))
        }
    }
    graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * rise * density
    }
}

/** Title that fades in with a slight rise and settles its letter tracking. */
@Composable
fun RiseText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val reduce = LocalReduceMotion.current
    val a = remember(text) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(text) { if (!reduce) a.animateTo(1f, tween(520)) }
    val track = style.letterSpacing.let { if (it.isSp) it.value else 0f }
    Text(
        text,
        style = style.copy(letterSpacing = (track + (1f - a.value) * 1.4f).sp),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * 10f * density
        },
    )
}

/** Number that rolls up from 0 on first show and rolls digit-by-digit on change (JetBrains Mono). */
@Composable
fun RollingNumber(value: Int, style: TextStyle, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (reduce) anim.snapTo(value.toFloat()) else anim.animateTo(value.toFloat(), spring(dampingRatio = 1f, stiffness = 40f))
    }
    val shown = NumberFormat.getIntegerInstance().format(anim.value.toInt())
    Row(modifier) {
        shown.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(120))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(90)))
                },
                label = "digit$i",
            ) { c -> Text(c.toString(), style = style) }
        }
    }
}
