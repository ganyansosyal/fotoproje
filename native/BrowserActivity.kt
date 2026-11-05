package com.ganyansosyal.fotoproje

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * Yerel (Flutter'sız) tarayıcı ekranı. Amaç: kaydırma akıcılığı.
 *
 * - WebView Android'in kendi görünüm ağacında durur; üstünde Flutter katmanı yoktur.
 * - Üst çubuk gizlenirken WebView'ın boyutu DEĞİŞMEZ: WebView çubuk yüksekliği kadar
 *   uzun yerleştirilir, çubukla birlikte yalnızca translationY ile kaydırılır
 *   (GPU'da, yeniden yerleşim yok). Sayfanın görünüm alanı hep aynı kalır.
 */
class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout

    private var barH = 0
    private var barShown = true
    private var acc = 0
    private var desktop = false
    private var mobileUa = ""
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    // ---- video yakalama
    private val ui = Handler(Looper.getMainLooper())
    private var hookJs = ""
    private var noAutoJs = ""
    private var docStart = false
    private lateinit var fab: LinearLayout
    private lateinit var fabText: TextView
    private var playerId: String? = null
    private var pDur = 0.0
    private var pLive = false
    private var pH = 0
    private var pSrc = ""
    private var pRef: String? = null
    private val pUrls = ArrayList<String>()
    private val pHints = HashMap<String, String>()
    private var found: List<Found> = emptyList()
    private var probeGen = 0
    private var bannerDone = false
    private val netCands = ArrayList<NetCand>()
    private val probeRun = Runnable { runProbe(null) }

    private class Found(val url: String, val kind: String, val title: String, val subtitle: String)
    private class NetCand(val url: String, val kind: String, var t: Long)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barH = dp(52)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = lightFlags()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }

        // ---- sayfa alanı
        content = FrameLayout(this)
        content.clipChildren = true
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        web = WebView(this)
        content.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = barH }
        )
        // WebView, alanın tam boyunda ama çubuk kadar aşağıdan başlar (alt kısım kırpılır).
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val lp = web.layoutParams as FrameLayout.LayoutParams
            if (h > 0 && lp.height != h) {
                lp.height = h
                lp.topMargin = barH
                web.layoutParams = lp
            }
        }

        // ---- üst çubuk
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(Color.WHITE)
        bar.elevation = dpf(3)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), 0, dp(4), 0)

        urlBox = EditText(this)
        urlBox.hint = "Ara veya adres yaz"
        urlBox.isSingleLine = true
        urlBox.textSize = 15f
        urlBox.setTextColor(0xFF202124.toInt())
        urlBox.setPadding(dp(16), 0, dp(16), 0)
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setSelectAllOnFocus(true)
        urlBox.background = GradientDrawable().apply {
            setColor(0xFFF1F3F4.toInt())
            cornerRadius = dpf(20)
        }
        urlBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(v.text.toString())
                true
            } else {
                false
            }
        }
        row.addView(urlBox, LinearLayout.LayoutParams(0, dp(40), 1f))

        val menuBtn = button("⋮", 22f)
        menuBtn.setOnClickListener { showMenu(it) }
        row.addView(menuBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        )

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        bar.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        )
        content.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        )

        // ---- video indirme düğmesi (video bulununca yeşil)
        fab = LinearLayout(this)
        fab.orientation = LinearLayout.HORIZONTAL
        fab.gravity = Gravity.CENTER_VERTICAL
        fab.setPadding(dp(11), 0, dp(11), 0)
        fab.elevation = dpf(4)
        fab.background = GradientDrawable().apply {
            cornerRadius = dpf(20)
            setColor(FAB_OFF)
        }
        val fabIcon = TextView(this)
        fabIcon.text = "⬇"
        fabIcon.textSize = 16f
        fabIcon.setTextColor(Color.WHITE)
        fab.addView(fabIcon)
        fabText = TextView(this)
        fabText.text = "Videoyu al"
        fabText.textSize = 13f
        fabText.setTextColor(Color.WHITE)
        fabText.setTypeface(fabText.typeface, android.graphics.Typeface.BOLD)
        fabText.setPadding(dp(8), 0, 0, 0)
        fabText.visibility = View.GONE
        fab.addView(fabText)
        fab.setOnClickListener { onFabTap() }
        content.addView(
            fab,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        // ---- alt çubuk: geri / ileri / yenile / uygulamaya dön
        val line = View(this)
        line.setBackgroundColor(0xFFDADCE0.toInt())
        root.addView(
            line,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        )
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setBackgroundColor(Color.WHITE)
        val back = button("‹", 30f)
        back.setOnClickListener { if (web.canGoBack()) web.goBack() }
        val fwd = button("›", 30f)
        fwd.setOnClickListener { if (web.canGoForward()) web.goForward() }
        val reload = button("↻", 22f)
        reload.setOnClickListener { web.reload() }
        val close = button("✕", 20f)
        close.setOnClickListener { finish() }
        for (b in listOf(back, fwd, reload, close)) {
            bottom.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )

        setContentView(root)
        setupWeb()
        setupVideo()

        val u = intent.getStringExtra("url") ?: "https://www.google.com"
        web.loadUrl(u)
    }

    private fun button(t: String, size: Float): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = size
        b.gravity = Gravity.CENTER
        b.setTextColor(0xFF202124.toInt())
        return b
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        mobileUa = ws.userAgentString.replace("; wv", "")
        ws.userAgentString = mobileUa

        web.overScrollMode = View.OVER_SCROLL_ALWAYS
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val s = request?.url?.scheme ?: return false
                val ok = s == "http" || s == "https" || s == "about" || s == "file" ||
                    s == "data" || s == "blob" || s == "javascript"
                return !ok
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!urlBox.hasFocus()) urlBox.setText(shown(url))
                setBar(true)
                clearMedia()
                if (!docStart) injectNow()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!docStart) injectNow()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!urlBox.hasFocus()) urlBox.setText(shown(url))
                updateFab()
            }

            // Engellemez; yalnızca video adreslerini not eder (ayrı iş parçacığında).
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url?.toString()
                if (u != null) noteNet(u)
                return null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?
            ) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                enterFull(view, callback)
            }

            override fun onHideCustomView() {
                exitFull()
            }
        }

        if (Build.VERSION.SDK_INT >= 23) {
            web.setOnScrollChangeListener { _, _, y, _, oldY -> onScrolled(y, oldY) }
        }
    }

    // ---- çubuğu kaydırmaya göre gizle / göster (yalnızca translationY)
    private fun onScrolled(y: Int, oldY: Int) {
        if (urlBox.hasFocus()) return
        if (y <= dp(2)) {
            acc = 0
            setBar(true)
            return
        }
        val d = y - oldY
        if (d == 0) return
        if ((d > 0) != (acc > 0)) acc = 0
        acc += d
        if (acc > dp(40)) {
            setBar(false)
        } else if (acc < -dp(16)) {
            setBar(true)
        }
    }

    private fun setBar(show: Boolean) {
        if (show == barShown) return
        barShown = show
        acc = 0
        val ty = if (show) 0f else -barH.toFloat()
        val ip = DecelerateInterpolator()
        bar.animate().translationY(ty).setDuration(180).setInterpolator(ip).start()
        web.animate().translationY(ty).setDuration(180).setInterpolator(ip).start()
    }

    // ---- adres
    private fun shown(u: String?): String {
        if (u == null) return ""
        return u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/')
    }

    private fun toUrl(t0: String): String {
        val t = t0.trim()
        if (t.isEmpty()) return "about:blank"
        val isUrl = t.startsWith("http://") || t.startsWith("https://") ||
            (t.contains('.') && !t.contains(' '))
        if (isUrl) return if (t.startsWith("http")) t else "https://$t"
        return "https://www.google.com/search?q=" + Uri.encode(t)
    }

    private fun go(text: String) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0)
        web.requestFocus()
        web.loadUrl(toUrl(text))
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (desktop) "✓ Masaüstü sitesi" else "Masaüstü sitesi")
        m.menu.add(0, 2, 1, "Yenile")
        m.menu.add(0, 4, 2, "Bulunan videolar")
        m.menu.add(0, 3, 3, "Uygulamaya dön")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleDesktop()
                2 -> web.reload()
                3 -> finish()
                4 -> showFound()
            }
            true
        }
        m.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        web.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa
        web.reload()
    }

    // ---- video tam ekran
    @Suppress("DEPRECATION")
    private fun enterFull(view: View, cb: WebChromeClient.CustomViewCallback?) {
        customView = view
        customCb = cb
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @Suppress("DEPRECATION")
    private fun exitFull() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        window.decorView.systemUiVisibility = lightFlags()
    }

    @Suppress("DEPRECATION")
    private fun lightFlags(): Int {
        var f = 0
        if (Build.VERSION.SDK_INT >= 23) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        return f
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            exitFull()
        } else if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        ui.removeCallbacks(probeRun)
        content.removeView(web)
        web.destroy()
        super.onDestroy()
    }


    // =====================================================================
    //  Video yakalama. Algılama betiği (hookJs) Dart'tan gelir; inceleme ve
    //  indirme Flutter tarafındaki (Dart) yöneticiye "app/browser" kanalıyla sorulur.
    // =====================================================================
    private inner class Bridge {
        @JavascriptInterface
        fun h(name: String, arg: String) {
            if (name != "player") return
            ui.post { onPlayer(arg) }
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun setupVideo() {
        hookJs = intent.getStringExtra("hook") ?: ""
        noAutoJs = intent.getStringExtra("noAutoplay") ?: ""
        web.addJavascriptInterface(Bridge(), "__fp")
        if (hookJs.isEmpty()) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Tüm çerçevelerde (iframe dahil) sayfa başlamadan çalışır
                WebViewCompat.addDocumentStartJavaScript(web, SHIM + "\n" + hookJs, setOf("*"))
                if (noAutoJs.isNotEmpty()) {
                    WebViewCompat.addDocumentStartJavaScript(web, noAutoJs, setOf("*"))
                }
                docStart = true
            }
        } catch (_: Throwable) {
            docStart = false
        }
    }

    // Belge-başı betik desteklenmiyorsa: yalnızca ana çerçeveye sonradan enjekte
    private fun injectNow() {
        if (hookJs.isEmpty()) return
        web.evaluateJavascript(SHIM + "\n" + hookJs, null)
        if (noAutoJs.isNotEmpty()) web.evaluateJavascript(noAutoJs, null)
    }

    private fun isYoutube(u: String): Boolean {
        val x = u.lowercase()
        return x.contains("youtube.com/watch") || x.contains("youtube.com/shorts") ||
            x.contains("youtu.be/")
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun res(onOk: (Any?) -> Unit, onFail: () -> Unit = {}): MethodChannel.Result =
        object : MethodChannel.Result {
            override fun success(result: Any?) {
                onOk(result)
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                onFail()
            }

            override fun notImplemented() {
                onFail()
            }
        }

    // ---- ağ seviyesinde aday adresler
    private val mediaRe = Regex(
        "\\.(m3u8|mpd|mp4|webm|m4v|mov|mkv|flv)(?:\$|[?#/])", RegexOption.IGNORE_CASE
    )
    private val badRe = Regex(
        "(imasdk|adservice|adsystem|preroll|midroll|vpaid|tracking|beacon|analytics|thumb|sprite|storyboard|preview|[/_.-]ads?[/_.-]|[/_.-]advert|[/_.-]vast[/_.?-])",
        RegexOption.IGNORE_CASE
    )
    private val segRe = Regex(
        "((seg|segment|chunk|frag|fragment)[-_.]?\\d|/init[-_.][^/]*\$|[?&](bytestart|byterange|range|sq|seg|segment|chunk)=)",
        RegexOption.IGNORE_CASE
    )

    private fun noteNet(u: String) {
        if (u.length > 2500) return
        val k = mediaRe.find(u) ?: return
        if (badRe.containsMatchIn(u) || segRe.containsMatchIn(u)) return
        val now = System.currentTimeMillis()
        var isNew = true
        synchronized(netCands) {
            for (c in netCands) {
                if (c.url == u) {
                    c.t = now
                    isNew = false
                    break
                }
            }
            if (isNew) {
                netCands.add(NetCand(u, k.groupValues[1].lowercase(), now))
                if (netCands.size > 40) netCands.removeAt(0)
            }
        }
        if (isNew) ui.post { if (playerId != null) scheduleProbe() }
    }

    // ---- oynatıcı bildirimi (hookJs'ten)
    private fun onPlayer(raw: String) {
        val m = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val id = m.optString("id")
        if (m.optBoolean("stop", false)) {
            if (id != playerId) return
            clearMedia()
            return
        }
        val dur = m.optDouble("d", 0.0)
        if (id != playerId || Math.abs(dur - pDur) > 1) bannerDone = false
        playerId = id
        pDur = dur
        pLive = m.optBoolean("live", false)
        pH = m.optInt("h", 0)
        pSrc = m.optString("src", "")
        val ref = m.optString("ref", "")
        pRef = if (ref.startsWith("http")) ref else null
        pUrls.clear()
        m.optJSONArray("urls")?.let { a ->
            for (i in 0 until a.length()) pUrls.add(a.getString(i))
        }
        pHints.clear()
        m.optJSONObject("hints")?.let { h ->
            val keys = h.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                pHints[k] = h.optString(k)
            }
        }
        scheduleProbe()
    }

    private fun clearMedia() {
        ui.removeCallbacks(probeRun)
        probeGen++
        synchronized(netCands) { netCands.clear() }
        pUrls.clear()
        pHints.clear()
        playerId = null
        pDur = 0.0
        pLive = false
        pH = 0
        pSrc = ""
        pRef = null
        found = emptyList()
        bannerDone = false
        updateFab()
    }

    private fun scheduleProbe() {
        ui.removeCallbacks(probeRun)
        if (playerId == null) {
            found = emptyList()
            updateFab()
            return
        }
        ui.postDelayed(probeRun, 600)
    }

    private fun probeArgs(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val net = synchronized(netCands) { netCands.filter { now - it.t < 90_000 } }
        val urls = LinkedHashSet<String>()
        urls.addAll(pUrls)
        for (c in net) urls.add(c.url)
        val hints = HashMap<String, String>(pHints)
        for (c in net) hints[c.url] = c.kind
        return mapOf(
            "urls" to urls.filter { it.startsWith("http") || it.startsWith("blob:") },
            "hints" to hints,
            "ref" to (pRef ?: web.url ?: ""),
            "dur" to (if (pLive) 0.0 else pDur),
            "src" to pSrc,
            "h" to pH
        )
    }

    private fun runProbe(onDone: ((List<Found>) -> Unit)?) {
        val ch = MainActivity.browser
        if (playerId == null || ch == null) {
            onDone?.invoke(emptyList())
            return
        }
        val gen = ++probeGen
        ch.invokeMethod("probe", probeArgs(), res({ r ->
            if (gen != probeGen || playerId == null) {
                onDone?.invoke(found)
            } else {
                val list = (r as? List<*>)?.mapNotNull { e ->
                    (e as? Map<*, *>)?.let {
                        Found(
                            it["url"].toString(), it["kind"].toString(),
                            it["title"].toString(), it["subtitle"].toString()
                        )
                    }
                } ?: emptyList()
                found = list
                updateFab()
                if (list.isNotEmpty() && !bannerDone) {
                    bannerDone = true
                    toast("Video bulundu")
                }
                onDone?.invoke(list)
            }
        }, { onDone?.invoke(emptyList()) }))
    }

    private fun updateFab() {
        val on = found.isNotEmpty() || isYoutube(web.url ?: "")
        fabText.visibility = if (on) View.VISIBLE else View.GONE
        (fab.background as GradientDrawable).setColor(if (on) FAB_ON else FAB_OFF)
    }

    // ---- indirme
    private fun download(f: Found) {
        val ch = MainActivity.browser
        if (ch == null) {
            toast("Uygulama motoru hazır değil, uygulamayı yeniden açın")
            return
        }
        ch.invokeMethod(
            "download",
            mapOf(
                "url" to f.url,
                "kind" to f.kind,
                "ref" to (pRef ?: web.url ?: ""),
                "title" to (web.title ?: "")
            ),
            res({ r -> toast(if (r is String) r else "İndirme başladı") },
                { toast("İndirme başlatılamadı") })
        )
    }

    private fun downloadYoutube(u: String) {
        val ch = MainActivity.browser
        if (ch == null) {
            toast("Uygulama motoru hazır değil, uygulamayı yeniden açın")
            return
        }
        ch.invokeMethod(
            "youtube", mapOf("url" to u, "title" to (web.title ?: "")),
            res({ r -> toast(if (r is String) r else "İndirme başladı") },
                { toast("İndirme başlatılamadı") })
        )
    }

    private fun onFabTap() {
        val u = web.url ?: ""
        if (isYoutube(u)) {
            downloadYoutube(u)
            return
        }
        if (playerId == null) {
            toast("Video bulunamadı")
            return
        }
        if (found.isNotEmpty()) {
            download(found[0])
            return
        }
        toast("Kontrol ediliyor…")
        runProbe { list -> if (list.isEmpty()) toast("Video bulunamadı") else download(list[0]) }
    }

    private fun showFound() {
        if (found.isEmpty()) {
            if (playerId == null) {
                toast("Video bulunamadı")
                return
            }
            runProbe { list -> if (list.isEmpty()) toast("Video bulunamadı") else showFound() }
            return
        }
        val items = found.map { it.title + "\n" + it.subtitle }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bulunan videolar")
            .setItems(items) { _, i -> download(found[i]) }
            .show()
    }

    companion object {
        private const val FAB_ON = 0xFF1E9E4A.toInt()
        private const val FAB_OFF = 0x99757575.toInt()
        private const val SHIM =
            "(function(){if(window.flutter_inappwebview)return;window.flutter_inappwebview={callHandler:function(n,a){try{__fp.h(String(n),String(a));}catch(e){}return Promise.resolve();}};})();"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
