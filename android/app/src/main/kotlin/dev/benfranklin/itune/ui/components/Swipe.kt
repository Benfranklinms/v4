package dev.benfranklin.itune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.data.Likes
import dev.benfranklin.itune.player.Playback
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T

/** Song row swipe actions: swipe right = play next, swipe left = like. The row springs back after the action. */
@Composable
fun SwipeTrackRow(s: AppState, t: Track, content: @Composable () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val h = LocalHapticFeedback.current
    val st = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { h.performHapticFeedback(HapticFeedbackType.LongPress); Playback.playNext(ctx, t); s.toast("Playing next") }
                SwipeToDismissBoxValue.EndToStart -> { h.performHapticFeedback(HapticFeedbackType.LongPress); val now = Likes.toggle(t); s.toast(if (now) "Added to Liked Songs" else "Removed from Liked Songs") }
                else -> {}
            }
            false
        },
        positionalThreshold = { it * 0.32f },
    )
    SwipeToDismissBox(
        state = st,
        backgroundContent = {
            val dir = st.dismissDirection
            Row(
                Modifier.fillMaxSize().background(c.surf2).padding(horizontal = 24.dp),
                horizontalArrangement = if (dir == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dir == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(Icons.Rounded.PlaylistPlay, null, tint = c.txt, modifier = Modifier.size(20.dp))
                    Text("  Play next", style = T.ui(13.sp, FontWeight.SemiBold))
                } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                    Text(if (Likes.isLiked(t.id)) "Unlike  " else "Like  ", style = T.ui(13.sp, FontWeight.SemiBold))
                    Icon(Icons.Rounded.Favorite, null, tint = c.txt, modifier = Modifier.size(20.dp))
                }
            }
        },
    ) { Box(Modifier.background(c.bg)) { content() } }
}
