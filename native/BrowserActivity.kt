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
        m.menu.add(0, 3, 2, "Uygulamaya dön")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleDesktop()
                2 -> web.reload()
                3 -> finish()
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
        content.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
