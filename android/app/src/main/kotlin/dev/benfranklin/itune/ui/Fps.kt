package dev.benfranklin.itune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.ui.theme.T
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug FPS overlay (long-press the Version row in Settings). */
object FpsOverlay {
    val enabled = MutableStateFlow(false)
    @Volatile var refreshHz = 0f
}

@Composable
fun FpsLayer() {
    val on by FpsOverlay.enabled.collectAsState()
    if (!on) return
    var fps by remember { mutableIntStateOf(0) }
    var worst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var start = 0L
        var last = 0L
        var maxGap = 0L
        while (true) {
            withFrameNanos { t ->
                if (start == 0L) start = t
                if (last != 0L) maxGap = maxOf(maxGap, t - last)
                last = t
                frames++
                if (t - start >= 1_000_000_000L) {
                    fps = frames
                    worst = (maxGap / 1_000_000).toInt()
                    frames = 0
                    start = t
                    maxGap = 0
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            "$fps fps · ${FpsOverlay.refreshHz.toInt()} Hz · max ${worst} ms",
            style = T.num(11.sp, color = Color.White),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
