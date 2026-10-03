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
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
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
                runOnUiThread { updatePipParams() }
            }

            @JavascriptInterface
            fun setState(state: Int) {
                Handler(Looper.getMainLooper()).post { KeepAliveService.instance?.update(state = state) }
            }

            @JavascriptInterface
            fun setPosition(posMs: Int, durMs: Int) {
                Handler(Looper.getMainLooper()).post { KeepAliveService.instance?.updatePosition(posMs.toLong(), durMs.toLong()) }
            }

            @JavascriptInterface
            fun setTitle(title: String) {
                Handler(Looper.getMainLooper()).post { KeepAliveService.instance?.update(newTitle = title) }
            }

            @JavascriptInterface
            fun setHasList(list: Boolean) {
                Handler(Looper.getMainLooper()).post { KeepAliveService.instance?.update(list = list) }
            }

            @JavascriptInterface
            fun enterPip() {
                runOnUiThread { this@MainActivity.enterPip() }
            }
        }, "AndroidBridge")

        // Notification / lock-screen buttons -> YouTube player commands
        PlayerBus.command = { cmd ->
            runOnUiThread { web.evaluateJavascript("window.yt && yt('$cmd')", null) }
        }

        // Seek bar in the notification -> player
        PlayerBus.seek = { ms ->
            runOnUiThread { web.evaluateJavascript("window.yt && yt('seekTo',[${ms / 1000.0}, true])", null) }
        }

        // Back button: first let the page close its search panel; only then leave the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.onBackPress ? onBackPress() : false)") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        handleIntent(intent, first = true)

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
        handleIntent(intent, first = false)
    }

    /** A link shared to this app from YouTube (or anywhere) ends up here. */
    private fun handleIntent(i: Intent?, first: Boolean) {
        var url = appUrl
        var hasLink = false
        if (i != null) {
            val shared = if (i.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
            val data = i.data
            if (!shared.isNullOrBlank()) {
                url = appUrl + "?text=" + Uri.encode(shared)
                hasLink = true
            } else if (i.action == Intent.ACTION_VIEW && data != null) {
                url = if (data.scheme == "https") {
                    data.toString()                          // https://academichub.dev/re/yt-v3/?code=...
                } else {
                    val q = data.encodedQuery                // myyt://play?code=...&list=...
                    if (q.isNullOrBlank()) appUrl else "$appUrl?$q"
                }
                hasLink = true
            }
        }
        // Opening the app again (notification tap, launcher icon) must NOT reload the page,
        // otherwise the playing video, search results and queue would be lost.
        if (!first && !hasLink) return
        // "no-cache" = always fetch the newest index.html from the server (WebView caches aggressively)
        web.loadUrl(url, mapOf("Cache-Control" to "no-cache"))
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        // Android 12+: enter PiP automatically on Home / swipe-up gesture
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(videoActive)
        return b.build()
    }

    private fun updatePipParams() {
        try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { }
    }

    private fun enterPip(showErrors: Boolean = true) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (showErrors) Toast.makeText(this, "This phone does not support Picture-in-Picture", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val ok = enterPictureInPictureMode(pipParams())
            if (!ok && showErrors) askPipPermission()
        } catch (e: Exception) {
            if (showErrors) {
                Toast.makeText(this, "PiP failed: ${e.message}", Toast.LENGTH_LONG).show()
                askPipPermission()
            }
        }
    }

    /** Opens the phone setting where PiP can be switched on for this app. */
    private fun askPipPermission() {
        Toast.makeText(this, "Turn ON Picture-in-Picture for YouTube Free", Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) { }
        }
    }

    /** Called when the user presses Home / swipes up. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoActive && !isInPictureInPictureMode) enterPip(showErrors = false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        web.evaluateJavascript(
            "window.onPipChange && window.onPipChange($isInPictureInPictureMode)", null
        )
    }

    override fun onDestroy() {
        PlayerBus.command = null
        PlayerBus.seek = null
        stopService(Intent(this, KeepAliveService::class.java))
        web.destroy()
        super.onDestroy()
    }

    // NOTE: we never call web.onPause() - that would stop background audio.
}
