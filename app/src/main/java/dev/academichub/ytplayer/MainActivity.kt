package dev.academichub.ytplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * WebView that always reports itself as "visible".
 * Without this, the YouTube player sees the page as hidden and pauses
 * as soon as the app goes to the background or the screen turns off.
 */
class BgWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

class MainActivity : AppCompatActivity() {

    // ====== CHANGE THIS if your domain or path changes ======
    private val appUrl = "https://academichub.dev/re/yt-v3/"
    private val appHost = "academichub.dev"
    // ========================================================

    private lateinit var web: BgWebView

    // Set by the web page: true once a video has been loaded
    @Volatile
    private var videoActive = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = BgWebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return false
                if (host == appHost || host.endsWith(".$appHost")) return false
                // Anything else opens in the normal browser
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        // Functions the web page can call as  AndroidBridge.xxx()
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun clip(): String {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                return cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
            }

            @JavascriptInterface
            fun setActive(active: Boolean) {
                videoActive = active
            }

            @JavascriptInterface
            fun enterPip() {
                runOnUiThread { this@MainActivity.enterPip() }
            }
        }, "AndroidBridge")

        handleIntent(intent)

        // Android 13+ asks permission to show the "playing" notification
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Foreground service = keeps playing when the app is in the background
        startForegroundService(Intent(this, KeepAliveService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** A link shared to this app from YouTube (or anywhere) ends up here. */
    private fun handleIntent(i: Intent?) {
        val shared = if (i != null && i.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        val url = if (!shared.isNullOrBlank()) appUrl + "?text=" + Uri.encode(shared) else appUrl
        web.loadUrl(url)
    }

    private fun enterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (e: Exception) {
            // PiP not allowed on this device / disabled in settings
        }
    }

    /** Called when the user presses Home / swipes up. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoActive && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        web.evaluateJavascript(
            "window.onPipChange && window.onPipChange($isInPictureInPictureMode)", null
        )
    }

    override fun onDestroy() {
        stopService(Intent(this, KeepAliveService::class.java))
        web.destroy()
        super.onDestroy()
    }

    // NOTE: we never call web.onPause() - that would stop background audio.
}
