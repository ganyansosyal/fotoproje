package com.ganyansosyal.fotoproje

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ayarlar ekranı (eski settings_page.dart). Saf Android görünümleri. */
class SettingsActivity : Activity() {
    private lateinit var box: LinearLayout

    private val th get() = Theme.of(Store.dark)
    private val bg get() = th.bg
    private val card get() = th.surface
    private val fg get() = th.fg
    private val sub get() = th.mut
    private val line get() = th.line
    private val accent get() = th.accent

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dlgTheme() =
        if (Store.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    override fun onCreate(b: Bundle?) {
        setTheme(
            if (Store.dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(b)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(this)
        back.text = "‹"
        back.textSize = 30f
        back.setTextColor(fg)
        back.gravity = Gravity.CENTER
        back.setOnClickListener { finish() }
        top.addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))
        val title = TextView(this)
        title.text = L.t("settings")
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(fg)
        top.addView(title)
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        val sv = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), 0, dp(16), dp(24))
        sv.addView(box)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    // ---------------------------------------------------------------- yapı taşları
    private fun header(txt: String) {
        val t = TextView(this)
        t.text = txt.uppercase(java.util.Locale.getDefault())
        t.setTextColor(accent)
        t.typeface = Typeface.DEFAULT_BOLD
        t.textSize = 12f
        t.letterSpacing = 0.08f
        t.setPadding(dp(4), dp(24), dp(4), dp(10))
        box.addView(t)
    }

    private fun group(vararg rows: View) {
        val g = LinearLayout(this)
        g.orientation = LinearLayout.VERTICAL
        g.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
            setStroke(dp(1), line)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val d = View(this)
                d.setBackgroundColor(line)
                g.addView(d, LinearLayout.LayoutParams(-1, 1))
            }
            g.addView(r)
        }
        box.addView(g, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(title: String, subtitle: String? = null, click: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(18), dp(14), dp(18), dp(14))
        val t = TextView(this)
        t.text = title
        t.setTextColor(fg)
        t.textSize = 16f
        r.addView(t)
        if (subtitle != null) {
            val s = TextView(this)
            s.text = subtitle
            s.setTextColor(sub)
            s.textSize = 13f
            r.addView(s)
        }
        if (click != null) r.setOnClickListener { click() }
        return r
    }

    private fun switchRow(title: String, subtitle: String?, value: Boolean, on: (Boolean) -> Unit): View {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        val left = row(title, subtitle)
        left.setPadding(0, 0, 0, 0)
        r.setPadding(dp(18), dp(10), dp(14), dp(10))
        r.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = value
        // Açıkken mavi, kapalıyken sönük gri (cihazın kahverengi varsayılanı yerine)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = android.content.res.ColorStateList(
            states, intArrayOf(accent, if (Store.dark) 0xFF80868B.toInt() else 0xFFBDC1C6.toInt())
        )
        sw.trackTintList = android.content.res.ColorStateList(
            states, intArrayOf((accent and 0x00FFFFFF) or 0x66000000, line)
        )
        sw.setOnCheckedChangeListener { _, v -> on(v) }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    private fun pick(title: String, items: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val idx = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(this, dlgTheme())
            .setTitle(title)
            .setSingleChoiceItems(items.map { it.second }.toTypedArray(), idx) { d, which ->
                d.dismiss()
                onPick(items[which].first)
            }
            .setNegativeButton(L.t("cancel"), null)
            .show()
    }

    private fun tr(a: String, b: String) = if (L.code == "tr") a else b

    private fun gb(b: Long): String =
        if (b >= 1_073_741_824L) String.format(java.util.Locale.getDefault(), "%.1f GB", b / 1_073_741_824.0)
        else String.format(java.util.Locale.getDefault(), "%.0f MB", b / 1_048_576.0)

    /** İndirme adresinin altında depolama durumu: indirilenler / diğer kullanılan / boş. */
    private fun storageRow(): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(18), dp(2), dp(18), dp(16))
        var total = 0L
        var free = 0L
        var used = 0L
        try {
            val st = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            total = st.totalBytes
            free = st.availableBytes
        } catch (_: Throwable) {}
        try {
            java.io.File(Downloads.displayPath).walkTopDown().forEach { if (it.isFile) used += it.length() }
        } catch (_: Throwable) {}
        if (total <= 0L) return r
        val dlW = if (used > 0) (used.toFloat() / total).coerceAtLeast(0.015f) else 0f
        val otherW = ((total - free - used).toFloat() / total).coerceAtLeast(0f)
        val freeW = (1f - dlW - otherW).coerceAtLeast(0f)
        val bar = LinearLayout(this)
        bar.weightSum = dlW + otherW + freeW
        bar.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(th.surface2) }
        bar.clipToOutline = true
        bar.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        fun seg(w: Float, c: Int) {
            if (w <= 0f) return
            val v = View(this)
            v.setBackgroundColor(c)
            bar.addView(v, LinearLayout.LayoutParams(0, -1, w))
        }
        seg(dlW, accent)
        seg(otherW, (fg and 0x00FFFFFF) or 0x55000000)
        r.addView(bar, LinearLayout.LayoutParams(-1, dp(8)))
        val lg = LinearLayout(this)
        val a = TextView(this)
        a.text = tr("İndirilenler ", "Downloads ") + gb(used)
        a.setTextColor(sub)
        a.textSize = 12f
        val b = TextView(this)
        b.text = gb(free) + tr(" boş", " free")
        b.setTextColor(sub)
        b.textSize = 12f
        b.gravity = Gravity.END
        lg.addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        lg.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(lg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return r
    }

    /** Önbellek / geçmiş / çerez temizleme: üç satır yerine tek düğme sırası. */
    private fun clearRow(): View {
        val r = LinearLayout(this)
        r.setPadding(dp(14), dp(12), dp(14), dp(14))
        fun btn(txt: String, act: () -> Unit) {
            val b = TextView(this)
            b.text = txt
            b.textSize = 13f
            b.typeface = Typeface.DEFAULT_BOLD
            b.setTextColor(fg)
            b.gravity = Gravity.CENTER
            b.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), line) }
            b.setOnClickListener { act(); toast(L.t("cleared")) }
            r.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        btn(tr("Önbellek", "Cache")) { try { WebView(this).clearCache(true) } catch (_: Throwable) {} }
        btn(tr("Geçmiş", "History")) { Store.clearHistory() }
        btn(tr("Çerezler", "Cookies")) { try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {} }
        return r
    }

    /** Tema: Açık / Koyu parçalı seçici. */
    private fun themeRow(): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(18), dp(14), dp(18), dp(14))
        val t = TextView(this)
        t.text = L.t("theme")
        t.setTextColor(fg)
        t.textSize = 16f
        r.addView(t)
        val seg = LinearLayout(this)
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        seg.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(th.surface2) }
        fun opt(label: String, key: String) {
            val on = Store.theme == key
            val b = TextView(this)
            b.text = label
            b.gravity = Gravity.CENTER
            b.textSize = 14f
            b.typeface = Typeface.DEFAULT_BOLD
            b.setTextColor(if (on) fg else sub)
            if (on) b.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat(); setColor(bg); setStroke(dp(1), line)
            }
            b.setOnClickListener { if (!on) { Store.setString("theme", key); recreate() } }
            seg.addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        opt(L.t("themeLight"), "light")
        opt(L.t("themeDark"), "dark")
        r.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return r
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun info(title: String, body: String) {
        AlertDialog.Builder(this, dlgTheme()).setTitle(title).setMessage(body)
            .setPositiveButton(L.t("ok"), null).show()
    }

    private fun feedback() {
        val e = EditText(this)
        e.hint = L.t("feedbackHint")
        e.minLines = 4
        e.gravity = Gravity.TOP
        val w = LinearLayout(this)
        w.setPadding(dp(22), dp(8), dp(22), 0)
        w.addView(e, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this, dlgTheme())
            .setTitle(L.t("feedbackTitle"))
            .setView(w)
            .setPositiveButton(L.t("send")) { _, _ ->
                val text = e.text.toString().trim()
                if (text.isNotEmpty()) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_SUBJECT, L.t("feedbackTitle"))
                    i.putExtra(Intent.EXTRA_TEXT, "[$APP_VERSION] $text")
                    try { startActivity(Intent.createChooser(i, null)) } catch (_: Throwable) {}
                }
            }
            .setNegativeButton(L.t("cancel"), null)
            .show()
    }

    // ---------------------------------------------------------------- içerik
    private fun render() {
        box.removeAllViews()
        header(L.t("download"))
        group(
            row(L.t("downloadAddr"), Downloads.displayPath) {
                pick(
                    L.t("downloadAddr"),
                    listOf("false" to L.t("appFolder"), "true" to L.t("publicFolder")),
                    Store.publicFolder.toString()
                ) { Store.setBool("publicFolder", it == "true"); render() }
            },
            storageRow(),
            switchRow(L.t("wifiOnly"), null, Store.wifiOnly) { Store.setBool("wifiOnly", it) }
        )
        header(L.t("browser"))
        group(
            switchRow(L.t("blockAds"), if (Store.blockAds) L.t("on") else L.t("off"), Store.blockAds) {
                Store.setBool("blockAds", it); render()
            },
            switchRow(L.t("recentSites"), null, Store.recentSites) { Store.setBool("recentSites", it) },
            row(L.t("searchEngine"), Store.engine) {
                pick(L.t("engine"), searchEngines.keys.map { it to it }, Store.engine) {
                    Store.setString("engine", it); render()
                }
            },
            clearRow()
        )
        header(L.t("general"))
        group(
            row(L.t("language"), when (Store.lang) {
                "tr" -> "Türkçe"; "en" -> "English"; else -> L.t("langDefault")
            }) {
                pick(
                    L.t("language"),
                    listOf("system" to L.t("langDefault"), "tr" to "Türkçe", "en" to "English"),
                    Store.lang
                ) { Store.setString("lang", it); recreate() }
            },
            themeRow(),
            switchRow(L.t("syncGallery"), if (Store.syncGallery) L.t("on") else L.t("off"), Store.syncGallery) {
                Store.setBool("syncGallery", it); render()
            }
        )
        header(L.t("help"))
        group(
            row(L.t("feedback")) { feedback() },
            row(L.t("privacy")) { info(L.t("privacy"), L.t("privacyText")) },
            row(APP_VERSION)
        )
    }
}
