package dev.benfranklin.itune.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.benfranklin.itune.App
import kotlinx.coroutines.launch

/** Optional Google sign-in for YouTube Music: captures the music.youtube.com cookie for the core. */
class SignInActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                if (url.startsWith("https://music.youtube.com") && cookie.contains("SAPISID")) {
                    lifecycleScope.launch {
                        dev.benfranklin.itune.data.ioResult { App.core.signInCookie(cookie) }.onSuccess {
                            App.prefs.signedIn.value = true
                            dev.benfranklin.itune.data.Account.load(refresh = true)
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
    }
}
