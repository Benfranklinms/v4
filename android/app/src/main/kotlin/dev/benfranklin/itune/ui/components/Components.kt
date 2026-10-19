package dev.benfranklin.itune.ui.components

import androidx.compose.ui.platform.testTag

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.benfranklin.itune.core.Track
import dev.benfranklin.itune.ui.theme.LocalColors
import dev.benfranklin.itune.ui.theme.T

@Composable
fun Artwork(url: String?, size: Dp, radius: Dp = 8.dp, modifier: Modifier = Modifier, circle: Boolean = false) {
    val c = LocalColors.current
    val shape = if (circle) CircleShape else RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape).background(c.surf3)) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(260).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PulseMark(Modifier.align(Alignment.Center).size(size * 0.36f), c.t4)
        }
    }
}

/** The app mark ("Pulse"): a disc with three rounded bars cut out. */
@Composable
fun PulseMark(modifier: Modifier, color: Color = LocalColors.current.txt) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val cx = size.width / 2
        val cy = size.height / 2
        val p = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r))
            val w = 0.20f * r
            listOf(-0.36f to 0.34f, 0f to 0.56f, 0.36f to 0.42f).forEach { (x, h) ->
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = cx + x * r - w / 2, top = cy - h * r, right = cx + x * r + w / 2, bottom = cy + h * r,
                        radiusX = w / 2, radiusY = w / 2,
                    ),
                )
            }
        }
        drawPath(p, color)
    }
}

/** Soft radial glow in the album colour. */
@Composable
fun Glow(color: Color, modifier: Modifier = Modifier, alpha: Float = 0.55f, centerY: Float = 0.3f, radiusFrac: Float = 0.75f) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height * centerY)
        val r = size.maxDimension * radiusFrac
        drawRect(
            Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.45f to color.copy(alpha = alpha * 0.45f),
                1f to Color.Transparent,
                center = center, radius = r,
            ),
            size = Size(size.width, size.height),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        RiseText(text, T.ui(18.sp, FontWeight.Bold), Modifier.weight(1f))
        if (action != null) Text(action, style = T.ui(12.5.sp, FontWeight.SemiBold, LocalColors.current.t3), modifier = Modifier.press { onAction?.invoke() })
    }
}

@Composable
fun TrackRow(
    t: Track,
    index: Int,
    onClick: () -> Unit,
    onMore: () -> Unit,
    playing: Boolean = false,
    trailing: String? = null,
    leadingNumber: Int? = null,
    showArt: Boolean = true,
) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().enter(index).press(onLongClick = onMore, scaleTo = 0.98f) { onClick() }.testTag("track_row").padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingNumber != null) {
            Text("$leadingNumber", style = T.num(12.sp, color = c.t3), modifier = Modifier.width(26.dp))
        }
        if (showArt) Artwork(t.thumb, 46.dp, 7.dp)
        else if (leadingNumber == null) Text("${index + 1}", style = T.num(13.sp, color = c.t3), modifier = Modifier.width(30.dp).testTag("track_number"))
        Column(Modifier.weight(1f).padding(start = if (showArt) 14.dp else 4.dp)) {
            Text(t.title, style = T.ui(14.5.sp, FontWeight.SemiBold, if (playing) c.accentUi else c.txt), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(t.artists, style = T.ui(12.sp, FontWeight.Medium, c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = T.num(11.sp, color = c.t3), modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.Rounded.MoreHoriz, "More", tint = c.t3, modifier = Modifier.padding(start = 6.dp).size(32.dp).padding(5.dp).press(onClick = onMore).testTag("track_more"))
    }
}

@Composable
fun ChipButton(label: String, icon: ImageVector? = null, modifier: Modifier = Modifier, selected: Boolean = false, mono: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.surf4 else c.surf2)
            .border(0.6.dp, c.line3, RoundedCornerShape(16.dp))
            .press(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.t2, modifier = Modifier.size(14.dp))
        Text(label, style = if (mono) T.num(12.sp, FontWeight.Medium, c.txt) else T.ui(12.sp, FontWeight.Medium, if (selected) c.txt else c.t2), maxLines = 1)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable BoxScope.() -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.surf).border(0.5.dp, c.line, RoundedCornerShape(18.dp)).padding(padding),
        content = content,
    )
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "tg")
    Box(
        Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.lerp(c.surf4, c.accentUi, x))
            .press { onChange(!on) },
    ) {
        Box(
            Modifier.padding(3.dp).size(22.dp).graphicsLayer { translationX = x * 18.dp.toPx() }
                .clip(CircleShape).background(if (on) c.onAccent else c.t2),
        )
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtDur(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
