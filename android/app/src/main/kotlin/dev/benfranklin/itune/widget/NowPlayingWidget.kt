package dev.benfranklin.itune.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.palette.graphics.Palette
import dev.benfranklin.itune.App
import dev.benfranklin.itune.MainActivity
import dev.benfranklin.itune.R
import dev.benfranklin.itune.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Home-screen widget: art, title, play/pause, next; tinted with the album colour. */
class NowPlayingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val t = Playback.current.value
        val art: Bitmap? = t?.thumb?.takeIf { it.startsWith("http") }?.let { url ->
            withContext(Dispatchers.IO) { runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
        val tint = art?.let { Palette.from(it).generate().let { p -> (p.vibrantSwatch ?: p.dominantSwatch)?.rgb } }
        val playing = Playback.playing.value
        provideContent {
            val bg = Color(0xFF111112)
            val glow = tint?.let { Color(it).copy(alpha = 0.22f) } ?: Color(0x00000000)
            Box(GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp)) {
                Box(GlanceModifier.fillMaxSize().background(glow).cornerRadius(24.dp)) {}
                Row(GlanceModifier.fillMaxSize().padding(14.dp).clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                    if (art != null) Image(ImageProvider(art), null, GlanceModifier.size(72.dp).cornerRadius(12.dp))
                    else Image(ImageProvider(R.drawable.ic_stat_glyph), null, GlanceModifier.size(40.dp))
                    Spacer(GlanceModifier.width(14.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(t?.title ?: "iTune", style = TextStyle(color = ColorProvider(Color(0xFFF2F2F2)), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text(t?.artists ?: "Nothing playing", style = TextStyle(color = ColorProvider(Color(0xFF9A9A9C)), fontSize = 12.sp), maxLines = 1)
                        Spacer(GlanceModifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(GlanceModifier.size(40.dp).background(Color(0xFFF2F2F2)).cornerRadius(20.dp).clickable(actionRunCallback<ToggleAction>()), contentAlignment = Alignment.Center) {
                                Text(if (playing) "❚❚" else "▶", style = TextStyle(color = ColorProvider(Color(0xFF09090A)), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            }
                            Spacer(GlanceModifier.width(12.dp))
                            Box(GlanceModifier.size(40.dp).clickable(actionRunCallback<NextAction>()), contentAlignment = Alignment.Center) {
                                Text("⏭", style = TextStyle(color = ColorProvider(Color(0xFFF2F2F2)), fontSize = 16.sp))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            App.scope.launch {
                runCatching {
                    if (GlanceAppWidgetManager(ctx).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) NowPlayingWidget().updateAll(ctx)
                }
            }
        }
    }
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { Playback.ensure(context) { Playback.toggle() } }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { Playback.ensure(context) { Playback.next() } }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

@Suppress("unused") private fun unused() = GlanceModifier.fillMaxWidth()
