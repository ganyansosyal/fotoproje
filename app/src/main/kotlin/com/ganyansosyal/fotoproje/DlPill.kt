package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

/**
 * Tam ekranda (tarayıcı tam ekran modu ya da video tam ekranı) üst/alt çubuklar gizli olduğundan
 * indirme ilerlemesi görünmez. Bu küçük hap şeklindeki bildirim yalnızca tam ekranda çıkar:
 * indirirken ilerleme, bitince "nereye indi" bilgisi. Dokununca İndirmeler ekranı açılır.
 * Sayfa düzenine dokunmaz: pencerenin en üstüne eklenen yüzen bir görünümdür.
 */
class DlPill(private val act: Activity, private val onTap: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val dm = act.resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).toInt()

    private val box = LinearLayout(act)
    private val line1 = TextView(act)
    private val line2 = TextView(act)
    private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
    private var attached = false
    private var track: DownloadTask? = null
    private val hideRun = Runnable { hide() }

    init {
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(10), dp(16), dp(10))
        box.background = GradientDrawable().apply {
            setColor(0xF2161A18.toInt()); cornerRadius = dp(Theme.R_PILL).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
        box.elevation = dp(10).toFloat()
        line1.setTextColor(Color.WHITE)
        line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        line1.typeface = Typeface.DEFAULT_BOLD
        line1.maxLines = 1
        line2.setTextColor(0xB3FFFFFF.toInt())
        line2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        line2.maxLines = 1
        line2.ellipsize = TextUtils.TruncateAt.MIDDLE
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(Theme.DARK.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        bar.indeterminateTintList = ColorStateList.valueOf(Theme.DARK.accent)
        box.addView(line1, LinearLayout.LayoutParams(-2, -2))
        box.addView(line2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
        box.setOnClickListener { hide(); onTap() }
        box.visibility = View.GONE
    }

    private fun tr() = L.code == "tr"

    private fun attach() {
        if (attached) return
        val decor = act.window.decorView as? ViewGroup ?: return
        // Sağ üstteki "tam ekrandan çık" düğmesiyle çakışmasın diye iki yandan boşluk bırakılır
        decor.addView(
            box,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(26)
                leftMargin = dp(60); rightMargin = dp(60)
            }
        )
        attached = true
    }

    private fun show() {
        attach()
        box.bringToFront() // video tam ekranının (customView) da üstünde dursun
        if (box.visibility != View.VISIBLE) {
            box.alpha = 0f
            box.translationY = -dp(24).toFloat()
            box.visibility = View.VISIBLE
            box.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    fun hide() {
        ui.removeCallbacks(hideRun)
        if (box.visibility != View.VISIBLE) return
        box.animate().alpha(0f).translationY(-dp(24).toFloat()).setDuration(180)
            .withEndAction { box.visibility = View.GONE }.start()
    }

    private fun where(t: DownloadTask): String {
        val folder = if (Build.VERSION.SDK_INT >= 29) {
            (if (tr()) "Galeri › " else "Gallery › ") + "Movies/VideoDownloader"
        } else {
            t.path?.let { File(it).parent } ?: ""
        }
        val name = t.path?.let { File(it).name } ?: t.title
        return if (folder.isEmpty()) name else "$folder\n$name"
    }

    /** [inFull] true ise (tam ekranda) göster; değilse hap hemen gizlenir. */
    fun update(inFull: Boolean) {
        if (!inFull) {
            track = null
            ui.removeCallbacks(hideRun)
            box.animate().cancel()
            box.visibility = View.GONE
            return
        }
        val run = Downloads.active.firstOrNull { it.status == DlStatus.RUNNING }
        if (run != null) {
            track = run
            ui.removeCallbacks(hideRun)
            val p = run.progress
            val pct = Math.round(p * 100)
            line1.text = when {
                p !in 0.0..1.0 -> if (tr()) "İndiriliyor…" else "Downloading…"
                tr() -> "İndiriliyor · %$pct"
                else -> "Downloading · $pct%"
            }
            line2.text = run.label.ifEmpty { run.title }
            line2.maxLines = 1
            if (p in 0.0..1.0) { bar.isIndeterminate = false; bar.progress = (p * 1000).toInt() }
            else bar.isIndeterminate = true
            show()
            return
        }
        val t = track ?: return // hiçbir şey indirilmiyor: açık kalan sonuç mesajı kendi süresini bekler
        track = null
        bar.isIndeterminate = false
        when (t.status) {
            DlStatus.DONE -> {
                line1.text = if (tr()) "✓ İndirildi" else "✓ Downloaded"
                line2.text = where(t)
                line2.maxLines = 2
                bar.progress = 1000
                show()
                ui.postDelayed(hideRun, 5000)
            }
            DlStatus.FAILED -> {
                line1.text = if (tr()) "✕ İndirme başarısız" else "✕ Download failed"
                line2.text = t.error ?: ""
                line2.maxLines = 1
                bar.progress = 0
                show()
                ui.postDelayed(hideRun, 4000)
            }
            else -> hide()
        }
    }
}
