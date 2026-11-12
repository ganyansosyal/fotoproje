package com.ganyansosyal.fotoproje

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Tarayıcı ekranı (saf Kotlin). Amaç: kaydırma akıcılığı.
 *
 * - WebView Android'in kendi görünüm ağacında durur.
 * - Üst çubuk gizlenirken WebView'ın boyutu DEĞİŞMEZ: WebView çubuk yüksekliği kadar
 *   uzun yerleştirilir, çubukla birlikte yalnızca translationY ile kaydırılır.
 * - Sekmeler, ana sayfa/favoriler, yer imi/geçmiş, reklam engelleme, blob video
 *   indirme, koyu tema, dil, tam ekran, performans ölçer burada yerel olarak çalışır.
 * - Ayarlar, geçmiş, yer imi ve indirme yöneticisi Kotlin'de (Store, Downloads);
 *   bu ekran onlarla Backend üzerinden konuşur.
 */
class BrowserActivity : Activity() {
    // ---- görünümler
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var starBtn: TextView
    private lateinit var reloadBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fab: LinearLayout
    private lateinit var fabView: DlFabView
    private lateinit var fabText: TextView
    private lateinit var dlBadge: TextView
    private lateinit var dlIcon: VecIcon
    private var dlBlink: ValueAnimator? = null
    private val dlPill by lazy { DlPill(this) { openApp(1) } }
    private fun refreshPill() { dlPill.update(fullMode || customView != null) }
    private var themeFg = Color.BLACK
    private val seenFail = HashSet<String>()
    private val dlListener: () -> Unit = {
        runOnUiThread {
            updateDlBadge()
            refreshPill()
            // Adres süresi dolmuş (401/403/410) bir indirme başarısız olduysa bulunan listeyi yenile
            try {
                for (t in ArrayList(Downloads.active)) {
                    if (t.status != DlStatus.FAILED || !seenFail.add(t.id)) continue
                    val e = t.error ?: ""
                    if (e.startsWith("HTTP 403") || e.startsWith("HTTP 410") || e.startsWith("HTTP 401")) {
                        val c = cur
                        if (c != null && c.playerId != null) {
                            val bare = t.url.substringBefore("#fpq=")
                            synchronized(c.netCands) { c.netCands.removeAll { it.url == bare } }
                            scheduleProbe(c)
                            toast(
                                if (L.code == "tr") "Adres süresi dolmuş olabilir, liste yenileniyor. Tekrar dene."
                                else "The link may have expired, refreshing the list. Try again."
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
    private lateinit var line: View
    private lateinit var bottom: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var fwdBtn: TextView
    private lateinit var tabBox: TextView
    private lateinit var perfView: TextView
    private lateinit var exitFullBtn: TextView
    private lateinit var fsBtn: FsButton
    private val fsSeen = HashMap<Int, String>()
    private lateinit var tabsLayer: FrameLayout
    private lateinit var tabsPanel: LinearLayout
    private val tintViews = ArrayList<TextView>()

    // ---- durum
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var tabSeq = 0
    private var barH = 0
    private var barShown = true
    private var acc = 0
    private var contentH = 0
    private var fullMode = false
    private var isRoot = false
    private var mobileUa = ""
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var menuWin: PopupWindow? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var perfOn = false

    // ---- ayarlar (Store'dan gelir)
    private var dark = false

    @Volatile
    private var blockAds = true
    private var engineBase = "https://www.google.com/search?q="
    private var strs: Map<String, String> = emptyMap()
    private var bookmarks: Set<String> = emptySet()
    private var hookJs = Media.HOOK_JS
    private var noAutoJs = Media.NO_AUTOPLAY_JS
    private var docStart = false

    private val saveRun = Runnable { saveTabs() }

    private class Pal(val bg: Int, val fg: Int, val field: Int, val sub: Int, val line: Int)

    private val pal: Pal get() = if (dark) DARK else LIGHT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    private fun t(k: String): String = strs[k] ?: FALLBACK[k] ?: k

    // =====================================================================
    //  Yaşam döngüsü
    // =====================================================================
    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("fp_native", MODE_PRIVATE)
        loadCachedCfg()
        setTheme(
            if (dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        current = this
        isRoot = intent.action == Intent.ACTION_MAIN || intent.getBooleanExtra("root", false)
        barH = dp(52)
        buildUi()
        applyTheme()
        restoreTabs(intent.dataString ?: intent.getStringExtra("url") ?: "")
        loadCfg(0)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        requestHighRefreshRate()
    }

    override fun onPause() {
        cur?.web?.onPause()
        saveTabs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cur?.web?.onResume()
        // Ayarlar/favoriler başka ekranda değişmiş olabilir
        loadCfg(0)
        updateDlBadge()
        val c = cur
        if (c != null && c.isHome && c.web != null && !c.loading) loadHome(c)
        // Arka plandayken süre/boyut alınamamış (bilinmiyor) yayınlar varsa geri dönünce yeniden incele
        if (c != null && c.playerId != null && c.found.any {
                (it.kind == "m3u8" || it.kind == "mpd") && (it.duration <= 0.0 || (it.size <= 0L && it.bw <= 0))
            }) scheduleProbe(c)
    }

    override fun onDestroy() {
        if (current === this) current = null
        Downloads.removeListener(dlListener)
        fabView.setOn(false)
        ui.removeCallbacks(saveRun)
        for (j in ArrayList(blobJobs.values)) finishBlob(j, false)
        for (tb in ArrayList(tabs)) destroyWeb(tb)
        hlsExec.shutdownNow()
        super.onDestroy()
    }

    /** Ekranın en yüksek yenileme hızını (90/120 Hz) ister. */
    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            if (d == null) return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }

    /** Dışarıdan (VIEW intent) gelen adres: yeni sekmede açar. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val u = intent?.dataString ?: intent?.getStringExtra("url") ?: return
        openFromApp(u)
    }

    fun openFromApp(url: String) {
        if (url.isEmpty()) return
        val same = tabs.firstOrNull { it.url == url }
        if (same != null) selectTab(same) else openTab(url, true, null)
    }

    // =====================================================================
    //  Arayüz
    // =====================================================================
    @Suppress("DEPRECATION")
    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }

        // ---- sayfa alanı
        val sn = SwipeNav(this)
        sn.canBack = { cur?.web?.canGoBack() == true }
        sn.canFwd = { cur?.web?.canGoForward() == true }
        sn.onBack = { cur?.web?.let { if (it.canGoBack()) it.goBack() } }
        sn.onFwd = { cur?.web?.let { if (it.canGoForward()) it.goForward() } }
        sn.allowed = { tabsLayer.visibility != View.VISIBLE }
        sn.isDark = { dark }
        content = sn
        content.clipChildren = true
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        // WebView, alanın tam boyunda ama çubuk kadar aşağıdan başlar (alt kısım kırpılır).
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != contentH) {
                contentH = h
                applyWebParams()
            }
        }

        // ---- üst çubuk: adres kutusu, yer imi yıldızı, menü
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.elevation = dpf(3)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), 0, dp(4), 0)

        urlBox = EditText(this)
        urlBox.hint = t("search")
        urlBox.isSingleLine = true
        urlBox.textSize = 15f
        urlBox.setPadding(dp(16), 0, dp(16), 0)
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setSelectAllOnFocus(true)
        urlBox.background = GradientDrawable().apply { cornerRadius = dpf(20) }
        urlBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(v.text.toString())
                true
            } else {
                false
            }
        }
        urlBox.setPadding(dp(16), 0, dp(44), 0) // sağda yenile düğmesine yer
        // Yenile düğmesi adres kutusunun içinde (sağda)
        reloadBtn = button("⟳", 20f)
        reloadBtn.setOnClickListener { onReloadTap() }
        val urlWrap = FrameLayout(this)
        urlWrap.addView(urlBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        urlWrap.addView(
            reloadBtn,
            FrameLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        )
        row.addView(urlWrap, LinearLayout.LayoutParams(0, dp(40), 1f))

        starBtn = button("☆", 22f)
        starBtn.setOnClickListener { toggleBookmark() }
        row.addView(starBtn, LinearLayout.LayoutParams(dp(42), dp(44)))

        val menuBtn = button("⋮", 22f)
        menuBtn.setOnClickListener { showMenu(it) }
        row.addView(menuBtn, LinearLayout.LayoutParams(dp(42), dp(44)))
        bar.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        )

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        progress.visibility = View.GONE
        progress.progressTintList = ColorStateList.valueOf(Theme.LIGHT.accent)
        bar.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        )
        content.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        )

        // ---- video indirme düğmesi: yuvarlak; video yokken gri, bulununca kırmızı + yayılan halka
        fab = LinearLayout(this)
        fab.orientation = LinearLayout.HORIZONTAL
        fab.gravity = Gravity.CENTER
        fabView = DlFabView(this)
        fab.addView(fabView, LinearLayout.LayoutParams(dp(84), dp(84)))
        fabText = TextView(this) // eski kodla uyum için (ekranda görünmez)
        fabText.text = t("getVideo")
        fab.contentDescription = t("getVideo")
        fab.setOnClickListener { onFabTap() }
        content.addView(
            fab,
            FrameLayout.LayoutParams(dp(84), dp(84)).apply {
                // sağ kenarın tam ortası (videonun üstündeki kırmızı düğmeyle karışmasın)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(2)
            }
        )
        setupFabDrag()

        // ---- performans ölçer (menüden açılır)
        perfView = TextView(this)
        perfView.textSize = 11.5f
        perfView.setTextColor(Color.WHITE)
        perfView.setPadding(dp(10), dp(8), dp(10), dp(8))
        perfView.background = GradientDrawable().apply {
            cornerRadius = dpf(10)
            setColor(0xCC000000.toInt())
        }
        perfView.visibility = View.GONE
        perfView.isClickable = false
        content.addView(
            perfView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.TOP
                topMargin = barH + dp(6)
                rightMargin = dp(6)
            }
        )

        // ---- tam ekran modundan çıkış düğmesi
        exitFullBtn = TextView(this)
        exitFullBtn.text = "⤢"
        exitFullBtn.textSize = 18f
        exitFullBtn.gravity = Gravity.CENTER
        exitFullBtn.setTextColor(Color.WHITE)
        exitFullBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x73000000)
        }
        exitFullBtn.visibility = View.GONE
        exitFullBtn.setOnClickListener { setFullMode(false) }
        content.addView(
            exitFullBtn,
            FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.END or Gravity.TOP
                topMargin = dp(10)
                rightMargin = dp(10)
            }
        )

        // ---- yüzen "Tam ekran" hapı (sosyal sitelerde sağdan kayar)
        fsBtn = FsButton(this) { setFullMode(!fullMode) }
        content.addView(
            fsBtn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.TOP
                topMargin = barH + dp(18)
                rightMargin = dp(10)
            }
        )

        // ---- sekme paneli (alttan açılır)
        tabsLayer = FrameLayout(this)
        tabsLayer.setBackgroundColor(0x73000000)
        tabsLayer.visibility = View.GONE
        tabsLayer.setOnClickListener { hideTabs() }
        tabsPanel = LinearLayout(this)
        tabsPanel.orientation = LinearLayout.VERTICAL
        tabsPanel.isClickable = true // dokunuşlar karartmaya geçmesin
        tabsLayer.addView(
            tabsPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val fab = TextView(this)
        fab.text = "+"
        fab.textSize = 30f
        fab.gravity = Gravity.CENTER
        fab.setTextColor(0xFFFFFFFF.toInt())
        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.LIGHT.accent)
        }
        fab.elevation = dpf(6)
        fab.setOnClickListener {
            hideTabs()
            openTab(HOME, true, null)
        }
        tabsLayer.addView(
            fab,
            FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(20)
                bottomMargin = dp(60)
            }
        )
        content.addView(
            tabsLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- alt çubuk: geri / ileri / ana sayfa / sekmeler / indirmeler / ayarlar
        line = View(this)
        root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        backBtn = VecIcon(this, VecIcon.BACK, 26f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            tintViews.add(this)
        }
        backBtn.setOnClickListener { cur?.web?.let { if (it.canGoBack()) it.goBack() } }
        fwdBtn = VecIcon(this, VecIcon.FORWARD, 26f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            tintViews.add(this)
        }
        fwdBtn.setOnClickListener { cur?.web?.let { if (it.canGoForward()) it.goForward() } }
        val homeBtn = VecIcon(this, VecIcon.HOME, 26f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            tintViews.add(this)
        }
        homeBtn.setOnClickListener { goHome() }
        tabBox = TextView(this)
        tabBox.gravity = Gravity.CENTER
        tabBox.textSize = 12f
        tabBox.setTypeface(tabBox.typeface, Typeface.BOLD)
        tabBox.background = GradientDrawable().apply { cornerRadius = dpf(5) }
        tabBox.setOnClickListener { toggleTabs() }
        val tabCell = FrameLayout(this)
        tabCell.addView(
            tabBox,
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        )
        dlIcon = VecIcon(this, VecIcon.DOWNLOAD, 26f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            tintViews.add(this)
        }
        dlIcon.setOnClickListener { openApp(1) }
        dlBadge = TextView(this)
        dlBadge.textSize = 11f
        dlBadge.setTextColor(Color.WHITE)
        dlBadge.setTypeface(dlBadge.typeface, Typeface.BOLD)
        dlBadge.gravity = Gravity.CENTER
        dlBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE53935.toInt())
        }
        dlBadge.visibility = View.GONE
        val closeBtn = FrameLayout(this)
        closeBtn.isClickable = true
        closeBtn.setOnClickListener { openApp(1) }
        closeBtn.addView(dlIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        closeBtn.addView(
            dlBadge,
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER).apply {
                leftMargin = dp(18)
                bottomMargin = dp(16)
            }
        )
        bottom.addView(backBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(fwdBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(homeBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(tabCell, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(closeBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        val settingsBtn = VecIcon(this, VecIcon.SETTINGS, 26f).apply {
            gravity = Gravity.CENTER
            isClickable = true
            tintViews.add(this)
        }
        settingsBtn.setOnClickListener { openApp(3) }
        bottom.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )

        setContentView(root)
        Downloads.addListener(dlListener)
        updateDlBadge()
    }

    /** Alt çubuktaki indirme simgesi: indirme sürerken kırmızı yanıp söner, bitince normale döner. */
    private fun updateDlBadge() {
        if (!::dlIcon.isInitialized) return
        dlBadge.visibility = View.GONE
        val busy = Downloads.active.isNotEmpty()
        if (busy && dlBlink == null) {
            dlIcon.setTextColor(Theme.of(dark).danger)
            dlBlink = ValueAnimator.ofFloat(1f, 0.3f).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { dlIcon.alpha = it.animatedValue as Float }
                start()
            }
        } else if (!busy && dlBlink != null) {
            dlBlink?.cancel()
            dlBlink = null
            dlIcon.alpha = 1f
            dlIcon.setTextColor(themeFg)
        }
    }

    private fun button(txt: String, size: Float): TextView {
        val b = TextView(this)
        b.text = txt
        b.textSize = size
        b.gravity = Gravity.CENTER
        b.isClickable = true
        tintViews.add(b)
        return b
    }

    @Suppress("DEPRECATION")
    private fun sysFlags(): Int {
        if (dark) return 0
        var f = 0
        if (Build.VERSION.SDK_INT >= 23) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        return f
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        if (!::root.isInitialized) return
        val p = pal
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        if (customView == null && !fullMode) window.decorView.systemUiVisibility = sysFlags()
        root.setBackgroundColor(p.bg)
        content.setBackgroundColor(p.bg)
        bar.setBackgroundColor(p.bg)
        bottom.setBackgroundColor(p.bg)
        line.setBackgroundColor(p.line)
        urlBox.setTextColor(p.fg)
        urlBox.setHintTextColor(p.sub)
        (urlBox.background as GradientDrawable).setColor(p.field)
        themeFg = p.fg
        for (b in tintViews) b.setTextColor(p.fg)
        if (dlBlink != null) dlIcon.setTextColor(Theme.of(dark).danger)
        progress.progressTintList = ColorStateList.valueOf(Theme.of(dark).accent)
        tabBox.setTextColor(p.fg)
        (tabBox.background as GradientDrawable).setStroke(dp(2), p.fg)
        updateStar()
    }

    // =====================================================================
    //  Ayarlar (Store ile eşitleme)
    // =====================================================================
    /** Ayarlar, geçmiş, indirme vb. artık doğrudan Kotlin (Backend). */
    private fun dart(
        method: String,
        args: Any?,
        onOk: (Any?) -> Unit = {},
        onFail: () -> Unit = {}
    ) {
        Backend.call(method, args, { r -> if (!isFinishing) onOk(r) }, { if (!isFinishing) onFail() })
    }

    private fun loadCachedCfg() {
        try {
            applyCfgValues(Backend.cfg())
        } catch (_: Throwable) {
        }
    }

    private fun applyCfgValues(m: Map<*, *>) {
        dark = m["dark"] == true
        blockAds = m["blockAds"] != false
        engineBase = m["engine"]?.toString() ?: engineBase
        hookJs = m["hook"]?.toString() ?: hookJs
        noAutoJs = m["noAutoplay"]?.toString() ?: noAutoJs
        val sm = HashMap<String, String>()
        (m["strings"] as? Map<*, *>)?.forEach { (k, v) -> sm[k.toString()] = v.toString() }
        if (sm.isNotEmpty()) strs = sm
        val bm = HashSet<String>()
        (m["bookmarks"] as? List<*>)?.forEach { bm.add(it.toString()) }
        bookmarks = bm
    }

    private fun loadCfg(retry: Int) {
        dart("cfg", null, { r ->
            val m = r as? Map<*, *>
            if (m != null) applyCfg(m)
        }, {
            // Beklenmedik hata: kısa aralıklarla tekrar dene
            if (retry < 14) {
                ui.postDelayed({ if (!isFinishing) loadCfg(retry + 1) }, 700)
            }
        })
    }

    private fun applyCfg(m: Map<*, *>) {
        val newDark = m["dark"] == true
        val newAds = m["blockAds"] != false
        val newEngine = m["engine"]?.toString() ?: engineBase
        val newHook = m["hook"]?.toString() ?: hookJs
        val newNoAuto = m["noAutoplay"]?.toString() ?: noAutoJs
        val sm = HashMap<String, String>()
        (m["strings"] as? Map<*, *>)?.forEach { (k, v) -> sm[k.toString()] = v.toString() }
        val bm = HashSet<String>()
        (m["bookmarks"] as? List<*>)?.forEach { bm.add(it.toString()) }

        val themeChanged = newDark != dark
        val scriptsChanged = newAds != blockAds || newHook != hookJs || newNoAuto != noAutoJs
        dark = newDark
        blockAds = newAds
        engineBase = newEngine
        hookJs = newHook
        noAutoJs = newNoAuto
        if (sm.isNotEmpty()) strs = sm
        bookmarks = bm

        if (!::root.isInitialized) return
        urlBox.hint = t("search")
        fabText.text = t("getVideo")
        if (themeChanged) applyTheme()
        if (scriptsChanged) {
            for (tb in tabs) if (tb.web != null) installScripts(tb)
        }
        updateStar()
    }

    // =====================================================================
    //  Sekmeler
    // =====================================================================
    private fun makeTab(url: String): Tab {
        val tb = Tab(++tabSeq, url)
        tb.isHome = url == HOME
        return tb
    }

    private fun openTab(url: String, select: Boolean, parent: Tab?): Tab {
        val tb = makeTab(url)
        tb.parentId = parent?.id ?: -1
        tabs.add(tb)
        if (select) selectTab(tb) else updateTabCount()
        scheduleSave()
        return tb
    }

    private fun selectTab(tb: Tab) {
        val old = cur
        if (old != null && old !== tb) {
            old.web?.let {
                content.removeView(it)
                it.onPause()
            }
        }
        cur = tb
        tb.lastUsed = System.currentTimeMillis()
        ensureWeb(tb)
        val w = tb.web ?: return
        if (w.parent == null) attachWeb(w)
        w.onResume()
        barShown = true
        acc = 0
        w.translationY = 0f
        bar.translationY = 0f
        trimTabs()
        // Adres çubuğu odaktaysa updateChrome onu güncellemez: sekme değişirken/kapanırken eski adres kalıyordu
        if (urlBox.hasFocus()) {
            try {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(urlBox.windowToken, 0)
            } catch (_: Throwable) {}
            urlBox.clearFocus()
        }
        updateChrome()
        scheduleSave()
    }

    private fun closeTab(tb: Tab) {
        val i = tabs.indexOf(tb)
        if (i < 0) return
        val wasCur = tb === cur
        tabs.removeAt(i)
        ui.removeCallbacks(tb.probeRun ?: Runnable { })
        destroyWeb(tb)
        if (tabs.isEmpty()) {
            val h = makeTab(HOME)
            tabs.add(h)
            cur = null
            selectTab(h)
            return
        }
        if (wasCur) {
            cur = null
            val next = tabs.firstOrNull { it.id == tb.parentId }
                ?: tabs[minOf(i, tabs.size - 1)]
            selectTab(next)
        } else {
            updateTabCount()
        }
        scheduleSave()
    }

    private fun closeAllTabs() {
        for (tb in ArrayList(tabs)) {
            ui.removeCallbacks(tb.probeRun ?: Runnable { })
            destroyWeb(tb)
        }
        tabs.clear()
        cur = null
        val h = makeTab(HOME)
        tabs.add(h)
        selectTab(h)
    }

    /** Bellek için en çok [MAX_LIVE] canlı WebView tutulur; eskiler kapatılıp adresten yeniden açılır. */
    private fun trimTabs() {
        val live = tabs.count { it.web != null }
        if (live <= MAX_LIVE) return
        val cand = tabs.filter { it.web != null && it !== cur && it.url.isNotEmpty() }
            .sortedBy { it.lastUsed }
        var extra = live - MAX_LIVE
        for (tb in cand) {
            if (extra <= 0) break
            destroyWeb(tb)
            extra--
        }
    }

    private fun destroyWeb(tb: Tab) {
        val w = tb.web
        if (w != null) {
            if (!tb.isHome) tb.restore = snapState(w) ?: tb.restore
            (w.parent as? ViewGroup)?.removeView(w)
            try {
                w.stopLoading()
                w.destroy()
            } catch (_: Throwable) {
            }
        }
        tb.web = null
        tb.loaded = false
        tb.scripts.clear()
        tb.loading = false
        clearMedia(tb)
    }

    private fun ensureWeb(tb: Tab) {
        if (tb.web == null) {
            tb.web = createWeb(tb)
            tb.loaded = false
        }
        if (!tb.loaded) {
            tb.loaded = true
            if (tb.isHome || tb.url.isEmpty() || tb.url == HOME) {
                tb.isHome = true
                loadHome(tb)
            } else {
                val st = tb.restore
                tb.restore = null
                val ok = st != null && try { tb.web?.restoreState(st) != null } catch (_: Throwable) { false }
                if (!ok) tb.web?.loadUrl(tb.url)
            }
        }
    }

    private fun snapState(w: WebView): android.os.Bundle? = try {
        val b = android.os.Bundle()
        val l = w.saveState(b)
        if (l != null && l.size > 0) b else null
    } catch (_: Throwable) { null }

    private fun stateFile(i: Int) = java.io.File(java.io.File(filesDir, "ts").apply { mkdirs() }, "$i.bin")

    private fun writeState(i: Int, b: android.os.Bundle) {
        val pc = android.os.Parcel.obtain()
        try {
            pc.writeBundle(b)
            stateFile(i).writeBytes(pc.marshall())
        } finally { pc.recycle() }
    }

    private fun readState(i: Int): android.os.Bundle? {
        val f = stateFile(i)
        if (!f.exists()) return null
        val pc = android.os.Parcel.obtain()
        return try {
            val by = f.readBytes()
            pc.unmarshall(by, 0, by.size)
            pc.setDataPosition(0)
            pc.readBundle(WebView::class.java.classLoader)
        } catch (_: Throwable) { null } finally { pc.recycle() }
    }

    private fun attachWeb(w: WebView) {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (contentH > 0) contentH else ViewGroup.LayoutParams.MATCH_PARENT
        )
        lp.topMargin = if (fullMode) 0 else barH
        content.addView(w, 0, lp)
    }

    private fun applyWebParams() {
        val w = cur?.web ?: return
        val lp = w.layoutParams as? FrameLayout.LayoutParams ?: return
        val top = if (fullMode) 0 else barH
        if (lp.height != contentH || lp.topMargin != top) {
            lp.height = contentH
            lp.topMargin = top
            w.layoutParams = lp
        }
    }

    private fun recoverTab(tb: Tab) {
        val wasCur = tb === cur
        destroyWeb(tb)
        if (wasCur) {
            cur = null
            selectTab(tb)
        }
    }

    // ---- sekme paneli
    private fun toggleTabs() {
        if (tabsShown) hideTabs() else showTabs()
    }

    private var tabsShown = false

    private fun showTabs() {
        menuWin?.dismiss()
        setBar(true) // adres çubuğu gizliyse geri getir
        tabsPanel.setPadding(0, if (fullMode) 0 else barH, 0, 0) // çubuğun altından başla
        buildTabsList()
        tabsShown = true
        tabsLayer.animate().cancel()
        tabsLayer.visibility = View.VISIBLE
        val h = if (tabsLayer.height > 0) tabsLayer.height.toFloat() else dpf(600)
        tabsLayer.alpha = 0f
        tabsLayer.translationY = h * 0.35f
        tabsLayer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
        // kartlar sırayla büyüyerek belirir
        val grid = tabsPanel.getChildAt(1) as? ScrollView
        val col = (grid?.getChildAt(0) as? LinearLayout)
        if (col != null) {
            var n = 0
            for (i in 0 until col.childCount) {
                val row = col.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until row.childCount) {
                    val card = row.getChildAt(j)
                    card.scaleX = 0.88f
                    card.scaleY = 0.88f
                    card.animate().scaleX(1f).scaleY(1f)
                        .setStartDelay(40L * n++)
                        .setDuration(240)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                        .start()
                }
            }
        }
    }

    private fun hideTabs() {
        if (!tabsShown) {
            tabsLayer.visibility = View.GONE
            return
        }
        tabsShown = false
        tabsLayer.animate().cancel()
        tabsLayer.animate()
            .alpha(0f)
            .translationY(tabsLayer.height * 0.25f)
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { if (!tabsShown) tabsLayer.visibility = View.GONE }
            .start()
    }

    private fun initialOf(tb: Tab): String {
        if (tb.isHome) return "⌂"
        val h = try {
            Uri.parse(tb.url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        val s = h.removePrefix("www.")
        return if (s.isEmpty()) "?" else s.substring(0, 1).uppercase()
    }

    private fun tabLabel(tb: Tab): String =
        if (tb.isHome) t("home") else if (tb.title.isNotEmpty()) tb.title else shown(tb.url)

    private fun buildTabsList() {
        val p = pal
        tabsLayer.setBackgroundColor(p.bg)
        tabsPanel.removeAllViews()
        tabsPanel.background = null

        // başlık
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(18), dp(8), dp(8), dp(4))
        val title = TextView(this)
        title.text = t("tabs") + " (" + tabs.size + ")"
        title.textSize = 17f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(p.fg)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabsPanel.addView(head)

        // kart ızgarası (2 sütun)
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(10), dp(4), dp(10), dp(10))
        val list = ArrayList(tabs)
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until 2) {
                val lp = LinearLayout.LayoutParams(0, dp(190), 1f)
                lp.setMargins(dp(6), dp(6), dp(6), dp(6))
                if (i + k < list.size) {
                    row.addView(tabCard(list[i + k], p), lp)
                } else {
                    row.addView(View(this), lp) // tek kalan kart için boşluk
                }
            }
            grid.addView(row)
            i += 2
        }
        val sv = ScrollView(this)
        sv.addView(grid)
        tabsPanel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // alt: hepsini kapat
        val all = TextView(this)
        all.text = t("closeAll")
        all.textSize = 14f
        all.gravity = Gravity.CENTER
        all.setTextColor(Theme.of(dark).danger)
        all.setOnClickListener {
            hideTabs()
            closeAllTabs()
        }
        tabsPanel.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun tabCard(tb: Tab, p: Pal): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            setColor(p.field)
            cornerRadius = dpf(Theme.R)
            setStroke(dp(if (tb === cur) 2 else 1), if (tb === cur) Theme.of(dark).accent else p.line)
        }
        // üst: başlık + ✕
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(12), 0, 0, 0)
        val t1 = TextView(this)
        t1.text = tabLabel(tb)
        t1.textSize = 13f
        t1.setTextColor(p.fg)
        t1.isSingleLine = true
        t1.ellipsize = android.text.TextUtils.TruncateAt.END
        if (tb === cur) t1.setTypeface(t1.typeface, Typeface.BOLD)
        top.addView(t1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = TextView(this)
        x.text = "✕"
        x.textSize = 15f
        x.gravity = Gravity.CENTER
        x.setTextColor(p.fg)
        x.setOnClickListener {
            closeTab(tb)
            if (tabsLayer.visibility == View.VISIBLE) buildTabsList()
        }
        top.addView(x, LinearLayout.LayoutParams(dp(42), dp(40)))
        card.addView(top)
        // orta: önizleme yerine harf / simge
        val ic = TextView(this)
        ic.text = initialOf(tb)
        ic.gravity = Gravity.CENTER
        ic.textSize = 34f
        ic.setTypeface(ic.typeface, Typeface.BOLD)
        ic.setTextColor(p.sub)
        card.addView(ic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // alt: adres
        val t2 = TextView(this)
        t2.text = if (tb.isHome) "" else shown(tb.url)
        t2.textSize = 11f
        t2.setTextColor(p.sub)
        t2.isSingleLine = true
        t2.ellipsize = android.text.TextUtils.TruncateAt.END
        t2.setPadding(dp(12), 0, dp(12), dp(10))
        card.addView(t2)
        card.setOnClickListener {
            selectTab(tb)
            hideTabs()
        }
        return card
    }

    private fun updateTabCount() {
        tabBox.text = tabs.size.toString()
    }

    // ---- sekmeleri kalıcı sakla (uygulamaya dönüp gelince / yeniden açılınca kaybolmasın)
    private fun scheduleSave() {
        ui.removeCallbacks(saveRun)
        ui.postDelayed(saveRun, 800)
    }

    private fun saveTabs() {
        try {
            val arr = JSONArray()
            var ci = 0
            for (tb in tabs) {
                val u = if (tb.isHome) HOME else tb.url
                if (u.isEmpty()) continue
                val o = JSONObject()
                o.put("u", u)
                o.put("t", tb.title)
                o.put("d", tb.desktop)
                if (!tb.isHome) {
                    val st = tb.web?.let { snapState(it) } ?: tb.restore
                    if (st != null) { writeState(arr.length(), st); o.put("s", true) }
                }
                if (tb === cur) ci = arr.length()
                arr.put(o)
            }
            prefs.edit().putString("tabs", arr.toString()).putInt("cur", ci).apply()
        } catch (_: Throwable) {
        }
    }

    private fun restoreTabs(startUrl: String) {
        var idx = 0
        try {
            val raw = prefs.getString("tabs", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tb = makeTab(o.optString("u", HOME))
                    tb.title = o.optString("t", "")
                    tb.desktop = o.optBoolean("d", false)
                    if (o.optBoolean("s", false)) tb.restore = readState(i)
                    tabs.add(tb)
                }
                idx = prefs.getInt("cur", 0)
            }
        } catch (_: Throwable) {
            tabs.clear()
        }
        if (tabs.isEmpty()) tabs.add(makeTab(HOME))
        if (idx !in tabs.indices) idx = 0
        updateTabCount()
        if (startUrl.isNotEmpty()) {
            val same = tabs.firstOrNull { it.url == startUrl }
            if (same != null) selectTab(same) else openTab(startUrl, true, null)
        } else {
            selectTab(tabs[idx])
        }
    }

    // =====================================================================
    //  WebView kurulumu
    // =====================================================================
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWeb(tb: Tab): WebView {
        val w = WebView(this)
        val ws = w.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.setSupportMultipleWindows(true)
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Güvenli tarama kontrolü kapalı: her sayfada "site zararlı mı" sorusunun bekleme süresi gider (Android 8+)
        if (Build.VERSION.SDK_INT >= 26) ws.setSafeBrowsingEnabled(false)
        // Ekran dışı alan da önceden çizilir: kaydırınca resimler/yazılar hazır gelir (Android 6+)
        // offscreenPreRaster kapalı: zayıf telefonlarda kaydırmayı ağırlaştırıyordu
        if (mobileUa.isEmpty()) mobileUa = ws.userAgentString.replace("; wv", "")
        ws.userAgentString = if (tb.desktop) DESKTOP_UA else mobileUa

        w.overScrollMode = View.OVER_SCROLL_ALWAYS
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.setBackgroundColor(pal.bg)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)

        w.addJavascriptInterface(Bridge(tb), "__fp")
        // Video dinleyici: ağ seviyesi (NetSniffer.kt) + JS fetch/XHR kancası (JsHook.kt)
        val sniffer = VideoSniffer { hit ->
            noteNet(tb, hit.url, false, hit.headers, hit.kind)
        }
        JsHook.install(w, sniffer)
        installScripts(tb, w)

        w.webViewClient = object : WebViewClient() {
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
                tb.loading = true
                tb.progress = 0
                val n = normUrl(url, tb)
                if (n == HOME) {
                    tb.isHome = true
                    tb.url = HOME
                    // Geri/ileri ile ana sayfa girdisine dönüldü: içeriği yeniden üret
                    if (!tb.homeLoading) loadHome(tb)
                } else if (n != null) {
                    tb.isHome = false
                    tb.homeGen++
                    tb.url = n
                }
                clearMedia(tb)
                sniffer.reset()
                if (tb === cur) {
                    if (!urlBox.hasFocus()) urlBox.setText(shownUrl(tb))
                    setBar(true)
                    updateStar()
                    updateProgress()
                    updateFab()
                    checkSocial(tb, n)
                }
                if (!docStart) injectNow(tb)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tb.loading = false
                tb.progress = 100
                if (tb.isHome) tb.homeLoading = false
                if (!docStart) injectNow(tb)
                if (perfOn) view?.evaluateJavascript(PERF_START, null)
                val u = view?.url
                if (!tb.isHome && u != null && u.startsWith("http")) {
                    dart("addHistory", mapOf("url" to u, "title" to (view?.title ?: "")))
                }
                if (tb === cur) {
                    updateProgress()
                    updateNav()
                    checkSocial(tb, view?.url ?: tb.url)
                }
                scheduleSave()
                // Sayfa yüklenince video sessizce aranır (oynatıcılar videoyu geç yükler: 2 sn ve 6 sn sonra)
                if (!tb.isHome && u != null && u.startsWith("http")) {
                    for (d in longArrayOf(2000L, 6000L)) {
                        ui.postDelayed({
                            if (tb.web != null && tb.url == normUrl(u, tb) && tb.found.isEmpty() && !tb.isHome) {
                                scanPage(tb, true)
                            }
                        }, d)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                val n = normUrl(url, tb)
                if (n != null) {
                    tb.url = n
                    tb.isHome = n == HOME
                }
                if (tb === cur) {
                    if (!urlBox.hasFocus()) urlBox.setText(shownUrl(tb))
                    updateStar()
                    updateFab()
                    updateNav()
                    checkSocial(tb, n)
                }
                scheduleSave()
            }

            // Reklam alanlarını keser; yalnızca video adreslerini not eder (ayrı iş parçacığı).
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                val u = req.url.toString()
                sniffer.inspect(req)
                if (blockAds && !req.isForMainFrame && AdBlock.isAd(u, req.url.host)) {
                    return AdBlock.empty()
                }
                // Medya öğesinin ilk isteği "Range: bytes=0-" taşır; yalnızca bu uzantısız adresler aday olur
                // (parça/segment istekleri kapalı aralık ya da farklı başlangıç kullanır, onlar aday olmaz)
                val ranged = try {
                    req.requestHeaders?.entries?.any {
                        it.key.equals("Range", true) && it.value.trim().equals("bytes=0-", true)
                    } == true
                } catch (_: Throwable) { false }
                noteNet(tb, u, ranged, req.requestHeaders)
                return null
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                ui.post { recoverTab(tb) }
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tb.progress = newProgress
                if (tb === cur) updateProgress()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                tb.title = title ?: ""
                val u = view?.url
                if (!tb.isHome && u != null && u.startsWith("http")) {
                    dart("historyTitle", mapOf("url" to u, "title" to tb.title))
                }
                if (tabsLayer.visibility == View.VISIBLE) buildTabsList()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val tr = msg.obj as? WebView.WebViewTransport ?: return false
                val nt = makeTab("")
                nt.parentId = tb.id
                nt.loaded = true
                nt.web = createWeb(nt)
                tabs.add(nt)
                tr.webView = nt.web
                msg.sendToTarget()
                selectTab(nt)
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                val x = tabs.firstOrNull { it.web === window } ?: return
                ui.post { closeTab(x) }
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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                val params = fileChooserParams ?: return false
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (_: Exception) {
                    fileCb?.onReceiveValue(null)
                    fileCb = null
                    false
                }
            }
        }

        // Sayfanın indirme olarak sunduğu videolar
        w.setDownloadListener { url, _, _, mime, _ ->
            val m = (mime ?: "").lowercase()
            val k = kindOf(url)
            if (m.startsWith("video/") || m.contains("mpegurl") || m.contains("dash+xml") || k != null) {
                startDownload(tb, url, k ?: "")
            }
        }

        w.setOnTouchListener { _, e ->
            if (tb === cur) onWebTouch(e.actionMasked)
            false
        }
        if (Build.VERSION.SDK_INT >= 23) {
            w.setOnScrollChangeListener { _, _, y, _, oldY ->
                if (tb === cur) onScrolled(y, oldY)
            }
        }
        return w
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val r = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCb?.onReceiveValue(r)
            fileCb = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun isHomeUrl(u: String): Boolean = u == HOME || u.startsWith(HOME_BASE)

    /** Sayfa adresini sadeleştirir: ana sayfa -> HOME, boş sayfa -> null (eskisi korunur). */
    private fun normUrl(u: String?, tb: Tab): String? {
        if (u == null) return null
        if (isHomeUrl(u)) return HOME
        if (u.startsWith("about:blank")) return if (tb.homeLoading) HOME else null
        return u
    }

    // ---- belge başı betikleri
    private fun installScripts(tb: Tab, w0: WebView? = null) {
        val w = w0 ?: tb.web ?: return
        for (h in tb.scripts) {
            try {
                h.remove()
            } catch (_: Throwable) {
            }
        }
        tb.scripts.clear()
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                docStart = false
                return
            }
            val rules = setOf("*")
            // Tüm çerçevelerde (iframe dahil) sayfa başlamadan çalışır
            tb.scripts.add(
                WebViewCompat.addDocumentStartJavaScript(w, SHIM + "\n" + hookJs, rules)
            )
            if (noAutoJs.isNotEmpty()) {
                tb.scripts.add(WebViewCompat.addDocumentStartJavaScript(w, noAutoJs, rules))
            }
            if (blockAds) {
                tb.scripts.add(WebViewCompat.addDocumentStartJavaScript(w, AdBlock.HIDE_JS, rules))
            }
            docStart = true
        } catch (_: Throwable) {
            docStart = false
        }
    }

    // Belge-başı betik desteklenmiyorsa: yalnızca ana çerçeveye sonradan enjekte
    private fun injectNow(tb: Tab) {
        val w = tb.web ?: return
        w.evaluateJavascript(SHIM + "\n" + hookJs, null)
        JsHook.injectFallback(w)
        if (noAutoJs.isNotEmpty()) w.evaluateJavascript(noAutoJs, null)
        if (blockAds) w.evaluateJavascript(AdBlock.HIDE_JS, null)
    }

    // ---- sayfadan gelen çağrılar (ana sayfa düğmeleri, oynatıcı, performans, blob)
    private inner class Bridge(val tb: Tab) {
        @JavascriptInterface
        fun h(name: String, args: String) {
            ui.post { onJs(tb, name, args) }
        }

        @JavascriptInterface
        fun blob(token: String, kind: String, a: String, b: String) {
            ui.post { onBlob(token, kind, a, b) }
        }
    }

    private fun onJs(tb: Tab, name: String, args: String) {
        val arr = try {
            JSONArray(args)
        } catch (_: Exception) {
            return
        }
        when (name) {
            "player" -> onPlayer(tb, arr.optString(0))
            // JS hook'u yeni video adayı buldu (oynatıcı beklemeden)
            "cand" -> {
                val cu = arr.optString(0)
                if (cu.startsWith("http")) noteNet(tb, cu, false, null, arr.optString(1))
            }
            "perf" -> if (tb === cur) onPerf(arr.optInt(0), arr.optInt(1), arr.optInt(2))
            // Ana sayfa düğmeleri yalnızca ana sayfada geçerlidir
            "addFav" -> if (tb.isHome) showAddFav()
            "favMenu" -> if (tb.isHome) confirmRemoveFav(arr.optString(0), arr.optString(1))
        }
    }

    // =====================================================================
    //  Ana sayfa + favoriler
    // =====================================================================
    private fun loadHome(tb: Tab) {
        if (tb.web == null) return
        tb.isHome = true
        tb.homeLoading = true
        tb.url = HOME
        val g = ++tb.homeGen
        dart("home", null, { r ->
            if (g == tb.homeGen) {
                val html = (r as? String)?.takeIf { it.isNotEmpty() } ?: fallbackHome()
                tb.web?.loadDataWithBaseURL(HOME_BASE, html, "text/html", "utf-8", HOME_BASE)
            }
        }, {
            if (g == tb.homeGen) {
                tb.web?.loadDataWithBaseURL(HOME_BASE, fallbackHome(), "text/html", "utf-8", HOME_BASE)
            }
        })
    }

    private fun fallbackHome(): String {
        val p = pal
        fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)
        return "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'></head>" +
            "<body style='margin:0;padding:48px 16px;background:" + hex(p.bg) + ";color:" + hex(p.fg) +
            ";font-family:sans-serif'><form onsubmit=\"var v=document.getElementById('q').value.trim();" +
            "if(v){location.href='" + engineBase + "'+encodeURIComponent(v)}return false\">" +
            "<input id=q style='width:100%;box-sizing:border-box;padding:12px 18px;border-radius:24px;" +
            "border:0;background:" + hex(p.field) + ";color:" + hex(p.fg) + ";font-size:15px' placeholder='" +
            t("search") + "'></form></body></html>"
    }

    private fun reloadHomeTabs() {
        for (tb in tabs) if (tb.isHome && tb.web != null) loadHome(tb)
    }

    private fun goHome() {
        val tb = cur ?: return
        tb.homeGen++
        if (tb.isHome) {
            loadHome(tb)
            return
        }
        loadHome(tb)
    }

    private fun showAddFav() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(8), dp(22), 0)
        val n = EditText(this)
        n.hint = t("nameLbl")
        n.isSingleLine = true
        val u = EditText(this)
        u.hint = t("addrLbl")
        u.isSingleLine = true
        u.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        box.addView(n)
        box.addView(u)
        AlertDialog.Builder(this, dlgTheme())
            .setTitle(t("addFav"))
            .setView(box)
            .setPositiveButton(t("add")) { _, _ ->
                var link = u.text.toString().trim()
                if (link.isNotEmpty()) {
                    if (!link.startsWith("http")) link = "https://$link"
                    dart(
                        "addFavorite",
                        mapOf("name" to n.text.toString(), "url" to link),
                        { reloadHomeTabs() }
                    )
                }
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun confirmRemoveFav(url: String, name: String) {
        AlertDialog.Builder(this, dlgTheme())
            .setTitle(t("removeFavQ"))
            .setMessage(name)
            .setPositiveButton(t("removeFav")) { _, _ ->
                dart("removeFavorite", mapOf("url" to url), { reloadHomeTabs() })
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // =====================================================================
    //  Adres çubuğu, yer imi, çubuk gizleme
    // =====================================================================
    private fun shown(u: String?): String {
        if (u == null) return ""
        return u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/')
    }

    private fun shownUrl(tb: Tab): String = if (tb.isHome) "" else shown(tb.url)

    private fun toUrl(t0: String): String {
        val s = t0.trim()
        if (s.isEmpty()) return "about:blank"
        val isUrl = s.startsWith("http://") || s.startsWith("https://") ||
            (s.contains('.') && !s.contains(' '))
        if (isUrl) return if (s.startsWith("http")) s else "https://$s"
        return engineBase + Uri.encode(s)
    }

    private fun go(text: String) {
        if (text.trim().isEmpty()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0)
        urlBox.clearFocus()
        val tb = cur ?: return
        tb.homeGen++
        tb.web?.requestFocus()
        tb.web?.loadUrl(toUrl(text))
    }

    private fun loadInCurrent(url: String) {
        val tb = cur ?: return
        tb.homeGen++
        tb.web?.loadUrl(url)
    }

    /** Sayfanın kaynağındaki video adreslerini bulup inceler; bulunursa indirme seçeneklerini açar. */
    private fun scanPage(tb: Tab, silent: Boolean = false) {
        val w = tb.web ?: return
        if (isYtTab(tb)) return // YouTube'da tarama yok
        if (silent && isGoogleTab(tb)) return // Google'da kendiliğinden tarama yok (düğmeye basınca yine çalışır)
        w.evaluateJavascript(Media.SCAN_JS) { raw ->
            val urls = ArrayList<String>()
            val netHints = HashMap<String, String>()
            try {
                val str = org.json.JSONTokener(raw ?: "null").nextValue() as? String
                if (str != null) {
                    val arr = JSONArray(str)
                    for (i in 0 until arr.length()) urls.add(arr.getString(i))
                }
            } catch (_: Throwable) {
            }
            synchronized(tb.netCands) {
                val now = System.currentTimeMillis()
                tb.netCands.filter { now - it.t < 120_000 }.forEach { urls.add(it.url); netHints[it.url] = it.kind }
            }
            val candidates = urls.distinct().filter { !isYtUrl(it) }.take(100)
            if ((!silent && !tb.autoShow) || tb.found.isNotEmpty()) return@evaluateJavascript
            if (silent && candidates.isEmpty()) return@evaluateJavascript
            if (candidates.isEmpty()) {
                tb.autoShow = false
                if (tb === cur) toast(t("noVideo"))
                return@evaluateJavascript
            }
            val ref = tb.web?.url ?: tb.url
            Thread {
                val list = try {
                    Probe.probeAll(candidates, netHints, ref).map {
                        FoundItem(it.url, it.kind, it.title, it.subtitle, it.height, it.duration, it.size, it.bw)
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
                ui.post {
                    if (silent && !tb.autoShow) {
                        if (list.isNotEmpty() && tb.found.isEmpty()) {
                            tb.found = list
                            prefetchThumb(tb, list)
                            if (tb === cur) { updateFab(); toast(t("videoFound")) }
                        }
                    } else if (list.isNotEmpty() && tb.autoShow && tb.found.isEmpty()) {
                        tb.found = list
                        tb.autoShow = false
                        if (tb === cur) { updateFab(); showFound() }
                    } else if (list.isEmpty() && tb.autoShow && tb.found.isEmpty()) {
                        tb.autoShow = false
                        if (tb === cur) toast(t("noVideo"))
                    }
                }
            }.start()
        }
    }

    private fun updateStar() {
        if (!::starBtn.isInitialized) return
        val tb = cur
        val on = tb != null && !tb.isHome && bookmarks.contains(tb.url)
        starBtn.text = if (on) "★" else "☆"
        starBtn.setTextColor(if (on) Theme.of(dark).star else pal.fg)
    }

    private fun updateNav() {
        val w = cur?.web
        backBtn.alpha = if (w != null && w.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (w != null && w.canGoForward()) 1f else 0.35f
    }

    private var progAnim: android.animation.ObjectAnimator? = null
    private var progFinishing = false

    private fun animateProgress(to: Int) {
        progAnim?.cancel()
        progAnim = android.animation.ObjectAnimator.ofInt(progress, "progress", progress.progress, to).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun updateProgress() {
        val tb = cur ?: return
        val loading = tb.loading && tb.progress < 100
        if (loading) {
            // Yeni yükleme: çubuk gizliyse ya da sönüyorsa baştan başlar
            val fresh = progress.visibility != View.VISIBLE || progFinishing || progress.alpha < 0.99f
            progFinishing = false
            progress.animate().cancel()
            if (fresh) { progAnim?.cancel(); progress.progress = 0 }
            progress.alpha = 1f
            progress.visibility = View.VISIBLE
            animateProgress(maxOf(tb.progress, 6) * 10)
        } else if (progress.visibility == View.VISIBLE && !progFinishing) {
            // Bitti: sonuna kadar dolar, sonra yavaşça söner
            progFinishing = true
            animateProgress(1000)
            progress.animate().alpha(0f).setStartDelay(300).setDuration(300).withEndAction {
                if (progFinishing) {
                    progFinishing = false
                    progress.visibility = View.GONE
                    progress.alpha = 1f
                    progress.progress = 0
                }
            }.start()
        }
        if (::reloadBtn.isInitialized) {
            // yüklenirken ✕ (durdur), bitince ⟳ (yenile)
            reloadBtn.text = if (tb.loading && tb.progress < 100) "✕" else "⟳"
        }
    }

    private fun onReloadTap() {
        val tb = cur ?: return
        if (tb.isHome) {
            loadHome(tb)
            return
        }
        val w = tb.web ?: return
        if (tb.loading && tb.progress < 100) w.stopLoading() else w.reload()
    }

    private fun updateChrome() {
        val tb = cur ?: return
        if (!urlBox.hasFocus()) urlBox.setText(shownUrl(tb))
        updateStar()
        updateTabCount()
        updateFab()
        updateNav()
        updateProgress()
        updateFsBtn(tb, tb.url)
    }

    private fun toggleBookmark() {
        val tb = cur ?: return
        if (tb.isHome || !tb.url.startsWith("http")) return
        val wasOn = bookmarks.contains(tb.url)
        dart("toggleBookmark", mapOf("url" to tb.url), { r ->
            (r as? List<*>)?.let { l ->
                val s = HashSet<String>()
                for (e in l) s.add(e.toString())
                bookmarks = s
            }
            updateStar()
            toast(t(if (wasOn) "bookmarkRemoved" else "bookmarkAdded"))
            // Yıldız: ana sayfadaki "Sık kullandığın siteler" listesine de ekler / kaldırır
            if (wasOn) dart("removeFavorite", mapOf("url" to tb.url), { reloadHomeTabs() })
            else dart("addFavorite", mapOf("name" to "", "url" to tb.url), { reloadHomeTabs() })
        }, { toast(t("failed")) })
    }

    // ---- çubuğu kaydırmaya göre gizle / göster (yalnızca translationY)
    private fun onScrolled(y: Int, oldY: Int) {
        if (urlBox.hasFocus() || fullMode) return
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
            setBarSoft(false)
        } else if (acc < -dp(16)) {
            setBarSoft(true)
        }
    }

    // Parmak ekrandayken WebView'ı kaydırmak dokunuş koordinatını kaydırıp titreme yapar:
    // parmak varken yalnızca istek not edilir, parmak kalkınca uygulanır.
    private var touching = false
    private var pendingBar: Boolean? = null

    private fun setBarSoft(show: Boolean) {
        if (touching) pendingBar = show else setBar(show)
    }

    private fun onWebTouch(action: Int) {
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> { touching = true; pendingBar = null }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                touching = false
                pendingBar?.let { setBar(it) }
                pendingBar = null
            }
        }
    }

    private fun setBar(show: Boolean) {
        if (fullMode || show == barShown) return
        barShown = show
        acc = 0
        val ty = if (show) 0f else -barH.toFloat()
        val ip = DecelerateInterpolator()
        bar.animate().translationY(ty).setDuration(180).setInterpolator(ip).start()
        cur?.web?.animate()?.translationY(ty)?.setDuration(180)?.setInterpolator(ip)?.start()
    }

    // =====================================================================
    //  Menü
    // =====================================================================
    private fun dlgTheme(): Int =
        if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun showMenu(anchor: View) {
        val tb = cur ?: return
        val p = pal
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(6), 0, dp(6))
        val sv = ScrollView(this)
        sv.addView(col)
        val w = dp(250)
        val pw = PopupWindow(sv, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dpf(8)
        pw.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dpf(14)
            setColor(p.bg)
        })
        pw.isOutsideTouchable = true
        menuWin = pw

        val sel = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, sel, true)

        fun item(label: String, checked: Boolean? = null, action: () -> Unit) {
            val r = TextView(this)
            r.text = (if (checked == true) "✓  " else "") + label
            r.textSize = 15f
            r.setTextColor(p.fg)
            r.setPadding(dp(18), 0, dp(14), 0)
            r.gravity = Gravity.CENTER_VERTICAL
            if (sel.resourceId != 0) r.setBackgroundResource(sel.resourceId)
            r.setOnClickListener {
                pw.dismiss()
                action()
            }
            col.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }

        fun divider() {
            val d = View(this)
            d.setBackgroundColor(p.line)
            col.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }

        val marked = !tb.isHome && bookmarks.contains(tb.url)
        item(t("newTab")) { openTab(HOME, true, null) }
        item(t("home")) { goHome() }
        item(t("reload")) {
            if (tb.isHome) loadHome(tb) else tb.web?.reload()
        }
        divider()
        item(t(if (marked) "removeBookmark" else "addBookmark")) { toggleBookmark() }
        item(t("bookmarks")) { showBookmarks() }
        item(t("history")) { showHistory() }
        divider()
        item(t("share")) { if (!tb.isHome) shareText(tb.url) }
        item(t("copyLink")) { if (!tb.isHome) copyText(tb.url) }
        item(t("videosFound")) { showFound() }
        divider()
        item(t("desktop"), tb.desktop) { toggleDesktop() }
        item(t("fullScreen")) { setFullMode(true) }
        item(t("perfMeter"), perfOn) { togglePerf() }
        divider()
        item(t("downloads")) { openApp(1) }
        item(t("shareApp")) { shareText(t("shareAppText")) }

        col.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = resources.displayMetrics.heightPixels * 80 / 100
        pw.height = minOf(col.measuredHeight, maxH)
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun toggleDesktop() {
        val tb = cur ?: return
        tb.desktop = !tb.desktop
        val w = tb.web ?: return
        w.settings.userAgentString = if (tb.desktop) DESKTOP_UA else mobileUa
        if (tb.isHome) loadHome(tb) else w.reload()
        scheduleSave()
    }

    private fun shareText(text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(i, null))
        } catch (_: Exception) {
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        toast(t("linkCopied"))
    }

    /** İndirmeler (1) / Ayarlar (3) ekranını açar. */
    private fun openApp(section: Int) {
        saveTabs()
        startActivity(
            Intent(this, if (section == 1) DownloadsActivity::class.java else SettingsActivity::class.java)
        )
    }

    /** Ekranı kapatır. */
    private fun leave() {
        saveTabs()
        finish()
    }

    // ---- liste pencereleri (yer imleri / geçmiş)
    private class Row(
        val title: String,
        val sub: String,
        val onTap: () -> Unit,
        val onLong: (() -> Unit)?
    )

    private inner class RowAdapter(val rows: List<Row>) : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = pal
            val r = rows[position]
            val box = LinearLayout(this@BrowserActivity)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(20), dp(10), dp(20), dp(10))
            val t1 = TextView(this@BrowserActivity)
            t1.text = r.title
            t1.textSize = 15f
            t1.setTextColor(p.fg)
            t1.isSingleLine = true
            t1.ellipsize = android.text.TextUtils.TruncateAt.END
            box.addView(t1)
            if (r.sub.isNotEmpty()) {
                val t2 = TextView(this@BrowserActivity)
                t2.text = r.sub
                t2.textSize = 12f
                t2.setTextColor(p.sub)
                t2.isSingleLine = true
                t2.ellipsize = android.text.TextUtils.TruncateAt.END
                box.addView(t2)
            }
            return box
        }
    }

    private fun showList(
        title: String,
        rows: List<Row>,
        empty: String,
        neutral: String?,
        onNeutral: (() -> Unit)?
    ) {
        val b = AlertDialog.Builder(this, dlgTheme()).setTitle(title)
        if (rows.isEmpty()) {
            b.setMessage(empty)
        } else {
            b.setAdapter(RowAdapter(rows)) { _, i -> rows[i].onTap() }
        }
        b.setNegativeButton(t("ok"), null)
        if (neutral != null) b.setNeutralButton(neutral) { _, _ -> onNeutral?.invoke() }
        val d = b.create()
        d.show()
        d.listView?.setOnItemLongClickListener { _, _, pos, _ ->
            val f = rows.getOrNull(pos)?.onLong
            if (f != null) {
                d.dismiss()
                f()
                true
            } else {
                false
            }
        }
    }

    private fun showBookmarks() {
        dart("bookmarks", null, { r ->
            val list = (r as? List<*>)?.map { it.toString() } ?: emptyList()
            bookmarks = list.toSet()
            updateStar()
            val rows = list.map { u ->
                Row(shown(u), "", { loadInCurrent(u) }, {
                    // basılı tut: yer imini sil, listeyi yenile
                    dart("toggleBookmark", mapOf("url" to u), { _ -> showBookmarks() })
                })
            }
            showList(t("bookmarks"), rows, t("noBookmarks"), null, null)
        }, { toast(t("noBookmarks")) })
    }

    private fun showHistory() {
        dart("history", null, { r ->
            val list = (r as? List<*>) ?: emptyList<Any>()
            val rows = ArrayList<Row>()
            for (e in list) {
                val m = e as? Map<*, *> ?: continue
                val u = m["u"]?.toString() ?: continue
                val ti = m["t"]?.toString() ?: ""
                rows.add(Row(if (ti.isNotEmpty()) ti else shown(u), shown(u), { loadInCurrent(u) }, null))
            }
            showList(t("history"), rows, t("noHistory"), t("clear")) {
                dart("clearHistory", null, { for (tb in tabs) tb.web?.clearHistory(); toast(t("cleared")) })
            }
        }, { toast(t("noHistory")) })
    }

    // =====================================================================
    //  Tam ekran (video) + tarayıcı tam ekran modu
    // =====================================================================
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
        window.decorView.systemUiVisibility = IMMERSIVE
        refreshPill()
    }

    @Suppress("DEPRECATION")
    private fun exitFull() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        window.decorView.systemUiVisibility = if (fullMode) IMMERSIVE else sysFlags()
        refreshPill()
    }

    /** Tarayıcı tam ekran: üst/alt çubuklar ve sistem çubukları gizlenir, sayfa tüm ekranı kaplar. */
    @Suppress("DEPRECATION")
    private fun setFullMode(on: Boolean) {
        fullMode = on
        val vis = if (on) View.GONE else View.VISIBLE
        bar.visibility = vis
        bottom.visibility = vis
        line.visibility = vis
        updateFab()
        exitFullBtn.visibility = View.GONE
        fsBtn.setFull(on)
        (fsBtn.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = (if (on) 0 else barH) + dp(18)
            fsBtn.layoutParams = it
        }
        if (on) { fsBtn.showQuiet(); fsBtn.flash() }
        else cur?.let { updateFsBtn(it, it.url) } ?: fsBtn.hideNow()
        barShown = true
        acc = 0
        bar.translationY = 0f
        cur?.web?.translationY = 0f
        hideTabs()
        window.decorView.systemUiVisibility = if (on) IMMERSIVE else sysFlags()
        applyWebParams()
        root.requestApplyInsets()
        refreshPill()
        if (perfOn) {
            (perfView.layoutParams as FrameLayout.LayoutParams).topMargin =
                (if (on) 0 else barH) + dp(6)
            perfView.requestLayout()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            customView != null -> exitFull()
            menuWin?.isShowing == true -> menuWin?.dismiss()
            tabsLayer.visibility == View.VISIBLE -> hideTabs()
            fullMode -> setFullMode(false)
            else -> {
                val tb = cur
                val w = tb?.web
                if (w != null && w.canGoBack()) {
                    w.goBack()
                } else if (tb != null && tabs.size > 1) {
                    closeTab(tb)
                } else if (isRoot) {
                    saveTabs()
                    finishAffinity()
                } else {
                    saveTabs()
                    super.onBackPressed()
                }
            }
        }
    }

    // =====================================================================
    //  Performans ölçer (sayfanın kare hızı)
    // =====================================================================
    private fun togglePerf() {
        perfOn = !perfOn
        perfView.visibility = if (perfOn) View.VISIBLE else View.GONE
        if (perfOn) {
            perfView.text = t("perfPage") + "\n…"
            for (tb in tabs) tb.web?.evaluateJavascript(PERF_START, null)
        } else {
            for (tb in tabs) tb.web?.evaluateJavascript(PERF_STOP, null)
        }
    }

    private fun onPerf(fps: Int, worst: Int, jank: Int) {
        if (!perfOn) return
        val c = when {
            fps >= 50 -> "●"
            fps >= 30 -> "◐"
            else -> "○"
        }
        perfView.text = t("perfPage") + "\n" + c + " " + fps + " fps\n" +
            t("perfWorst") + " " + worst + " ms · " + t("perfJank") + " " + jank
    }

    // =====================================================================
    //  Video yakalama. Algılama betiği (hookJs) Dart'tan gelir; inceleme ve
    //  indirme Flutter tarafındaki (Dart) yöneticiye "app/browser" kanalıyla sorulur.
    // =====================================================================
    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun checkSocial(tb: Tab, url: String?) {
        updateFsBtn(tb, url)
    }

    /** Tam ekran hapının gösterileceği site grubu (yoksa null). */
    private fun fsKey(url: String?): String? {
        val h = try { java.net.URI(url ?: return null).host?.lowercase() ?: return null } catch (_: Throwable) { return null }
        fun d(x: String) = h == x || h.endsWith(".$x")
        return when {
            d("instagram.com") -> "instagram"
            d("x.com") || d("twitter.com") -> "x"
            d("facebook.com") || d("fb.com") || d("fb.watch") -> "facebook"
            d("youtube.com") || d("youtu.be") -> "youtube"
            d("tiktok.com") -> "tiktok"
            else -> null
        }
    }

    private fun updateFsBtn(tb: Tab, url: String?) {
        if (tb !== cur) return
        if (customView != null) { fsBtn.hideNow(); return }
        if (fullMode) { fsBtn.showQuiet(); return }
        val k = if (tb.isHome) null else fsKey(url ?: tb.url)
        if (k == null) { fsSeen.remove(tb.id); fsBtn.hideNow(); return }
        fsBtn.setFull(false)
        if (fsSeen[tb.id] != k) {
            fsSeen[tb.id] = k
            fsBtn.hideNow()
            fsSlideWhenFree(0)
        } else fsBtn.showQuiet()
    }

    private fun fsSlideWhenFree(tries: Int) {
        val tb = cur ?: return
        if (fullMode || customView != null || tb.isHome || fsKey(tb.url) == null) return
        fsBtn.slideIn()
    }

    /** Sekme YouTube'da mı? (m.youtube.com, youtu.be, youtube-nocookie.com ...) */
    private fun isYtTab(tb: Tab?): Boolean {
        // DİKKAT: noteNet arka plan iş parçacığından çağrılır; WebView.getUrl() yalnızca ana iş parçacığında olur.
        val u = tb?.url ?: return false
        val h = try { Uri.parse(u).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com") ||
            h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com")
    }

    /** Google arama sayfaları (google.com, google.com.tr, www.google.* ...): otomatik video algılama yapılmaz. */
    private fun isGoogleTab(tb: Tab?): Boolean {
        val u = tb?.url ?: return false
        val h = try { Uri.parse(u).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return Regex("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$").matches(h)
    }

    /** Adres YouTube'a mı ait? (gömülü oynatıcılar, googlevideo akışları, ytimg önizlemeleri dahil) */
    private fun isYtUrl(u: String?): Boolean {
        if (u.isNullOrEmpty()) return false
        val h = try { Uri.parse(u).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com") ||
            h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com") ||
            h.endsWith(".googlevideo.com") || h == "googlevideo.com" ||
            h.endsWith(".ytimg.com") || h == "ytimg.com"
    }

    private fun kindOf(u: String): String? {
        if (u.startsWith("blob:")) return "blob"
        val l = u.lowercase()
        if (l.contains(".m3u8")) return "m3u8"
        if (l.contains(".mpd")) return "mpd"
        if (l.contains("mime_type=video_mp4")) return "mp4"
        if (l.contains("mime_type=video_webm")) return "webm"
        val path = try {
            (Uri.parse(u).path ?: l).lowercase()
        } catch (_: Exception) {
            l
        }
        return EXT_RE.find(path)?.groupValues?.get(1)
    }

    // ---- ağ seviyesinde aday adresler
    private val NOT_MEDIA_RE = Regex(
        "\\.(js|mjs|css|png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|json|html?|txt|xml|map|vtt|srt|wasm)(?:$|[?#])",
        RegexOption.IGNORE_CASE
    )

    private fun noteNet(
        tb: Tab, u0: String, ranged: Boolean = false,
        requestHeaders: Map<String, String>? = null,
        forceKind: String = ""
    ) {
        if (u0.length > 2500) return
        if (isYtTab(tb)) return // YouTube'da ağ adayı toplanmaz
        if (isGoogleTab(tb)) return // Google'da gezerken aday toplanmaz
        if (isYtUrl(u0)) return // YouTube adresleri (gömülü oynatıcı, googlevideo) aday olmaz
        Net.rememberRequestHeaders(u0, requestHeaders)
        // Instagram/Facebook parça isteklerindeki bytestart/byteend atılır: tam dosya adresi aday olur
        val u = Media.unrange(u0)
        if (u != u0) Net.rememberRequestHeaders(u, requestHeaders)
        // Parça filtresi tüm adrese değil yalnızca dosya adına ve sorguya bakar (rastgele token/klasör adı gerçek videoyu elemesin)
        val segPath = u.substringBefore('?').substringBefore('#')
        val segQuery = if (u.contains('?')) "?" + u.substringAfter('?').substringBefore('#') else ""
        if (SEG_RE.containsMatchIn("/" + segPath.substringAfterLast('/') + segQuery)) return
        // Reklam ağı / reklam yolu içeren adresler aday olmaz
        if (AD_URL_RE.containsMatchIn(segPath)) return
        val match = MEDIA_RE.find(u)
        val lower = u.lowercase()
        // Uzantısı olmayan CDN adresleri: URL'nin bilinen medya parametrelerinden aday çıkar.
        val inferred = when {
            lower.contains("mime_type=video_mp4") -> "mp4"
            lower.contains("mime_type=video_webm") -> "webm"
            lower.contains("mime_type=audio") || lower.contains("mimetype=audio") -> "m4a"
            lower.contains(".m3u8") || lower.contains("format=hls") -> "m3u8"
            lower.contains(".mpd") || lower.contains("format=dash") -> "mpd"
            lower.contains("videoplayback") || lower.contains("/video/") || lower.contains("/videos/") -> "mp4"
            else -> ""
        }
        val kind = match?.groupValues?.get(1)?.ifEmpty { match.groupValues.getOrElse(2) { "" } }?.lowercase()
            ?.ifEmpty { inferred } ?: inferred
        var kind2 = if (kind == "m3u") "sniff" else kind // .m3u: türünü Probe içerikten anlar
        if (kind2.isEmpty() && ranged && u.startsWith("http") && !NOT_MEDIA_RE.containsMatchIn(u)) {
            kind2 = "sniff" // türü bilinmiyor: Probe ilk baytlara / Content-Type'a bakarak anlar
        }
        if (kind2.isEmpty() && forceKind.isNotEmpty()) kind2 = forceKind // JS hook'u içerikten tanıdı
        if (kind2.isEmpty()) return
        val now = System.currentTimeMillis()
        var isNew = true
        synchronized(tb.netCands) {
            for (c in tb.netCands) {
                if (c.url == u) {
                    c.t = now
                    isNew = false
                    break
                }
            }
            if (isNew) {
                tb.netCands.add(NetCand(u, kind2, now))
                if (tb.netCands.size > 120) tb.netCands.removeAt(0)
            }
        }
        if (isNew && tb.playerId != null) {
            if (tb.found.isEmpty()) ui.post { scheduleProbe(tb) }
            // Sonuç varken gelen adaylar sıradaki videonundur: arka planda önceden incele (kaydırınca anında bulunur)
            else Probe.warm(u, kind2, tb.pRef ?: tb.url)
        } else if (isNew && tb.found.isEmpty()) {
            // Oynatıcı başlamadan da yakala: kesin akış (m3u8/mpd) hemen incelenir, diğerleri önceden ısıtılır
            if (kind2 == "m3u8" || kind2 == "mpd") ui.post { scheduleProbe(tb) }
            else Probe.warm(u, kind2, tb.pRef ?: tb.url)
        }
    }

    // ---- oynatıcı bildirimi (hookJs'ten)
    private fun onPlayer(tb: Tab, raw: String) {
        if (isYtTab(tb)) return // YouTube'da video algılanmaz
        if (isGoogleTab(tb)) return // Google'da video algılanmaz
        val m = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        // Gömülü YouTube oynatıcısı (başka sitede iframe): tepki verme
        if (isYtUrl(m.optString("src", "")) || isYtUrl(m.optString("ref", ""))) return
        val id = m.optString("id")
        if (m.optBoolean("stop", false)) {
            if (id != tb.playerId) return
            tb.pAd = false
            clearMedia(tb)
            return
        }
        val dur = m.optDouble("d", 0.0)
        val newSrc = m.optString("src", "")
        // Başka bir video oynamaya başladıysa (süre/kaynak değişti) önceki videonun sonucu hemen silinir
        if (tb.playerId != null && (id != tb.playerId || Math.abs(dur - tb.pDur) > 0.5 || newSrc != tb.pSrc)) {
            tb.found = emptyList()
            tb.probeGen++
            if (tb === cur) updateFab()
        }
        if (id != tb.playerId || Math.abs(dur - tb.pDur) > 1) tb.bannerDone = false
        tb.playerId = id
        tb.pDur = dur
        if (m.optBoolean("show", false)) tb.autoShow = true
        tb.pLive = m.optBoolean("live", false)
        tb.pAd = m.optBoolean("ad", false)
        tb.pH = m.optInt("h", 0)
        tb.pSrc = m.optString("src", "")
        val ref = m.optString("ref", "")
        tb.pRef = if (ref.startsWith("http")) ref else null
        tb.pUrls.clear()
        m.optJSONArray("urls")?.let { a ->
            for (i in 0 until a.length()) tb.pUrls.add(a.getString(i))
        }
        tb.pHints.clear()
        m.optJSONObject("hints")?.let { h ->
            val keys = h.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tb.pHints[k] = h.optString(k)
            }
        }
        scheduleProbe(tb)
    }

    private fun hasNet(tb: Tab): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(tb.netCands) { tb.netCands.any { now - it.t < 90_000 } }
    }

    private fun clearMedia(tb: Tab, keepNet: Boolean = false) {
        tb.probeRun?.let { ui.removeCallbacks(it) }
        tb.probeGen++
        if (!keepNet) synchronized(tb.netCands) { tb.netCands.clear() }
        tb.pUrls.clear()
        tb.pHints.clear()
        tb.playerId = null
        tb.pDur = 0.0
        tb.pLive = false
        tb.pH = 0
        tb.pSrc = ""
        tb.pRef = null
        if (!keepNet) tb.found = emptyList()
        tb.bannerDone = false
        if (tb === cur) updateFab()
    }

    private fun scheduleProbe(tb: Tab) {
        val r = tb.probeRun ?: Runnable { runProbe(tb, null) }.also { tb.probeRun = it }
        ui.removeCallbacks(r)
        // Video açılır açılmaz bulunsun: oynatıcı beklenmez, ağdan gelen adaylar hemen incelenir
        ui.postDelayed(r, 150)
    }

    private fun probeArgs(tb: Tab): Map<String, Any?> {
        try { tb.web?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let { Net.ua = it } } catch (_: Throwable) {}
        val now = System.currentTimeMillis()
        val net = synchronized(tb.netCands) { tb.netCands.filter { now - it.t < 90_000 } }
        val urls = LinkedHashSet<String>()
        urls.addAll(tb.pUrls)
        for (c in net) urls.add(c.url)
        val hints = HashMap<String, String>(tb.pHints)
        for (c in net) hints[c.url] = c.kind
        return mapOf(
            "urls" to urls.filter { (it.startsWith("http") || it.startsWith("blob:")) && !isYtUrl(it) },
            "hints" to hints,
            "ref" to (tb.pRef ?: tb.web?.url ?: ""),
            "dur" to (if (tb.pLive) 0.0 else tb.pDur),
            "src" to tb.pSrc,
            "h" to tb.pH
        )
    }

    private fun runProbe(tb: Tab, onDone: ((List<FoundItem>) -> Unit)?) {
        val gen = ++tb.probeGen
        val startDur = tb.pDur
        val startSrc = tb.pSrc
        val startId = tb.playerId
        // Video değişmediyse (yalnızca yeni aday geldiyse) sonuç atılmaz
        fun stale() = tb.playerId != startId ||
            (startId == null && gen != tb.probeGen) || // oynatıcısız incelemede sayfa değiştiyse sonuç atılır
            Math.abs(tb.pDur - startDur) > 0.5 || tb.pSrc != startSrc
        dart("probe", probeArgs(tb), { r ->
            if (stale()) {
                onDone?.invoke(tb.found)
                return@dart
            }

            val original0 = (r as? List<*>)?.mapNotNull { e ->
                (e as? Map<*, *>)?.let { item ->
                    val url = item["url"]?.toString().orEmpty()
                    if (url.isBlank()) return@let null
                    FoundItem(
                        url,
                        item["kind"]?.toString().orEmpty(),
                        item["title"]?.toString().orEmpty(),
                        item["subtitle"]?.toString().orEmpty(),
                        (item["h"] as? Number)?.toInt() ?: 0,
                        (item["d"] as? Number)?.toDouble() ?: 0.0,
                        (item["s"] as? Number)?.toLong() ?: 0L,
                        (item["bw"] as? Number)?.toInt() ?: 0
                    )
                }
            } ?: emptyList()

            val wantDur = if (tb.pLive) 0.0 else tb.pDur
            // Normal sitede oynayan video kısa (fragman/reklam) ve listede çok daha uzun video varsa süre eşleştirmesi yapılmaz
            val trailerCase = wantDur in 0.5..30.0 && Probe.isAdSite(tb.pRef ?: tb.web?.url ?: tb.url) &&
                original0.any { it.duration >= 120 }
            val original = if (wantDur > 0.5 && !trailerCase) {
                val tol = maxOf(1.5, wantDur * 0.03)
                original0.filter { it.kind == "blob" || it.duration <= 0 && it.url.startsWith("blob:") ||
                    (it.duration > 0 && Math.abs(it.duration - wantDur) <= tol) }
            } else original0

            // WebView verilerini ana thread üzerinde al; ağ isteğini arka planda yap.
            val referer = tb.pRef ?: tb.web?.url ?: tb.url
            val hlsUrlCookies = original
                .filter { isHlsUrl(it.url, it.kind) }
                .associate { it.url to (try { CookieManager.getInstance().getCookie(it.url).orEmpty() } catch (_: Throwable) { "" }) }

            hlsExec.execute {
                val expanded = ArrayList<FoundItem>()
                // Kalite seçenekleri Probe'da "#fpq=<bant genişliği>" ile zaten açılıyor; Streams.hls
                // bunu çözüp seçilen kaliteyi VE ayrı ses listesini birlikte indirir.
                // (Burada medya listesine indirgemek sesi düşürüyordu.)
                expanded.addAll(original)

                // Reklam oynarken bulunan akışlar reklamdır: liste boş kalır, reklam bitince yeniden incelenir
                val result = if (tb.pAd) emptyList() else expanded.distinctBy { it.url }
                runOnUiThread {
                    if (isFinishing || stale() || (gen != tb.probeGen && tb.found.isNotEmpty())) {
                        onDone?.invoke(tb.found)
                        return@runOnUiThread
                    }
                    tb.found = result
                    prefetchThumb(tb, result)
                    if (tb === cur) updateFab()
                    if (result.isNotEmpty() && !tb.bannerDone) {
                        tb.bannerDone = true
                        if (tb === cur) toast(t("videoFound"))
                    }
                    if (result.isNotEmpty() && tb.autoShow && tb === cur) {
                        tb.autoShow = false
                        showFound()
                    }
                    onDone?.invoke(result)
                }
            }
        }, {
            onDone?.invoke(emptyList())
        })
    }

    private fun isHlsUrl(url: String, kind: String): Boolean {
        val path = try { android.net.Uri.parse(url).path.orEmpty() } catch (_: Throwable) { url.substringBefore('?') }
        return kind.contains("m3u8", ignoreCase = true) ||
            kind.contains("hls", ignoreCase = true) ||
            path.endsWith(".m3u8", ignoreCase = true)
    }

    /** Master playlist içindeki EXT-X-STREAM-INF satırlarını çözünürlük seçeneklerine çevirir. */
    private fun readHlsMasterPlaylist(
        masterUrl: String,
        referer: String,
        cookies: String,
        original: FoundItem
    ): List<FoundItem> {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL(masterUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
                setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
            }

            if (connection.responseCode !in 200..299) return emptyList()
            val finalUrl = connection.url
            val playlist = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!playlist.trimStart().startsWith("#EXTM3U")) return emptyList()

            val lines = playlist.lines()
            val variants = ArrayList<FoundItem>()
            val resolutionRegex = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
            val bandwidthRegex = Regex("(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\\d+)", RegexOption.IGNORE_CASE)

            for (index in lines.indices) {
                val info = lines[index].trim()
                if (!info.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue

                val resolution = resolutionRegex.find(info) ?: continue
                val width = resolution.groupValues[1].toIntOrNull() ?: continue
                val height = resolution.groupValues[2].toIntOrNull() ?: continue
                if (height <= 0 || width <= 0) continue
                // Ortalama bitrate varsa onu tercih et; yoksa BANDWIDTH kullan.
                val averageBandwidth = Regex("AVERAGE-BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(info)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val bandwidth = averageBandwidth
                    ?: bandwidthRegex.find(info)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 0

                // EXT-X-STREAM-INF sonrasındaki ilk boş olmayan, yorum olmayan satır URI'dir.
                val variantPath = lines.drop(index + 1).firstOrNull {
                    it.trim().isNotEmpty() && !it.trim().startsWith("#")
                }?.trim() ?: continue

                val variantUrl = try {
                    finalUrl.toURI().resolve(java.net.URI(variantPath)).toString()
                } catch (_: Throwable) {
                    try { java.net.URL(finalUrl, variantPath).toString() } catch (_: Throwable) { continue }
                }

                variants.add(
                    FoundItem(
                        variantUrl,
                        "m3u8",
                        original.title,
                        "${height}P · ${width}x${height}",
                        height,
                        original.duration,
                        0L,
                        bandwidth
                    )
                )
            }
            variants.distinctBy { it.url }.sortedWith(
                compareByDescending<FoundItem> { it.height }.thenByDescending { it.bw }
            )
        } catch (_: Throwable) {
            emptyList()
        } finally {
            try { connection?.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun updateFab() {
        val tb = cur ?: return
        val on = tb.found.isNotEmpty() && !isYtTab(tb) && !isGoogleTab(tb)
        fabView.setOn(on)
        // Yuvarlak indirme düğmesi geri getirildi (sürüklenebilir; video bulununca kırmızı)
        fab.visibility = View.VISIBLE
    }

    // ---- indirme
    /**
     * Facebook akışında (feed) video, gönderi adresi olmadan tek başına (sessiz fbcdn) iner.
     * Bu betik oynayan / ekranda en büyük videodan yukarı çıkıp gönderinin kendi adresini
     * (reel, videos, watch?v=, posts, permalink, share) ya da video kimliğini bulur.
     * Bulunamazsa boş döner (başka videonun adresi tahmin edilmez).
     */
    private val FB_POST_JS = """
(function(){try{
var POST=/(\/reel\/\d+|\/videos\/\d+|\/watch\/?\?(?:[^#]*&)?v=\d+|\/video\.php\?(?:[^#]*&)?v=\d+|\/posts\/[\w.-]+|\/permalink\.php\?|\/permalink\/|\/story\.php\?|\/share\/[rvp]\/[\w-]+|fb\.watch\/[\w-]+)/i;
var abs=function(h){try{return new URL(h,location.href).href}catch(e){return ''}};
var good=function(h){h=abs(h);return (h&&/^https?:\/\/[^\/]*(facebook\.com|fb\.watch)/i.test(h)&&POST.test(h))?h:''};
var vs=[].slice.call(document.querySelectorAll('video'));
var best=null,bs=-1;
vs.forEach(function(v){var r=v.getBoundingClientRect();
var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
var sc=w*h+(v.paused?0:1e9);if(sc>bs){bs=sc;best=v}});
if(!best)return '';
var el=best;
for(var i=0;el&&i<14;i++,el=el.parentElement){
var ds=el.getAttribute&&el.getAttribute('data-store');
if(ds){var m=/"videoID":"?(\d{6,})/.exec(ds);if(m)return 'https://www.facebook.com/watch/?v='+m[1];}
var vid=el.getAttribute&&(el.getAttribute('data-video-id')||el.getAttribute('data-videoid'));
if(vid&&/^\d{6,}$/.test(vid))return 'https://www.facebook.com/watch/?v='+vid;
var as=el.querySelectorAll?el.querySelectorAll('a[href]'):[];
for(var j=0;j<as.length;j++){var g=good(as[j].getAttribute('href'));if(g)return g.split('#')[0];}
if(el.tagName==='ARTICLE'||(el.getAttribute&&el.getAttribute('role')==='article'))break;
}
return '';
}catch(e){return ''}})()
"""

    private fun startDownload(
        tb: Tab, url: String, kind: String, name: String? = null, others: List<FoundItem> = emptyList()
    ) {
        if (isYtTab(tb)) { toast(t("ytNoDl")); return }
        try { tb.web?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let { Net.ua = it } } catch (_: Throwable) {}
        val cur = tb.pRef ?: tb.web?.url ?: ""
        fun send(ref: String) {
            dart(
                "download",
                mapOf(
                    "url" to url,
                    "kind" to kind,
                    "ref" to ref,
                    "title" to (name ?: tb.title),
                    "tab" to tb.id,
                    // Seçilen kaynak olmazsa sırayla bunlar denenir
                    "alts" to others.filter { it.url != url && it.kind != "blob" }.map { it.kind + "|" + it.url }
                ),
                { r -> toast(if (r is String) r else t("downloadStarted")) },
                { toast(t("failed")) }
            )
        }
        val fb = Regex("(facebook\\.com|fbcdn\\.|fb\\.watch)", RegexOption.IGNORE_CASE)
            .containsMatchIn(url + " " + cur)
        val web = tb.web
        if (fb && web != null && Ytdlp.postUrl(cur) == null && kind != "blob") {
            // Gönderi adresi bilinmiyor: sayfadan bul, yt-dlp'ye ver (ses+görüntü birlikte gelir)
            web.evaluateJavascript(FB_POST_JS) { raw ->
                val found = try {
                    (org.json.JSONTokener(raw ?: "").nextValue() as? String)?.trim()
                } catch (_: Throwable) { null }
                send(if (!found.isNullOrEmpty() && found.startsWith("http")) found else cur)
            }
        } else {
            send(cur)
        }
    }

    /** İndir düğmesi: parmakla sürüklenir (sağ/sol/yukarı/aşağı), bırakılan yer hatırlanır. */
    @Suppress("ClickableViewAccessibility")
    private fun setupFabDrag() {
        val sp = getSharedPreferences("fp_ui", MODE_PRIVATE)
        var startTx = 0f
        var startTy = 0f
        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = dp(8)

        fun clampTx(v: Float) = v.coerceIn(-fab.left.toFloat(), (content.width - fab.right).toFloat())
        fun clampTy(v: Float) = v.coerceIn(-fab.top.toFloat(), (content.height - fab.bottom).toFloat())

        fab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTx = fab.translationX
                    startTy = fab.translationY
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (moved) {
                        fab.translationX = clampTx(startTx + dx)
                        fab.translationY = clampTy(startTy + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        try {
                            sp.edit().putFloat("fab2X", fab.translationX).putFloat("fab2Y", fab.translationY).apply()
                        } catch (_: Throwable) {
                        }
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        // Ekran boyutu / alt çubuk değişince düğme dışarıda kalmasın
        fab.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (content.width > 0) {
                val x = clampTx(fab.translationX)
                val y = clampTy(fab.translationY)
                if (x != fab.translationX) fab.translationX = x
                if (y != fab.translationY) fab.translationY = y
            }
        }
        fab.post {
            // Varsayılan: sağ kenarın ortası. Kullanıcı sürüklediyse o yer hatırlanır.
            if (sp.contains("fab2X") || sp.contains("fab2Y")) {
                fab.translationX = clampTx(sp.getFloat("fab2X", 0f))
                fab.translationY = clampTy(sp.getFloat("fab2Y", 0f))
            } else {
                // Ortadan biraz aşağıda başla
                fab.translationY = clampTy(content.height * 0.10f)
            }
        }
    }

    private fun onFabTap() {
        val tb = cur ?: return
        // YouTube: indirme kapalı
        if (isYtTab(tb)) {
            toast(t("ytNoDl"))
            return
        }
        if (tb.found.isNotEmpty()) {
            showFound() // bulunan videoları seçenek olarak göster
            return
        }
        toast(t("checking"))
        probeThenScan(tb)
    }

    /** Önce oynatıcıdan bulunanlara bakar; boşsa sayfa kaynağını ve ağ kayıtlarını da tarar. */
    private fun probeThenScan(tb: Tab) {
        runProbe(tb) { list ->
            if (list.isNotEmpty()) showFound()
            else {
                tb.autoShow = true
                scanPage(tb)
            }
        }
    }

    private fun showFound() {
        val tb = cur ?: return
        if (isYtTab(tb)) { toast(t("ytNoDl")); return }
        // Normal sitede video bulunamadı ya da yalnızca kısa (fragman/reklam) var: bir kez videoyu kendimiz başlatıp ara
        val pageUrl = tb.web?.url ?: tb.url
        if (tb.web != null && tb.autoPlayUrl != pageUrl && Probe.isAdSite(pageUrl) &&
            tb.found.none { it.duration >= 60 }
        ) {
            tb.autoPlayUrl = pageUrl
            startPlayAndFind(tb)
            return
        }
        if (tb.found.isEmpty()) {
            toast(t("checking"))
            probeThenScan(tb)
            return
        }
        showFoundSheet(tb, tb.found)
    }

    /** Videoyu (sessiz) başlatır, oynat düğmesine basar; tam video ağdan görününce yeniden arar. */
    private fun startPlayAndFind(tb: Tab) {
        val w = tb.web ?: return
        toast(if (L.code == "tr") "Video başlatılıyor, aranıyor…" else "Starting the video, searching…")
        try { w.settings.mediaPlaybackRequiresUserGesture = false } catch (_: Throwable) {}
        w.evaluateJavascript("window.__fpPlay&&window.__fpPlay()", null)
        ui.postDelayed({
            try { tb.web?.settings?.mediaPlaybackRequiresUserGesture = true } catch (_: Throwable) {}
            if (isFinishing || tb !== cur) return@postDelayed
            probeThenScan(tb)
            // Aday adresler toplandı: videoyu durdur (arka planda sessizce oynamaya devam etmesin)
            tb.web?.evaluateJavascript(
                "Array.prototype.forEach.call(document.getElementsByTagName('video'),function(v){try{v.pause()}catch(e){}})", null
            )
        }, 3500)
    }

    // ---- Önizleme: kare önbelleği (liste hazır olunca arka planda önceden alınır)
    private val thumbMem = HashMap<String, Bitmap>()
    private val thumbBusy = HashSet<String>()

    /** Instagram/Facebook/TikTok/X akışları: sayfa önceden başka videoları da yüklediği için dosyadan alınan kare yanlış video olabilir. */
    private fun isFeedSite(u: String): Boolean =
        Regex("(instagram\\.com|facebook\\.com|fb\\.watch|tiktok\\.com|x\\.com|twitter\\.com)", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun grabFrame(f: FoundItem, ref: String): Bitmap? {
        if (f.kind == "blob" || f.kind == "mpd") return null
        val r = android.media.MediaMetadataRetriever()
        return try {
            val u = f.url.substringBefore("#")
            r.setDataSource(u, Net.headers(u, ref))
            val src = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (src != null) {
                val w = 360
                if (src.width > w) Bitmap.createScaledBitmap(src, w, src.height * w / src.width, true) else src
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    private val PAGE_RESOLVED = "Sayfadan çözüldü"

    private fun sortedFound(all: List<FoundItem>): List<FoundItem> {
        val found = all.filter {
            it.size > 0L || it.kind.equals("m3u8", true) || it.kind.equals("mpd", true) || it.kind == "blob" ||
                it.subtitle == PAGE_RESOLVED
        }.ifEmpty { all }
        val maxDur = found.maxOfOrNull { it.duration } ?: 0.0
        return found.sortedWith(
            compareByDescending<FoundItem> { if (maxDur > 0 && it.duration > 0 && it.duration < maxDur * 0.5) 0 else 1 }
                .thenByDescending { it.height }
                .thenByDescending { it.bw }
                .thenByDescending { it.size }
                .thenByDescending { it.duration }
        )
    }

    /** Video bulunur bulunmaz en iyi seçeneğin karesini arka planda alır: sayfa açılınca hazır olur. */
    private fun prefetchThumb(tb: Tab, found: List<FoundItem>) {
        if (isFeedSite(tb.pRef ?: tb.web?.url ?: tb.url)) return
        val f = sortedFound(found).firstOrNull() ?: return
        val key = f.url
        if (thumbMem.containsKey(key) || !thumbBusy.add(key)) return
        val ref = tb.pRef ?: tb.web?.url ?: tb.url
        Thread {
            val bm = grabFrame(f, ref)
            ui.post {
                thumbBusy.remove(key)
                if (bm != null) thumbMem[key] = bm
            }
        }.start()
    }

    /** Bulunan videoları alttan açılan sayfada gösterir; en yüksek kalite hazır seçili gelir. */
    private fun showFoundSheet(tb: Tab, found: List<FoundItem>) {
        val p = pal
        val tr = L.code == "tr"
        val th = Theme.of(dark)
        val acc = th.accent
        val onAcc = th.onAccent
        val chipBg = th.surface
        val thumbBg = th.surface2

        // Kısa klipleri (fragman/reklam) geride tut, kalan içinde en yüksek çözünürlük + en büyük dosya önce gelir
        val items = sortedFound(found)

        fun rounded(fill: Int, r: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dpf(r)
                if (sw > 0) setStroke(dp(sw), stroke)
            }

        fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(10), dp(18), dp(14))
        root.background = GradientDrawable().apply {
            setColor(p.bg)
            val r = dpf(Theme.R_SHEET)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val handle = View(this)
        handle.background = rounded(p.line, 2)
        val hp = LinearLayout.LayoutParams(dp(36), dp(4))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.bottomMargin = dp(12)
        root.addView(handle, hp)

        // Ağ durumu (Wi-Fi / mobil)
        val net = try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> if (tr) "Bağlantı yok" else "Offline"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> if (tr) "Mobil veri" else "Mobile data"
                else -> if (tr) "Ağ" else "Network"
            }
        } catch (_: Throwable) {
            if (tr) "Ağ" else "Network"
        }
        root.addView(tv("${if (tr) "Ağ" else "Network"} · $net", 14f, p.sub, true))

        // Küçük resim + ad
        var name = tb.title.ifEmpty { shownUrl(tb) }
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val thumb = FrameLayout(this)
        thumb.background = rounded(thumbBg, Theme.R)
        thumb.clipToOutline = true
        // Önizleme resmi: seçili videonun gerçek karesi (alınamazsa mavi kutu + ▶ kalır)
        val thumbImg = android.widget.ImageView(this)
        thumbImg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        thumbImg.visibility = View.GONE
        thumb.addView(thumbImg, FrameLayout.LayoutParams(-1, -1))
        val clap = tv("▶", 34f, Color.WHITE, true)
        clap.gravity = Gravity.CENTER
        thumb.addView(clap, FrameLayout.LayoutParams(-1, -1))
        val durText = items.firstOrNull { it.duration > 0 }?.let { Media.fmtDuration(it.duration) }
        if (durText != null) {
            val badge = tv(durText, 12f, Color.WHITE, true)
            badge.background = rounded(0xCC161A18.toInt(), 6)
            badge.setPadding(dp(8), dp(2), dp(8), dp(2))
            thumb.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }
        head.addView(thumb, LinearLayout.LayoutParams(dp(130), dp(86)))
        val nameTv = tv(name, 17f, p.fg, true)
        nameTv.maxLines = 3
        nameTv.ellipsize = TextUtils.TruncateAt.END
        val np = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        np.leftMargin = dp(14)
        head.addView(nameTv, np)
        val hlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(12)
        hlp.bottomMargin = dp(16)
        root.addView(head, hlp)

        // Kalite kutucukları (3'lü ızgara)
        var sel = 0
        val thumbCache = thumbMem
        val thumbRef = tb.pRef ?: tb.web?.url ?: tb.url
        fun showThumb(bm: Bitmap?) {
            if (bm == null) return
            thumbImg.setImageBitmap(bm)
            thumbImg.visibility = View.VISIBLE
            clap.visibility = View.GONE
        }
        // Seçili videonun karesini alır: önce dosyadan, olmazsa sayfada oynayan videodan
        fun loadThumb(f: FoundItem) {
            val feed = isFeedSite(thumbRef)
            if (!feed) thumbCache[f.url]?.let { showThumb(it); return }
            var snapShown = false
            val key = f.url
            fun still() = dlg.isShowing && items.getOrNull(sel)?.url == key && thumbImg.visibility != View.VISIBLE
            // 1) Sayfada oynayan videonun karesi: anında gelir
            tb.web?.evaluateJavascript("window.__fpSnap&&window.__fpSnap()") { raw ->
                try {
                    val str = org.json.JSONTokener(raw ?: "null").nextValue() as? String
                    if (!str.isNullOrEmpty() && str.contains("base64,") && still()) {
                        val bytes = android.util.Base64.decode(str.substringAfter("base64,"), android.util.Base64.DEFAULT)
                        val sn = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (sn != null) { snapShown = true; showThumb(sn) }
                    }
                } catch (_: Throwable) {
                }
            }
            // 2) Dosyadan kare (uzak adreste daha yavaş): gelince önbelleğe alınır
            if (!thumbBusy.add(key)) return
            Thread {
                val bm = grabFrame(f, thumbRef)
                ui.post {
                    thumbBusy.remove(key)
                    if (bm == null) return@post
                    if (feed) return@post // akışta oynayan videonun karesi esas; dosya karesi başka video olabilir
                    thumbCache[key] = bm
                    if (dlg.isShowing && items.getOrNull(sel)?.url == key) showThumb(bm)
                }
            }.start()
        }
        val chips = ArrayList<FrameLayout>()
        val ticks = ArrayList<TextView>()
        fun paint() {
            for (i in chips.indices) {
                val on = i == sel
                chips[i].background = if (on) rounded(chipBg, Theme.R, acc, 2) else rounded(chipBg, Theme.R, p.line, 1)
                ticks[i].visibility = if (on) View.VISIBLE else View.GONE
            }
        }
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        var row: LinearLayout? = null
        for ((idx, f) in items.withIndex()) {
            if (idx % 3 == 0) {
                row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val rp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rp.bottomMargin = dp(10)
                grid.addView(row, rp)
            }
            val label = when {
                f.height > 0 -> "${f.height}P"
                f.bw > 0 -> String.format("%.1f Mbps", f.bw / 1_000_000.0)
                f.kind == "blob" -> if (tr) "KORUMALI" else "PROTECTED"
                f.kind == "m4a" || f.kind == "mp3" || f.kind == "aac" || f.kind == "ogg" -> if (tr) "SES" else "AUDIO"
                else -> if (tr) "VİDEO" else "VIDEO"
            }
            val chip = FrameLayout(this)
            val inner = LinearLayout(this)
            inner.orientation = LinearLayout.VERTICAL
            inner.gravity = Gravity.CENTER
            inner.setPadding(dp(6), dp(14), dp(6), dp(14))
            val q = tv(label, 21f, p.fg, true)
            q.gravity = Gravity.CENTER
            inner.addView(q)
            // HLS dosyaları tek parça olmadığı için sunucu genellikle Content-Length vermez.
            // Bitrate (bit/sn) × süre (sn) / 8 ile yaklaşık boyut hesaplanır.
            val estimatedHlsBytes = if (
                f.size <= 0L && f.kind.equals("m3u8", ignoreCase = true) &&
                f.bw > 0 && f.duration > 0.0
            ) {
                (f.bw.toDouble() * f.duration / 8.0).toLong().coerceAtLeast(1L)
            } else 0L
            val sz = when {
                f.size > 0L -> Media.fmtBytes(f.size)
                estimatedHlsBytes > 0L -> "≈ ${Media.fmtBytes(estimatedHlsBytes)}"
                f.kind.equals("m3u8", ignoreCase = true) || f.kind.equals("mpd", ignoreCase = true) ->
                    if (tr) "Boyut bilinmiyor" else "Size unknown"
                else -> ""
            }
            val s = tv(if (f.duration > 0) (if (sz.isEmpty()) "" else "$sz · ") + Media.fmtDuration(f.duration) else sz, 14f, p.sub)
            s.gravity = Gravity.CENTER
            inner.addView(s)
            chip.addView(inner, FrameLayout.LayoutParams(-1, -2))
            val tick = tv("✓", 13f, onAcc, true)
            tick.gravity = Gravity.CENTER
            tick.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(acc) }
            val tp = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
            tp.topMargin = -dp(4)
            tp.rightMargin = -dp(2)
            chip.addView(tick, tp)
            chip.setOnClickListener {
                sel = idx
                paint()
                loadThumb(items[idx])
            }
            chips.add(chip)
            ticks.add(tick)
            val cp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            cp.leftMargin = if (idx % 3 == 0) 0 else dp(10)
            row!!.addView(chip, cp)
            if (idx == items.size - 1) {
                // Son satırı boş kutularla doldur ki genişlikler eşit kalsın
                var k = idx % 3 + 1
                while (k < 3) {
                    val sp = View(this)
                    val spp = LinearLayout.LayoutParams(0, 1, 1f)
                    spp.leftMargin = dp(10)
                    row.addView(sp, spp)
                    k++
                }
            }
        }
        paint()
        if (items.size > 9) {
            val sv = ScrollView(this)
            sv.addView(grid)
            root.addView(sv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.34).toInt()))
        } else {
            root.addView(grid)
        }

        // Alt eylemler: Kapat · Tekrar adlandır · İndir
        fun action(glyph: String, text: String, color: Int, onClick: () -> Unit): LinearLayout {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(8), 0, dp(8))
            val g = tv(glyph, 26f, color, true)
            g.gravity = Gravity.CENTER
            val t = tv(text, 14f, color)
            t.gravity = Gravity.CENTER
            c.addView(g)
            c.addView(t)
            c.setOnClickListener { onClick() }
            return c
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val cl = action("✕", if (tr) "Kapat" else "Close", p.fg) { dlg.dismiss() }
        val rn = action("✎", if (tr) "Yeniden adlandır" else "Rename", p.fg) {
            val et = EditText(this)
            et.setText(name)
            et.setSelection(et.text.length)
            AlertDialog.Builder(this)
                .setTitle(if (tr) "Yeniden adlandır" else "Rename")
                .setView(et)
                .setPositiveButton(if (tr) "Tamam" else "OK") { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isNotEmpty()) {
                        name = n
                        nameTv.text = n
                    }
                }
                .setNegativeButton(if (tr) "Vazgeç" else "Cancel", null)
                .show()
        }
        val dl = action("⤓", t("download"), acc) {
            val f = items[sel]
            dlg.dismiss()
            startDownload(tb, f.url, f.kind, name, items)
        }
        for (a in listOf(cl, rn, dl)) bar.addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.topMargin = dp(8)
        root.addView(bar, bp)

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
        loadThumb(items[sel])
    }

    // =====================================================================
    //  Blob videolar: sayfadan parça parça okunup dosyaya yazılır, sonra
    //  Dart'taki indirme yöneticisi devralır (Dart 'readBlob' ile çağırır).
    // =====================================================================
    private class BlobJob(
        val token: String,
        val tab: Tab,
        val path: String,
        val result: BlobRes
    ) {
        var size = 0L
        var off = 0L
        var done = false
        var timeout: Runnable? = null
    }

    private val blobJobs = HashMap<String, BlobJob>()
    private val blobExec = Executors.newSingleThreadExecutor()
    private val hlsExec = Executors.newSingleThreadExecutor()

    private fun q(s: String): String = JSONObject.quote(s)

    fun readBlob(url: String, path: String, tabId: Int?, result: BlobRes) {
        val tb = tabs.firstOrNull { it.id == tabId } ?: cur
        val w = tb?.web
        if (tb == null || w == null || url.isEmpty() || path.isEmpty()) {
            result.fail(t("blobFail"))
            return
        }
        val job = BlobJob(java.util.UUID.randomUUID().toString(), tb, path, result)
        blobJobs[job.token] = job
        armBlob(job)
        val js = "(function(){var T=" + q(job.token) + ";try{fetch(" + q(url) +
            ").then(function(r){return r.blob()}).then(function(b){window.__dlBlob=b;" +
            "__fp.blob(T,'ready',String(b.size),'')}).catch(function(){__fp.blob(T,'fail','','')});" +
            "}catch(e){__fp.blob(T,'fail','','')}})();"
        w.evaluateJavascript(js, null)
    }

    private fun armBlob(job: BlobJob) {
        job.timeout?.let { ui.removeCallbacks(it) }
        val r = Runnable { finishBlob(job, false) }
        job.timeout = r
        ui.postDelayed(r, 45_000)
    }

    private fun requestChunk(job: BlobJob) {
        val w = job.tab.web
        if (w == null) {
            finishBlob(job, false)
            return
        }
        armBlob(job)
        val js = "(function(T,o,n){try{var f=new FileReader();f.onload=function(){var s=String(f.result);" +
            "__fp.blob(T,'chunk',String(o),s.substring(s.indexOf(',')+1))};" +
            "f.onerror=function(){__fp.blob(T,'fail','','')};" +
            "f.readAsDataURL(window.__dlBlob.slice(o,o+n))}catch(e){__fp.blob(T,'fail','','')}})(" +
            q(job.token) + "," + job.off + "," + BLOB_CHUNK + ");"
        w.evaluateJavascript(js, null)
    }

    private fun onBlob(token: String, kind: String, a: String, b: String) {
        val job = blobJobs[token] ?: return
        if (job.done) return
        when (kind) {
            "fail" -> finishBlob(job, false)
            "ready" -> {
                job.size = a.toLongOrNull() ?: 0L
                if (job.size <= 0L) finishBlob(job, false) else requestChunk(job)
            }
            "chunk" -> {
                if (b.isEmpty() && job.off < job.size) {
                    finishBlob(job, false)
                    return
                }
                armBlob(job)
                val first = job.off == 0L
                blobExec.execute {
                    try {
                        val bytes = android.util.Base64.decode(b, android.util.Base64.DEFAULT)
                        FileOutputStream(job.path, !first).use { it.write(bytes) }
                        ui.post {
                            job.off += bytes.size.toLong()
                            if (job.off >= job.size) finishBlob(job, true) else requestChunk(job)
                        }
                    } catch (_: Throwable) {
                        ui.post { finishBlob(job, false) }
                    }
                }
            }
        }
    }

    private fun finishBlob(job: BlobJob, ok: Boolean) {
        if (job.done) return
        job.done = true
        job.timeout?.let { ui.removeCallbacks(it) }
        blobJobs.remove(job.token)
        job.tab.web?.evaluateJavascript("delete window.__dlBlob;", null)
        try {
            if (ok) job.result.ok() else job.result.fail(t("blobFail"))
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** MainActivity (Flutter) yeni adres / blob isteklerini bu örneğe iletir. */
        @JvmStatic
        var current: BrowserActivity? = null

        private const val HOME = "about:home"
        private const val HOME_BASE = "https://home.local/"
        private const val MAX_LIVE = 4
        private const val REQ_FILE = 4711
        private const val BLOB_CHUNK = 2 * 1024 * 1024
        private const val FAB_ON = 0xFF1E9E4A.toInt()
        private const val FAB_OFF = 0xE6616161.toInt()

        @Suppress("DEPRECATION")
        private const val IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        private val LIGHT = Theme.LIGHT.let { Pal(it.bg, it.fg, it.surface2, it.mut, it.line) }
        private val DARK = Theme.DARK.let { Pal(it.bg, it.fg, it.surface2, it.mut, it.line) }

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        // Sayfadaki betikler flutter_inappwebview.callHandler(ad, ...argümanlar) çağırır;
        // burada tüm argümanlar JSON dizisi olarak yerel köprüye iletilir.
        private const val SHIM =
            """(function(){if(window.flutter_inappwebview)return;window.flutter_inappwebview={callHandler:function(n){var a=[].slice.call(arguments,1);try{__fp.h(String(n),JSON.stringify(a));}catch(e){}return Promise.resolve();}};})();"""

        private const val PERF_START =
            """(function(){ var gen=window.__pg=(window.__pg||0)+1; var last=performance.now(), t0=last, frames=0, worst=0, jank=0; function tick(n){ if(window.__pg!==gen) return; var d=n-last; last=n; frames++; if(d>worst) worst=d; if(d>50) jank++; if(n-t0>=500){ try{ window.flutter_inappwebview.callHandler('perf',Math.round(frames*1000/(n-t0)),Math.round(worst),jank); }catch(e){} frames=0; worst=0; jank=0; t0=n; } requestAnimationFrame(tick); } requestAnimationFrame(tick); })();"""
        private const val PERF_STOP = "window.__pg=(window.__pg||0)+1;"

        private val MEDIA_RE = Regex(
            "\\.(m3u8|m3u|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|3g2|ogv|avi|wmv|mpg|mpeg|f4v|asf|m2ts|mts|m4a|aac|mp3|weba|opus)(?:\$|[?#/])|[?&]mime_type=video_(mp4|webm)", RegexOption.IGNORE_CASE
        )
        private val EXT_RE = Regex("\\.(mp4|webm|mov|m4v|mkv|flv|3gp|3g2|ogv|avi|wmv|mpg|mpeg|f4v|asf|m2ts|mts)\$")
        private val AD_URL_RE = Regex(
            "(doubleclick|googlesyndication|googleadservices|imasdk|adservice|adsystem|adnxs|adsrvr|pubmatic|" +
                "[/_.-]ads?[/_.-]|(^|[/_.-])(vast|vpaid|preroll|midroll|postroll)([/_.-]|\$))",
            RegexOption.IGNORE_CASE
        )
        private val SEG_RE = Regex(
            "((seg|segment|chunk|frag|fragment)[-_.]?\\d|/init[-_.][^/]*\$|[?&](bytestart|byterange|range|sq|seg|segment|chunk)=)",
            RegexOption.IGNORE_CASE
        )

        /** Sunucudan metin gelmezse (ilk açılışta) kullanılan temel Türkçe metinler. */
        private val FALLBACK = mapOf(
            "newTab" to "Yeni Sekme", "home" to "Ana Sayfa", "reload" to "Yenile",
            "bookmarks" to "Yer İşaretleri", "history" to "Geçmiş", "share" to "Paylaş",
            "copyLink" to "Bağlantıyı Kopyala", "addBookmark" to "Yer İşareti Ekle",
            "removeBookmark" to "Yer İşaretini Kaldır", "desktop" to "Masaüstü sitesi",
            "fullScreen" to "Tam ekran", "perfMeter" to "Performans ölçer",
            "downloads" to "İndirmeler", "settings" to "Ayarlar",
            "shareApp" to "Arkadaşlarınla Paylaş", "backToApp" to "Uygulamaya dön",
            "videosFound" to "Bulunan videolar", "search" to "Ara veya adres yaz",
            "noBookmarks" to "Yer imi yok", "noHistory" to "Geçmiş boş", "clear" to "Temizle",
            "cleared" to "Temizlendi", "linkCopied" to "Bağlantı kopyalandı",
            "cancel" to "İptal", "ok" to "Tamam", "add" to "Ekle", "addFav" to "Favorilere ekle",
            "nameLbl" to "Ad", "addrLbl" to "Adres", "removeFavQ" to "Favorilerden kaldırılsın mı?",
            "removeFav" to "Kaldır", "tabs" to "Sekmeler", "closeAll" to "Tüm sekmeleri kapat",
            "noVideo" to "Video bulunamadı. Videoyu oynatıp tekrar dene.",
            "ytNoDl" to "YouTube videoları indirilemez.",
            "checking" to "Videolar kontrol ediliyor...", "videoFound" to "Video bulundu",
            "getVideo" to "İndir", "downloadStarted" to "İndirme başladı", "failed" to "Başarısız",
            "blobFail" to "Blob okunamadı", "bookmarkAdded" to "Yer imi eklendi",
            "bookmarkRemoved" to "Yer imi kaldırıldı", "shareAppText" to "Bu tarayıcıyı denemelisin!",
            "perfPage" to "SAYFA (WebView)", "perfWorst" to "en kötü kare", "perfJank" to "takılma"
        )
    }
}


/** Yuvarlak indirme düğmesi: soluk gri (video yok) / parlayan yeşil (video var) + nabız gibi atan ışıma ve dışa yayılan halka. */
class DlFabView(ctx: android.content.Context) : android.view.View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ringP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val ic = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    private val icStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.8f * d
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val head = android.graphics.Path()
    private var on = false
    private var ring = 0f
    private var pulse = 0f
    private var anim: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // gölge / ışıma için
        fill.setShadowLayer(4f * d, 0f, 1.5f * d, 0x55000000)
    }

    fun setOn(v: Boolean) {
        if (v == on) return
        on = v
        anim?.cancel()
        anim = null
        ring = 0f
        pulse = 0f
        if (v) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    ring = it.animatedValue as Float
                    val f = it.animatedFraction
                    pulse = if (f < 0.5f) f * 2f else (1f - f) * 2f // 0 -> 1 -> 0
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (on && anim == null) { on = false; setOn(true) }
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 24f * d
        if (on) {
            // dışa yayılan halka
            val rr = r + ring * (width / 2f - r - 2f * d)
            ringP.color = (Theme.of(Store.dark).accent and 0xFFFFFF) or ((((1f - ring) * 200).toInt()) shl 24)
            c.drawCircle(cx, cy, rr, ringP)
            // parlayan yeşil ışıma (gölge katmanı)
            fill.setShadowLayer((7f + 8f * pulse) * d, 0f, 0f, Theme.of(Store.dark).accent)
            fill.color = Theme.of(Store.dark).accent
        } else {
            fill.setShadowLayer(4f * d, 0f, 1.5f * d, 0x55000000)
            fill.color = 0xB39E9E9E.toInt()
        }
        c.drawCircle(cx, cy, r, fill)
        // simge: ok + tepsi
        val a = if (on) 255 else 190
        ic.alpha = a
        icStroke.alpha = a
        c.drawLine(cx, cy - 9.5f * d, cx, cy - 0.5f * d, icStroke)
        head.reset()
        head.moveTo(cx - 6.5f * d, cy - 1.5f * d)
        head.lineTo(cx + 6.5f * d, cy - 1.5f * d)
        head.lineTo(cx, cy + 6f * d)
        head.close()
        c.drawPath(head, ic)
        c.drawLine(cx - 8f * d, cy + 10f * d, cx + 8f * d, cy + 10f * d, icStroke)
    }
}
