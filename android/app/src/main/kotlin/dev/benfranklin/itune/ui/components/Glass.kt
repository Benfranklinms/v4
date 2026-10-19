package dev.benfranklin.itune.ui.components

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.benfranklin.itune.ui.theme.ItuneColors
import dev.benfranklin.itune.ui.theme.LocalColors

/**
 * Liquid Glass kit (Pure Mono). Floating chrome (top bar, bottom nav, mini player, toasts) blurs the content
 * behind it through Haze (RenderEffect on API 31+, a translucent scrim below). Glass that lives inside the
 * scrolling content (cards, the Isa card, sheets) is drawn as translucent mono glass over the album-art wash.
 * Every glass surface gets a specular top sheen and a thin light rim; colour only comes from album art.
 */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }
val LocalChrome = staticCompositionLocalOf<ChromeState?> { null }

/** Shared spring for chrome motion: soft, no bounce, medium-low stiffness. */
fun <T> glassSpring(reduce: Boolean = false) = if (reduce) spring<T>(stiffness = Spring.StiffnessHigh * 4) else spring<T>(dampingRatio = 0.8f, stiffness = 380f)

private fun glassBase(c: ItuneColors) = if (c.light) Color(0xFFF7F7F9) else Color(0xFF141416)

fun glassStyle(c: ItuneColors, dark: Float, blur: Dp = 22.dp): HazeStyle = HazeStyle(
    backgroundColor = c.bg,
    tint = HazeTint(glassBase(c).copy(alpha = dark)),
    blurRadius = blur,
    noiseFactor = 0.035f,
    fallbackTint = HazeTint(glassBase(c).copy(alpha = (dark + 0.48f).coerceAtMost(0.95f))),
)

/**
 * A glass surface. [backdrop] = blur what is behind (only for floating chrome above the haze source);
 * otherwise a translucent fill. [dark] is how much of the mono base covers the backdrop.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    dark: Float = 0.40f,
    backdrop: Boolean = false,
    sheen: Float = 0.08f,
    rim: Float = 0.6f,
    blur: Dp = 22.dp,
): Modifier {
    val c = LocalColors.current
    val haze = LocalHaze.current
    val base = glassBase(c)
    val rimColor = if (c.light) Color.Black else Color.White
    var m = this.clip(shape)
    m = if (backdrop && haze != null) m.hazeEffect(haze, glassStyle(c, dark, blur))
    else m.background(base.copy(alpha = (dark + 0.22f).coerceAtMost(0.96f)))
    return m.drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        if (sheen > 0f) {
            drawOutline(outline, Brush.verticalGradient(0f to Color.White.copy(alpha = sheen), 0.45f to Color.White.copy(alpha = sheen * 0.25f), 1f to Color.Transparent))
        }
        drawContent()
        if (rim > 0f) {
            val w = 1.dp.toPx()
            drawOutline(
                outline,
                Brush.verticalGradient(
                    0f to rimColor.copy(alpha = 0.34f * rim), 0.35f to rimColor.copy(alpha = 0.09f * rim),
                    0.75f to rimColor.copy(alpha = 0.06f * rim), 1f to rimColor.copy(alpha = 0.18f * rim),
                ),
                style = Stroke(w),
            )
        }
    }
}

/** Round glass icon button (mic, send, collapse, more, lyrics, queue, download, like). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    tint: Color = LocalColors.current.txt,
    selected: Boolean = false,
    backdrop: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalColors.current
    Box(
        modifier.size(size)
            .press(scaleTo = 0.9f, onClick = onClick)
            .glass(CircleShape, dark = if (selected) 0.10f else 0.30f, backdrop = backdrop, sheen = if (selected) 0.22f else 0.10f, rim = 0.75f)
            .then(if (selected) Modifier.background(c.txt.copy(alpha = 0.16f)) else Modifier)
            .semantics { contentDescription = desc }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize)) }
}

/** Full-bleed album art blurred into soft bokeh, faded into the mono base (Now Playing backdrop). */
@Composable
fun BlurredArt(url: String?, modifier: Modifier = Modifier, dark: Float = 0.30f) {
    val c = LocalColors.current
    Box(modifier.background(c.bg)) {
        if (!url.isNullOrEmpty()) {
            // a tiny decode scaled up is already soft on every API; RenderEffect blur smooths it on 31+
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).size(48, 48).crossfade(500).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to c.bg.copy(alpha = 0.28f + dark * 0.3f), 0.35f to c.bg.copy(alpha = dark * 0.3f),
                    0.62f to c.bg.copy(alpha = 0.16f + dark * 0.3f), 1f to c.bg.copy(alpha = 0.62f + dark * 0.3f),
                ),
            ),
        )
    }
}

/** Album-art colour bleeding into the dark base: the only colour behind the glass. */
@Composable
fun AmbientWash(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(modifier) {
        Glow(c.glow, Modifier.fillMaxSize(), alpha = if (c.light) 0.14f else 0.30f, centerY = 0.08f, radiusFrac = 0.62f)
        Glow(c.glow, Modifier.fillMaxSize().padding(start = 120.dp), alpha = if (c.light) 0.06f else 0.12f, centerY = 0.55f, radiusFrac = 0.45f)
        Glow(c.glow, Modifier.fillMaxSize().padding(end = 140.dp), alpha = if (c.light) 0.05f else 0.11f, centerY = 0.95f, radiusFrac = 0.42f)
    }
}

/** Progressive blur for the status-bar edge (strong at the top, nothing at the bottom). */
fun statusEdgeStyleProgressive() = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)

/**
 * Hide-on-scroll chrome (top bar + bottom nav). Scrolling down hides both; a small scroll up or reaching the
 * top brings them back. [scrolled] is true once the content has moved past the screen header.
 */
@Stable
class ChromeState(private val thresholdPx: Float, private val hidePx: Float, private val showPx: Float) {
    var hidden by mutableStateOf(false)
    var scrolled by mutableStateOf(false)
    private var offset = 0f
    private var acc = 0f

    fun reset() {
        offset = 0f; acc = 0f
        hidden = false; scrolled = false
    }

    fun show() { hidden = false; acc = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            acc = if ((dy < 0f) == (acc < 0f)) acc + dy else dy
            if (acc < -hidePx && offset > thresholdPx * 0.5f) hidden = true
            if (acc > showPx) hidden = false
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            offset = (offset - consumed.y).coerceAtLeast(0f)
            if (available.y > 0f && consumed.y == 0f) { offset = 0f; hidden = false }
            scrolled = offset > thresholdPx
            return Offset.Zero
        }
    }
}


/**
 * Glass dialog: a floating glass card that springs in (scale + fade), scrim behind, mono actions.
 * The last action is the primary one (#E8E8E8 pill with dark text).
 */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    tag: String? = null,
    content: @Composable () -> Unit = {},
) {
    val c = LocalColors.current
    val appear = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                Modifier
                    .androidx_graphicsLayerScale { appear.value }
                    .glass(androidx.compose.foundation.shape.RoundedCornerShape(28.dp), dark = 0.78f, sheen = 0.10f, rim = 0.8f)
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .padding(20.dp),
            ) {
                androidx.compose.material3.Text(title, style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = c.txt))
                Box(Modifier.padding(top = 12.dp)) { content() }
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(top = 18.dp).fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    actions.forEachIndexed { i, (label, act) ->
                        val primary = i == actions.lastIndex
                        Box(
                            Modifier.height(40.dp)
                                .press(scaleTo = 0.94f, onClick = act)
                                .then(
                                    if (primary) Modifier.clip(CircleShape).background(c.accentUi)
                                    else Modifier.glass(CircleShape, dark = 0.30f, sheen = 0.08f, rim = 0.6f),
                                )
                                .testTag("dialog_$label")
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (primary) c.onAccent else c.txt))
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.androidx_graphicsLayerScale(p: () -> Float): Modifier = this.then(
    Modifier.graphicsLayer {
        val v = p()
        alpha = v.coerceIn(0f, 1f)
        val k = 0.92f + 0.08f * v
        scaleX = k; scaleY = k
    },
)
