package com.alphamessenger.ui.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alphamessenger.R
import com.alphamessenger.data.EmbeddedTor
import com.alphamessenger.data.NetworkPrivacyPrefs
import com.alphamessenger.ui.theme.AlphaTheme
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLEncoder

/** One open page. The WebView is made when the tab is first shown, so restored tabs cost nothing. */
@Stable
class BrowserTab(initialUrl: String) {
    val id = nextId++
    var web: WebView? = null
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var icon by mutableStateOf<Bitmap?>(null)
    var thumb by mutableStateOf<Bitmap?>(null)
    /** Requests blocked on the page now shown. */
    var blocked by mutableIntStateOf(0)
    /** The page address for the network threads (they must not read Compose state). */
    @Volatile var pageUrl: String = initialUrl

    private companion object { var nextId = 1L }
}

/**
 * The built-in browser: its own task (sending a link to a chat brings the messenger
 * forward and leaves the browser in recents), one instance, new links come in as tabs.
 * In private mode pages go through the in-app Tor or do not load.
 */
@AndroidEntryPoint
class BrowserActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val MAX_TABS = 12

        /** Title, then the readable blocks of the page's article (or its main part). */
        private const val READER_JS = """(function(){
            var root = document.querySelector('article') || document.querySelector('main') || document.body;
            var out = [document.title || ''];
            var nodes = root.querySelectorAll('h1,h2,h3,p,li,blockquote,pre');
            for (var i = 0; i < nodes.length && out.length < 800; i++) {
                var t = (nodes[i].innerText || '').trim();
                if (t.length > 1) out.push(t);
            }
            return JSON.stringify(out);
        })()"""

        /** Adds the element-hiding stylesheet once per page. */
        private fun hideJs(css: String): String =
            "(function(){if(document.getElementById('alpha-eh'))return;var s=document.createElement('style');s.id='alpha-eh';s.textContent=" +
                org.json.JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})()"

        /** Cookies, sign-ins, site storage and the saved tabs; also from the settings screen. */
        fun wipeSiteData(context: Context) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            BrowserPrefs(context).tabs = emptyList()
        }

        /** "Clear browser data" from the settings: the open browser clears itself, else the stores directly. */
        fun clearEverything(context: Context) {
            val open = BrowserSession.activity?.get()
            if (open != null && !open.isFinishing) { open.clearAll(); return }
            wipeSiteData(context)
            runCatching { WebView(context).apply { clearCache(true); destroy() } }
        }

        fun isWebLink(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        fun open(context: Context, url: String) {
            context.startActivity(
                Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val themeViewModel: com.alphamessenger.ui.theme.ThemeViewModel by viewModels()
    private lateinit var prefs: BrowserPrefs
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout

    val tabs = mutableStateListOf<BrowserTab>()
    var active by mutableIntStateOf(0)
        private set
    var showTabs by mutableStateOf(false)
    var desktop by mutableStateOf(false)
        private set
    var linksInside by mutableStateOf(true)
        private set
    var adBlock by mutableStateOf(true)
        private set
    /** Find on page: the query, and "match i of n" once the page answers. */
    var findQuery by mutableStateOf<String?>(null)
    var findIndex by mutableIntStateOf(0)
        private set
    var findCount by mutableIntStateOf(0)
        private set
    /** Reader view: the page's title and paragraphs, or null when closed. */
    var reader by mutableStateOf<Pair<String, List<String>>?>(null)

    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    val current: BrowserTab? get() = tabs.getOrNull(active)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = BrowserPrefs(this)
        desktop = prefs.desktopMode
        linksInside = prefs.openLinksInside
        adBlock = prefs.adBlock
        if (!routeProxy()) return
        if (adBlock) AdBlock.warmUp(this)

        stage = FrameLayout(this)
        root = FrameLayout(this)
        root.addView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val variant by themeViewModel.themeVariant.collectAsState()
                val customTheme by themeViewModel.activeCustom.collectAsState()
                AlphaTheme(variant = variant, custom = customTheme) { BrowserScreen(this@BrowserActivity, stage) }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        BrowserSession.activity = java.lang.ref.WeakReference(this)

        prefs.tabs.forEach { tabs += BrowserTab(it) }
        active = prefs.activeTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        val incoming = intent.getStringExtra(EXTRA_URL)
        if (incoming != null) openInNewTab(incoming) else if (tabs.isEmpty()) newTab() else show(active)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = current?.web
                when {
                    custom != null -> customCallback?.onCustomViewHidden()
                    reader != null -> reader = null
                    findQuery != null -> closeFind()
                    showTabs -> showTabs = false
                    web != null && web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { showTabs = false; openInNewTab(it) }
    }

    /** False = private mode wants Tor and this WebView cannot use a proxy: closed. */
    private fun routeProxy(): Boolean {
        val viaTor = NetworkPrivacyPrefs(this).torEnabled
        val supported = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)
        if (viaTor && !supported) {
            Toast.makeText(this, getString(R.string.video_tor_unavailable), Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        if (supported) {
            val pc = androidx.webkit.ProxyController.getInstance()
            if (viaTor) {
                pc.setProxyOverride(
                    androidx.webkit.ProxyConfig.Builder().addProxyRule("socks5://${EmbeddedTor.PROXY_ADDR}").build(), { it.run() }, {},
                )
            } else {
                pc.clearProxyOverride({ it.run() }, {})
            }
        }
        return true
    }

    val viaTor: Boolean get() = NetworkPrivacyPrefs(this).torEnabled

    // --- tabs ---

    fun newTab() {
        if (tabs.size >= MAX_TABS) closeTab(0)
        tabs += BrowserTab("")
        show(tabs.lastIndex)
    }

    private fun openInNewTab(url: String) {
        // The same address already open: back to it rather than a duplicate.
        val same = tabs.indexOfFirst { it.url == url }
        if (same >= 0) { show(same); return }
        if (tabs.size >= MAX_TABS) closeTab(0)
        tabs += BrowserTab(url)
        show(tabs.lastIndex)
    }

    fun show(index: Int) {
        if (index !in tabs.indices) return
        active = index
        val tab = tabs[index]
        val web = tab.web ?: makeWeb(tab).also { w -> if (tab.url.isNotEmpty()) w.loadUrl(tab.url) }
        if (web.parent !== stage) {
            stage.removeAllViews()
            (web.parent as? ViewGroup)?.removeView(web)
            stage.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        showTabs = false
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        // Closing from the switcher keeps the switcher open.
        val inSwitcher = showTabs
        tabs.removeAt(index)
        tab.web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        when {
            tabs.isEmpty() -> newTab()
            index < active || active > tabs.lastIndex -> show((active - 1).coerceAtLeast(0))
            index == active -> show(active.coerceAtMost(tabs.lastIndex))
        }
        showTabs = inSwitcher
    }

    /** Folds the browser into a bar in the messenger; the pages stay as they are. */
    fun fold() {
        val tab = current
        BrowserSession.fold(BrowserSession.Folded(tab?.title.orEmpty(), hostOf(tab?.url.orEmpty()), tab?.icon, tabs.size))
        startActivity(Intent(this, com.alphamessenger.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The switcher shows each page as it looked: a small picture taken on the way there. */
    fun openSwitcher() {
        current?.let { tab -> tab.web?.let { tab.thumb = snapshot(it) } }
        showTabs = true
    }

    private fun snapshot(web: WebView): Bitmap? = runCatching {
        if (web.width == 0 || web.height == 0) return null
        val scale = 0.4f
        val bmp = Bitmap.createBitmap((web.width * scale).toInt(), (web.height * scale).toInt(), Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(bmp)
        c.scale(scale, scale)
        c.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
        web.draw(c)
        bmp
    }.getOrNull()

    /** Closes every tab and wipes cookies, sign-ins, site storage and the cache. */
    fun clearAll() {
        tabs.forEach { t -> t.web?.let { it.clearCache(true); (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }
        tabs.clear()
        wipeSiteData(this)
        newTab()
    }

    // --- page actions ---

    fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            isWebLink(text) -> text
            !text.contains(' ') && Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(text) -> "https://$text"
            else -> prefs.searchUrl(text)
        }
        val tab = current ?: return
        tab.url = url
        (tab.web ?: makeWeb(tab).also { show(active) }).loadUrl(url)
    }

    fun back() { current?.web?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { current?.web?.takeIf { it.canGoForward() }?.goForward() }
    fun reloadOrStop() { current?.web?.let { if (current?.loading == true) it.stopLoading() else it.reload() } }

    /** The address goes to a chat the person picks, through the messenger's own share flow. */
    fun sendToChat() {
        val url = current?.url?.takeIf { it.isNotEmpty() } ?: return
        startActivity(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                .setClass(this, com.alphamessenger.ui.MainActivity::class.java)
        )
    }

    fun share() {
        val url = current?.url?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
        }
    }

    fun copyLink() { current?.url?.takeIf { it.isNotEmpty() }?.let(::copyText) }

    fun copyText(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
        Toast.makeText(this, getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
    }

    fun openOutside() {
        val url = current?.url?.takeIf { it.isNotEmpty() } ?: return
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), getString(R.string.chat_open_with))) }
    }

    fun setDesktopMode(on: Boolean) {
        desktop = on
        prefs.desktopMode = on
        tabs.forEach { t -> t.web?.let { applyAgent(it) } }
        current?.web?.reload()
    }

    fun setOpenLinksInside(on: Boolean) {
        linksInside = on
        prefs.openLinksInside = on
    }

    fun setAdBlocking(on: Boolean) {
        adBlock = on
        prefs.adBlock = on
        if (on) AdBlock.warmUp(this)
        current?.web?.reload()
    }

    // --- find on page ---

    fun find(query: String) {
        findQuery = query
        if (query.isEmpty()) { current?.web?.clearMatches(); findCount = 0; findIndex = 0; return }
        current?.web?.findAllAsync(query)
    }

    fun findNext(forward: Boolean) { current?.web?.findNext(forward) }

    fun closeFind() {
        current?.web?.clearMatches()
        findQuery = null
        findCount = 0
        findIndex = 0
    }

    // --- reader view ---

    /** The page's article as plain paragraphs: no menus, no ads, the text alone. */
    fun openReader() {
        val web = current?.web ?: return
        web.evaluateJavascript(READER_JS) { raw ->
            val json = runCatching { org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as? String ?: raw) }.getOrNull()
            val parts = (0 until (json?.length() ?: 0)).map { json!!.optString(it) }.filter { it.isNotBlank() }
            reader = if (parts.size > 1) parts.first() to parts.drop(1)
            else (current?.title.orEmpty() to listOf(getString(R.string.browser_reader_empty)))
        }
    }

    // --- the WebView ---

    private var mobileAgent: String? = null

    private fun applyAgent(web: WebView) {
        val base = mobileAgent ?: web.settings.userAgentString.also { mobileAgent = it }
        web.settings.userAgentString = if (desktop) {
            base.replace(Regex("""\(Linux; Android[^)]*\)"""), "(X11; Linux x86_64)").replace(" Mobile", "")
        } else base
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = desktop
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(tab: BrowserTab): WebView {
        val web = WebView(this)
        tab.web = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // A web page, nothing of ours: no files, no content providers, no plain-http parts on https pages.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(web.settings, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, prefs.thirdPartyCookies)
        web.settings.textZoom = prefs.textZoom
        applyAgent(web)
        web.setFindListener { activeMatch, count, _ ->
            if (current === tab) { findIndex = activeMatch; findCount = count }
        }
        web.setBackgroundColor(android.graphics.Color.WHITE)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isWebLink(url)) return false
                openApp(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.url = url
                tab.pageUrl = url
                tab.loading = true
                tab.icon = favicon
                tab.blocked = 0
            }

            // Ads and trackers never load; the page gets an empty answer in their place.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (!adBlock || request.isForMainFrame) return null
                val url = request.url.toString()
                if (!AdBlock.shouldBlock(url, tab.pageUrl)) return null
                runOnUiThread { tab.blocked++ }
                return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageCommitVisible(view: WebView, url: String) { hideAds(view, url) }

            override fun onPageFinished(view: WebView, url: String) {
                hideAds(view, url)
                tab.loading = false
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
                saveTabs()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                tab.url = url
                tab.canBack = view.canGoBack()
                tab.canForward = view.canGoForward()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress / 100f }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty() }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) { tab.icon = icon }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (custom != null) { callback.onCustomViewHidden(); return }
                custom = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.setBackgroundColor(android.graphics.Color.BLACK)
                WindowInsetsControllerCompat(window, root).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() {
                custom?.let { root.removeView(it) }
                custom = null
                customCallback = null
                WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
            }

            // target=_blank and window.open after a tap: a new tab.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                if (tabs.size >= MAX_TABS) closeTab(0)
                val child = BrowserTab("")
                tabs += child
                val childWeb = makeWeb(child)
                (resultMsg.obj as WebView.WebViewTransport).webView = childWeb
                resultMsg.sendToTarget()
                show(tabs.lastIndex)
                return true
            }

            override fun onCloseWindow(window: WebView) {
                val i = tabs.indexOfFirst { it.web === window }
                if (i >= 0) closeTab(i)
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return runCatching { pickFiles.launch(params.createIntent()); true }.getOrElse {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            // Camera, microphone and location are not given to sites.
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        web.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
        return web
    }

    private fun hideAds(view: WebView, url: String) {
        if (!adBlock) return
        val css = AdBlock.css(AdBlockEngine.hostOf(url))
        if (css.isNotEmpty()) view.evaluateJavascript(hideJs(css), null)
    }

    /** tel:, mailto:, intent: and app links go to the app that handles them. */
    private fun openApp(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && isWebLink(fallback)) current?.web?.loadUrl(fallback)
                else Toast.makeText(this, getString(R.string.browser_no_app), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.browser_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        if (!isWebLink(url)) return
        val name = URLUtil.guessFileName(url, disposition, mime)
        // Android 9 and older need the storage permission for Downloads: another app takes it there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { openOutside(); return }
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent)
                .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, getString(R.string.browser_download_started, name), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, getString(R.string.browser_no_app), Toast.LENGTH_SHORT).show() }
    }

    private fun saveTabs() {
        prefs.tabs = tabs.map { it.url }.filter { isWebLink(it) }
        prefs.activeTab = active
    }

    override fun onPause() {
        super.onPause()
        tabs.forEach { it.web?.onPause() }
        saveTabs()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.web?.onResume() }
        BrowserSession.unfold()
        // Settings may have changed in the messenger meanwhile.
        linksInside = prefs.openLinksInside
        if (adBlock != prefs.adBlock) { adBlock = prefs.adBlock; if (adBlock) AdBlock.warmUp(this) }
        if (desktop != prefs.desktopMode) setDesktopMode(prefs.desktopMode)
        tabs.forEach { t ->
            t.web?.let {
                it.settings.textZoom = prefs.textZoom
                CookieManager.getInstance().setAcceptThirdPartyCookies(it, prefs.thirdPartyCookies)
            }
        }
    }

    override fun onDestroy() {
        if (BrowserSession.activity?.get() === this) {
            BrowserSession.activity = null
            BrowserSession.unfold()
        }
        val wipe = isFinishing && prefs.wipeOnClose
        tabs.forEach { t -> t.web?.let { if (wipe) it.clearCache(true); (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }
        if (wipe) wipeSiteData(this)
        fileCallback?.onReceiveValue(null)
        super.onDestroy()
    }
}
