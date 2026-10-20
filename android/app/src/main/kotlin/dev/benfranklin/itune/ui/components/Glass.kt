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
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
/** Haze over everything under the sheets (content, chrome and Now Playing): sheets blur what they cover. */
val LocalSheetHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Live RenderEffect blur is off on the Android emulator only: the CI emulator's software GPU (SwiftShader /
 * gfxstream) crashed the whole emulator mid-animation with blur layers. Phones always get the real blur;
 * the emulator draws the same glass with the translucent fallback tint.
 */
val blurDecision: BlurDecision by lazy {
    val d = GlassPolicy.decide(Build.HARDWARE.orEmpty(), Build.FINGERPRINT.orEmpty(), Build.PRODUCT.orEmpty(), Build.VERSION.SDK_INT)
    // emulator-only probe (`setprop debug.itune.blur 1`, non-gating CI leg): run the real Haze path there too
    if (!d.liveBlur && sysProp("debug.itune.blur") == "1") BlurDecision(true, "forced:debug.itune.blur(${d.reason})") else d
}

private fun sysProp(k: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
}.getOrNull()
val liveBlur: Boolean get() = blurDecision.liveBlur

/** One line at start (tag itune-glass): whether this device draws live blur, and why. Smoke reads it. */
fun logGlassState() {
    android.util.Log.i("itune-glass", "liveBlur=${blurDecision.liveBlur} reason=${blurDecision.reason} lite=$liteGfx sdk=${Build.VERSION.SDK_INT} hw=${Build.HARDWARE}")
}
/**
 * Emulator-only diagnostic switch (`adb shell setprop debug.itune.lite 1`): glass draws as flat translucent
 * fills without gradient sheen/rim and the ambient wash is off. Never on for phones (requires !liveBlur).
 */
val liteGfx: Boolean by lazy {
    !liveBlur && runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.itune.lite") as String
    }.getOrNull() == "1"
}
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
    hazeState: HazeState? = null,
    /** Fill alpha when there is no live blur (emulator, old phones): sheets stay opaque enough that nothing ghosts through. */
    fallbackAlpha: Float? = null,
    /** Extra colour laid over the blurred backdrop (live blur only); null = none (every glass surface but one). */
    tint: Color? = null,
): Modifier {
    val c = LocalColors.current
    val haze = hazeState ?: LocalHaze.current
    val base = glassBase(c)
    val rimColor = if (c.light) Color.Black else Color.White
    var m = this.clip(shape)
    m = if (backdrop && haze != null && liveBlur) m.hazeEffect(haze, glassStyle(c, dark, blur)).let { if (tint != null) it.background(tint) else it }
    else m.background(base.copy(alpha = fallbackAlpha ?: (dark + 0.22f).coerceAtMost(0.96f)))
    if (liteGfx) return m
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
    // the active look is driven only by [selected] and animates fully back to idle when it turns off
    val k by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, androidx.compose.animation.core.tween(180), label = "sel")
    Box(
        modifier.size(size)
            .press(scaleTo = 0.9f, onClick = onClick)
            .glass(CircleShape, dark = 0.30f - 0.20f * k, backdrop = backdrop, sheen = 0.10f + 0.12f * k, rim = 0.75f)
            .then(if (k > 0.001f) Modifier.background(c.txt.copy(alpha = 0.16f * k), CircleShape) else Modifier)
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
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31 && liveBlur) Modifier.blur(36.dp) else Modifier),
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
    if (liteGfx) { Box(modifier); return }
    val theme by dev.benfranklin.itune.App.prefs.theme.state.collectAsState()
    // Adaptive on dark art (e.g. a near-black teal cover with no vibrant swatch): the raw swatch is so dark that
    // the wash vanished while the bars, which normalise the colour (ChromeTint), were still tinted. Lift only
    // such dark colours to a floor brightness, same hue and saturation; normal art is untouched.
    val glow = if (theme == "adaptive" && !c.light) washFloor(c.glow) else c.glow
    // Same three glows as before, but each drawn across the WHOLE screen: the second and third used to sit in a
    // padded Canvas (start 120dp / end 140dp) whose edge cut their gradient off, leaving vertical seams at about
    // 1/3 and 2/3 of the width. Centres, radii, colours and alphas are unchanged.
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        fun wash(left: Float, right: Float, alpha: Float, centerY: Float, radiusFrac: Float) {
            val bw = (right - left).coerceAtLeast(1f)
            val r = maxOf(bw, h) * radiusFrac
            drawRect(
                Brush.radialGradient(
                    0f to glow.copy(alpha = alpha), 0.45f to glow.copy(alpha = alpha * 0.45f), 1f to Color.Transparent,
                    center = Offset(left + bw / 2, h * centerY), radius = r,
                ),
            )
        }
        val l = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr
        val p120 = 120.dp.toPx(); val p140 = 140.dp.toPx()
        wash(0f, w, if (c.light) 0.14f else 0.30f, 0.08f, 0.62f)
        if (l) wash(p120, w, if (c.light) 0.06f else 0.12f, 0.55f, 0.45f) else wash(0f, w - p120, if (c.light) 0.06f else 0.12f, 0.55f, 0.45f)
        if (l) wash(0f, w - p140, if (c.light) 0.05f else 0.11f, 0.95f, 0.42f) else wash(p140, w, if (c.light) 0.05f else 0.11f, 0.95f, 0.42f)
    }
}

/** Minimum HSV brightness of the Adaptive wash colour (dark album art only; brighter swatches pass through). */
internal fun washFloor(c: Color, minV: Float = 0.55f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    if (hsv[2] >= minV) return c
    hsv[2] = minV
    return Color(android.graphics.Color.HSVToColor(hsv))
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
                    .glass(androidx.compose.foundation.shape.RoundedCornerShape(28.dp), dark = 0.78f, sheen = 0.10f, rim = 0.8f, fallbackAlpha = 1f)
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

/**
 * Bottom content padding for every scrolling page: the floating glass nav + mini player (when something is
 * loaded) + the system navigation bar + breathing room, so the last row is never hidden under the player.
 */
@Composable
fun bottomInset(extra: Dp = 24.dp): Dp {
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val playing = dev.benfranklin.itune.player.Playback.current.collectAsState().value != null
    return nav + 8.dp + dev.benfranklin.itune.ui.NAV_H.dp + (if (playing) (dev.benfranklin.itune.ui.MINI_H + 10).dp else 0.dp) + extra
}

/** The album-tinted chrome colour (animated ~400 ms on track change); #09090A with nothing playing. */
// dynamic (not static): the tint animates for 400 ms on every track change, and a static local re-composed the
// whole app on each of those frames; now only the chrome that reads it does
val LocalChromeTint = androidx.compose.runtime.compositionLocalOf { Color(ChromeTint.NO_TRACK) }

/** White text / icons on the tinted chrome, and the inactive label colour (>= 4.5:1, see [ChromeTint]). */
val ChromeOn = Color(ChromeTint.LABEL_ACTIVE)
val ChromeOnMuted = Color(ChromeTint.LABEL_INACTIVE)

/** Subtle shadow under chrome text, so glyphs keep an edge over bright art moving behind the glass. */
val ChromeTextShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, 1f), 3f)

/**
 * Floating chrome glass (mini player, bottom nav, top bar): real Haze blur (~40dp) of what scrolls behind,
 * tinted with the album colour at [ChromeTint.ALPHA], 2% grain, a 1px hairline (white 30% at the top fading to
 * 6%). Without live blur (emulator) an opaque tinted fill.
 */
@Composable
fun Modifier.chromeGlass(shape: Shape, haze: HazeState? = LocalHaze.current): Modifier {
    val c = LocalColors.current
    val tint = LocalChromeTint.current
    var m = this.clip(shape)
    m = if (haze != null && liveBlur) m.hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = c.bg,
            tint = HazeTint(tint.copy(alpha = ChromeTint.ALPHA)),
            blurRadius = 40.dp,
            noiseFactor = 0.02f,
            fallbackTint = HazeTint(tint.copy(alpha = 0.97f)),
        ),
    ) else m.background(tint)
    if (liteGfx) return m
    return m.drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(
            outline,
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.5f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.06f)),
            style = Stroke(1.dp.toPx()),
        )
    }
}
