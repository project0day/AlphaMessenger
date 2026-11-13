package com.alphamessenger.ui.video

import com.alphamessenger.ui.icons.AlphaIcons
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.alphamessenger.R
import com.alphamessenger.data.EmbeddedTor
import com.alphamessenger.data.NetworkPrivacyPrefs

/**
 * Video links played in the app: the site's official embed page in a screen of its
 * own that opens as the system's floating picture-in-picture window, growing out
 * of the preview's picture (play/pause there; tap it for full screen). Plain views, not Compose, under
 * the video: the WebView's video surface must not be covered by our drawing.
 * Only the video plays here — links on the page open in the browser. In private
 * mode the player goes through the in-app Tor or does not play; cookies and
 * site data are wiped when it closes.
 */
class VideoPlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_FROM = "from"
        private const val ACTION_TOGGLE = "com.alphamessenger.video.TOGGLE"

        /** One player at a time: a new video replaces the one playing. */
        private var current: java.lang.ref.WeakReference<VideoPlayerActivity>? = null

        fun canPlay(context: Context, url: String): Boolean =
            NetworkPrivacyPrefs(context).inAppVideo && VideoSource.of(url) != null

        /**
         * [from]: the preview's picture on screen — the window grows out of it.
         * Started in the chat's own task: a separate task makes the system move the chat
         * to the top when the window forms, blanking the screen for a few frames.
         * Not launch-into-PiP: expanding that window brings the chat over the player.
         */
        fun open(context: Context, url: String, from: android.graphics.Rect? = null) {
            current?.get()?.finish()
            val activity = context.findActivity()
            val intent = Intent(context, VideoPlayerActivity::class.java).putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FROM, from)
                // No open animation: the system would slide in a full-screen backdrop under the see-through window.
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (activity ?: context).startActivity(intent)
        }

        private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
            is android.app.Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }

    private lateinit var web: WebView
    private lateinit var controls: ComposeView
    private lateinit var root: android.view.View
    private lateinit var stage: FrameLayout
    /** Set while opening: the video sits over the preview's picture until the window takes it. */
    private var launchFrom: android.graphics.Rect? = null
    private var playing = true
    private var pageUrl = ""
    private lateinit var source: VideoSource

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            web.evaluateJavascript(TOGGLE_JS, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Bars stay see-through: full screen, the player's own black lies under them;
        // while opening over the chat, they keep the chat's look.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        source = VideoSource.of(pageUrl) ?: run { finish(); return }
        if (savedInstanceState == null) {
            @Suppress("DEPRECATION")
            launchFrom = (intent.getParcelableExtra(EXTRA_FROM) as? android.graphics.Rect)?.let { inside(it, source.ratio) }
        }

        val viaTor = NetworkPrivacyPrefs(this).torEnabled
        val proxyOverride = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)
        if (viaTor && !proxyOverride) {
            Toast.makeText(this, getString(R.string.video_tor_unavailable), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (proxyOverride) {
            val pc = androidx.webkit.ProxyController.getInstance()
            if (viaTor) {
                val cfg = androidx.webkit.ProxyConfig.Builder().addProxyRule("socks5://${EmbeddedTor.PROXY_ADDR}").build()
                pc.setProxyOverride(cfg, { it.run() }, {})
            } else {
                pc.clearProxyOverride({ it.run() }, {})
            }
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // A web page, nothing of ours: no files, no content providers, no
            // plain-http parts on the https page.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SAFE_BROWSING_ENABLE)) {
                androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                // Only the video plays here: the page's links go to the browser.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && !request.url.path.orEmpty().startsWith(source.playerPath)) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(WATCH_JS, null) }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        android.util.Log.w("VideoPlayer", "PLAYER_CONSOLE ${m.message().take(200)}")
                    }
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "alpha")
            // The embed page itself, with the referrer the player requires.
            loadUrl(source.embedUrl, mapOf("Referer" to source.referer))
        }

        // Our bar sits above the video, never over it: whatever site plays, its own
        // controls stay uncovered. The video keeps its proportions, centred.
        controls = ComposeView(this).apply { setContent { Controls() } }
        stage = FrameLayout(this)
        stage.addView(web, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeVideo()
        }
        root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(controls, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stage, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Everything sits between the system bars, never under them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (launchFrom == null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        current = java.lang.ref.WeakReference(this)

        ContextCompat.registerReceiver(this, toggleReceiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!enterPip()) finish() }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(pipParams())
        // Opening: the window is see-through (translucent theme), the video lies exactly
        // over the preview's picture and the system's window grows out of that spot.
        if (launchFrom != null) {
            controls.isVisible = false
            window.decorView.post { if (!enterPip()) endLaunch() }
        } else {
            endLaunch()
        }
    }

    private fun endLaunch() {
        launchFrom = null
        root.setBackgroundColor(android.graphics.Color.BLACK)
        controls.isVisible = !isInPictureInPictureMode
        ViewCompat.requestApplyInsets(root)
        stage.post { placeVideo() }
    }

    private fun placeVideo() {
        val from = launchFrom
        web.layoutParams = if (from != null) {
            FrameLayout.LayoutParams(from.width(), from.height(), Gravity.TOP or Gravity.START)
                .apply { setMargins(from.left, from.top, 0, 0) }
        } else {
            val (w, h) = fit(stage.width, stage.height, source.ratio)
            FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
    }

    private fun inside(r: android.graphics.Rect, ratio: Rational): android.graphics.Rect {
        val (w, h) = fit(r.width(), r.height(), ratio)
        val l = r.centerX() - w / 2
        val t = r.centerY() - h / 2
        return android.graphics.Rect(l, t, l + w, t + h)
    }

    @Composable
    private fun Controls() {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(AlphaIcons.Filled.Close, stringResource(R.string.common_close)) { finish() }
            Box(Modifier.weight(1f))
            RoundButton(AlphaIcons.Filled.PictureInPictureAlt, stringResource(R.string.video_pip)) { enterPip() }
            RoundButton(AlphaIcons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.video_open_outside)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }
                finish()
            }
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }

    private fun fit(w: Int, h: Int, r: Rational): Pair<Int, Int> =
        if (w.toLong() * r.denominator <= h.toLong() * r.numerator) w to (w * r.denominator / r.numerator)
        else (h * r.numerator / r.denominator) to h

    private fun pipParams(): PictureInPictureParams {
        val toggle = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val label = getString(if (playing) R.string.video_pause else R.string.video_play)
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            label, label, toggle,
        )
        // The window grows from where the video is on screen.
        val hint = launchFrom ?: android.graphics.Rect().takeIf { ::web.isInitialized && web.getGlobalVisibleRect(it) }
        return PictureInPictureParams.Builder()
            .setAspectRatio(source.ratio)
            .setSourceRectHint(hint)
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true) }
            .build()
    }

    private fun enterPip(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::controls.isInitialized) endLaunch()
    }

    // The floating window was closed: the system stops us — the video ends with it.
    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        if (::web.isInitialized) {
            runCatching { unregisterReceiver(toggleReceiver) }
            web.stopLoading()
            web.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(isPlaying: Boolean) {
            runOnUiThread {
                // The page can still report after this player was closed (another ▶ replaced it).
                if (isFinishing || isDestroyed) return@runOnUiThread
                playing = isPlaying
                if (isInPictureInPictureMode) setPictureInPictureParams(pipParams())
            }
        }
    }

    /** Reports the video's play / pause to the app. */
    private val WATCH_JS = """
            (function(){
              function hook(){
                var v=document.querySelector('video'); if(!v){ setTimeout(hook,500); return; }
                v.addEventListener('play',function(){alpha.state(true);});
                v.addEventListener('pause',function(){alpha.state(false);});
                v.addEventListener('canplay',function(){ if(v.paused) v.play().catch(function(){}); },{once:true});
              }
              hook();
            })();
        """
    private val TOGGLE_JS = "(function(){var v=document.querySelector('video'); if(v){ if(v.paused) v.play(); else v.pause(); }})();"
}
