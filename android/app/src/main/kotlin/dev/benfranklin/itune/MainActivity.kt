package dev.benfranklin.itune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.benfranklin.itune.player.Playback
import kotlinx.coroutines.launch
import dev.benfranklin.itune.ui.AppRoot
import dev.benfranklin.itune.ui.AppState
import dev.benfranklin.itune.ui.Dest
import dev.benfranklin.itune.ui.Sheet
import dev.benfranklin.itune.update.Updater

class MainActivity : ComponentActivity() {
    private val state = AppState()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOnce() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        val crash = CrashLog.take(this)
        dev.benfranklin.itune.data.Likes.load()
        dev.benfranklin.itune.data.Library.refresh()
        dev.benfranklin.itune.data.Net.watch(applicationContext)
        restoreSignIn()
        setContent {
            val s = remember { state }
            LaunchedEffect(Unit) { if (crash != null) s.crashReport = crash }
            LaunchedEffect(Unit) {
                // launch-time update check (at most every 6 h)
                val now = System.currentTimeMillis()
                if (now - App.prefs.lastUpdateCheck.value > 6 * 3600_000L) {
                    App.prefs.lastUpdateCheck.value = now
                    when (val r = Updater.check()) {
                        is Updater.Result.Available -> if (r.info.version != App.prefs.skippedVersion.value) s.sheet = Sheet.Update(r.info)
                        is Updater.Result.NeedsKey -> s.updateNeedsKey = true
                        else -> {}
                    }
                }
            }
            AppRoot(s)
        }
        // keep the session alive (restores lock-screen controls if music was playing)
        Playback.ensure(this) {}
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryOnce()
        }
    }

    /** OxygenOS caps apps at 60 Hz unless they ask: request the fastest mode at the current resolution. */
    private fun requestHighRefresh(on: Boolean) {
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            d ?: return
            val cur = d.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: cur
            val lp = window.attributes
            lp.preferredDisplayModeId = if (on) best.modeId else 0
            if (Build.VERSION.SDK_INT >= 30) lp.preferredRefreshRate = if (on) best.refreshRate else 0f
            window.attributes = lp
            dev.benfranklin.itune.ui.FpsOverlay.refreshHz = if (on) best.refreshRate else cur.refreshRate
            if (Build.VERSION.SDK_INT >= 35) {
                window.decorView.requestedFrameRate = if (on) best.refreshRate else android.view.View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefresh(true)
    }

    override fun onStop() {
        state.flushPending()
        dev.benfranklin.itune.player.Playback.saveSession()
        super.onStop()
    }

    /** Re-hands the saved Google cookie (WebView keeps it) to the core so the Account sheet and home use it. */
    private fun restoreSignIn() {
        if (!App.prefs.signedIn.value) return
        App.scope.launch {
            val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie("https://music.youtube.com") }.getOrNull()
            if (cookie != null && cookie.contains("SAPISID")) dev.benfranklin.itune.data.io { App.core.signInCookie(cookie) }
            dev.benfranklin.itune.data.Account.load()
        }
    }

    override fun onPause() {
        requestHighRefresh(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("open_now_playing", false)) state.nowPlaying = true
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            if (text.contains("spotify.com") || text.contains("music.apple.com")) {
                ImportLink.pending = Regex("https?://\\S+").find(text)?.value
                state.push(Dest.Import)
            }
        }
    }

    /** OnePlus/ColorOS kill background apps aggressively: ask once for "Unrestricted" battery. */
    @SuppressLint("BatteryLife")
    private fun askBatteryOnce() {
        if (App.prefs.batteryAsked.value) return
        App.prefs.batteryAsked.value = true
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}

object ImportLink {
    var pending: String? = null
}
