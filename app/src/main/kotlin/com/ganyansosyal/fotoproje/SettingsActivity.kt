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
        t.text = txt
        t.setTextColor(accent)
        t.typeface = Typeface.DEFAULT_BOLD
        t.textSize = 16f
        t.setPadding(dp(8), dp(22), dp(8), dp(8))
        box.addView(t)
    }

    private fun group(vararg rows: View) {
        val g = LinearLayout(this)
        g.orientation = LinearLayout.VERTICAL
        g.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
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
            row(L.t("clearCache")) {
                try { WebView(this).clearCache(true) } catch (_: Throwable) {}
                toast(L.t("cleared"))
            },
            row(L.t("clearHistory")) {
                Store.clearHistory()
                toast(L.t("cleared"))
            },
            row(L.t("clearCookies")) {
                try { CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) {}
                toast(L.t("cleared"))
            }
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
            row(L.t("theme"), if (Store.dark) L.t("themeDark") else L.t("themeLight")) {
                pick(
                    L.t("theme"),
                    listOf("light" to L.t("themeLight"), "dark" to L.t("themeDark")),
                    Store.theme
                ) { Store.setString("theme", it); recreate() }
            },
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
