package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** İlerleme / Bitti listeleri (eski downloads_pages.dart). Saf Android görünümleri. */
class DownloadsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var tabProg: TextView
    private lateinit var tabDone: TextView
    private var showDone = false
    private val listener: () -> Unit = { render() }

    private val dark get() = Store.dark
    private val th get() = Theme.of(dark)
    private val bg get() = th.bg
    private val card get() = th.surface
    private val fg get() = th.fg
    private val sub get() = th.mut
    private val line get() = th.line
    private val track get() = th.surface2
    private val accent get() = th.accent
    private val accentText get() = th.accent
    private val accentSoft get() = th.accentSoft
    private val danger get() = th.danger
    private val dangerSoft get() = th.dangerSoft
    private val onAccent get() = th.onAccent

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(r).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /** Başlığa göre sabit bir renk geçişi (küçük resim yokken / ilerleme kartında). */
    private fun thumbGradient(title: String): GradientDrawable {
        val h = (Math.abs(title.hashCode()) % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(h, 0.46f, 0.55f))
        val c2 = Color.HSVToColor(floatArrayOf((h + 45f) % 360f, 0.52f, 0.32f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = dp(16).toFloat()
        }
    }

    override fun onCreate(b: Bundle?) {
        setTheme(
            if (Store.dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(b)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        showDone = intent.getBooleanExtra("done", false)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(8), dp(12), 0)
        val back = TextView(this)
        back.text = "‹"
        back.textSize = 30f
        back.setTextColor(fg)
        back.gravity = Gravity.CENTER
        back.setOnClickListener { finish() }
        top.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this)
        title.text = L.t("downloads")
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(fg)
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))

        // Hap biçimli sekme seçici
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4))
        tabs.background = rounded(track, 16)
        tabProg = tabView(L.t("progress")) { showDone = false; render() }
        tabDone = tabView(L.t("done")) { showDone = true; Downloads.markSeen(); render() }
        tabs.addView(tabProg, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        tabs.addView(tabDone, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), 0) })

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(16), dp(16), dp(16), dp(24))
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun tabView(txt: String, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = txt
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        t.setOnClickListener { click() }
        return t
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(listener)
        if (showDone) Downloads.markSeen()
        render()
    }

    override fun onPause() {
        Downloads.removeListener(listener)
        super.onPause()
    }

    private fun style(t: TextView, on: Boolean) {
        t.setTextColor(if (on) fg else sub)
        t.typeface = Typeface.DEFAULT_BOLD
        t.background = rounded(if (on) card else Color.TRANSPARENT, 12)
        t.elevation = if (on) dp(1).toFloat() else 0f
    }

    private fun render() {
        style(tabProg, !showDone)
        style(tabDone, showDone)
        val shown = Downloads.shown()
        tabProg.text = L.t("progress") + if (shown.isNotEmpty()) "  ${shown.size}" else ""
        tabDone.text = L.t("done") + if (Downloads.unseen > 0) "  ${Downloads.unseen}" else ""
        list.removeAllViews()
        if (!showDone) {
            if (shown.isEmpty()) empty(L.t("noActive"), "⤓") else for (t in shown) list.addView(activeRow(t))
        } else {
            if (Downloads.done.isEmpty()) empty(L.t("noDownloads"), "▶") else for (t in Downloads.done) list.addView(doneRow(t))
        }
    }

    private fun empty(txt: String, symbol: String) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(110), 0, 0)
        val icon = TextView(this)
        icon.text = symbol
        icon.textSize = 34f
        icon.gravity = Gravity.CENTER
        icon.setTextColor(accentText)
        icon.background = rounded(accentSoft, 28)
        col.addView(icon, LinearLayout.LayoutParams(dp(84), dp(84)))
        val t = TextView(this)
        t.text = txt
        t.setTextColor(fg)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.textSize = 17f
        col.addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        list.addView(col, LinearLayout.LayoutParams(-1, -2))
    }

    private fun cardBox(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        c.background = rounded(card, 22, line)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(6), 0, dp(6))
        c.layoutParams = lp
        return c
    }

    /** Yumuşak renkli, 44dp yüksekliğinde yuvarlak düğme. */
    private fun pill(txt: String, fgc: Int, bgc: Int, click: () -> Unit): TextView {
        val b = TextView(this)
        b.text = txt
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(fgc)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), 0, dp(16), 0)
        b.background = rounded(bgc, 14)
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(8) }
        return b
    }

    private fun actionRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        return row
    }

    private fun activeRow(t: DownloadTask): View {
        val c = cardBox()
        val failed = t.status == DlStatus.FAILED
        val paused = t.status == DlStatus.PAUSED
        val p = t.progress

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        // Sol: renkli kutu + yüzde
        val tb = FrameLayout(this)
        tb.background = thumbGradient(t.title)
        val pct = TextView(this)
        pct.text = if (failed) "!" else if (p < 0) "…" else "${(p.coerceIn(0.0, 1.0) * 100).toInt()}%"
        pct.setTextColor(Color.WHITE)
        pct.typeface = Typeface.DEFAULT_BOLD
        pct.textSize = 15f
        pct.gravity = Gravity.CENTER
        tb.addView(pct, FrameLayout.LayoutParams(-1, -1))
        head.addView(tb, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = t.title
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setTextColor(fg)
        name.typeface = Typeface.DEFAULT_BOLD
        name.textSize = 15f
        col.addView(name)
        val meta = TextView(this)
        meta.textSize = 12.5f
        if (failed) {
            meta.text = L.t("failed") + ": " + (t.error ?: "")
            meta.setTextColor(danger)
            meta.maxLines = 2
        } else {
            meta.text = if (t.label.isEmpty()) L.t("queued") else t.label
            meta.setTextColor(sub)
        }
        col.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(head)

        // İlerleme çubuğu
        if (!failed && p < 0 && !paused) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.isIndeterminate = true
            c.addView(pb, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })
        } else {
            val col2 = if (failed) danger else if (paused) sub else accent
            val frac = if (failed) 1f else p.coerceIn(0.0, 1.0).toFloat()
            c.addView(SegBar(this, track, col2, frac), LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(10) })
        }

        val row = actionRow()
        if (failed || paused) row.addView(pill(L.t(if (failed) "retry" else "resume"), onAccent, accent) { Downloads.resume(t) })
        else if (t.canPause) row.addView(pill(L.t("pause"), accentText, accentSoft) { Downloads.pause(t) })
        row.addView(pill(L.t("cancel"), danger, dangerSoft) { if (failed) Downloads.removeFailed(t) else Downloads.cancel(t) })
        c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return c
    }

    /** Yuvarlak, yumuşak renkli ikon düğme (40dp). */
    private fun iconBtn(kind: Int, desc: String, fgc: Int, bgc: Int, click: () -> Unit): View {
        val b = VecIcon(this, kind, 20f)
        b.setTextColor(fgc)
        b.contentDescription = desc
        b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bgc) }
        b.setOnClickListener { click() }
        b.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
        return b
    }

    private fun doneRow(t: DownloadTask): View {
        val c = cardBox()
        c.setPadding(dp(12), dp(12), dp(12), dp(12))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        // Küçük resim (videonun bir karesi) + oynat rozeti
        val thumbBox = FrameLayout(this)
        thumbBox.background = rounded(th.surface2, Theme.R)
        thumbBox.clipToOutline = true
        val thumb = android.widget.ImageView(this)
        thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        thumbBox.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(this)
        badge.text = "▶"
        badge.textSize = 11f
        badge.setTextColor(Color.WHITE)
        badge.gravity = Gravity.CENTER
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x6B000000)
        }
        thumbBox.addView(badge, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        val p = t.path
        if (p != null) loadThumb(p, thumb)
        thumbBox.setOnClickListener { openFile(t) }
        top.addView(thumbBox, LinearLayout.LayoutParams(dp(84), dp(60)).apply { rightMargin = dp(12) })

        // Başlık + yalnızca boyut (teknik not gösterilmez)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = t.title
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setTextColor(fg)
        name.typeface = Typeface.DEFAULT_BOLD
        name.textSize = 15f
        col.addView(name)
        val s = TextView(this)
        s.text = Media.fmtBytes(t.size)
        s.setTextColor(sub)
        s.textSize = 12.5f
        col.addView(s, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        // GEÇİCİ TEŞHİS: hangi yöntemin kullanıldığını (yt-dlp/FFmpeg/SES YOK ...) göster. Sorun çözülünce kaldır.
        val nt = t.note
        if (!nt.isNullOrEmpty()) {
            val nv = TextView(this)
            nv.text = nt
            nv.maxLines = 14
            nv.ellipsize = android.text.TextUtils.TruncateAt.END
            nv.setTextColor(sub)
            nv.textSize = 10.5f
            col.addView(nv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }
        top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        // Sağda ikon düğmeler: paylaş + sil
        top.addView(iconBtn(VecIcon.SHARE, L.t("share"), accentText, accentSoft) { shareFile(t) })
        top.addView(iconBtn(VecIcon.TRASH, L.t("delete"), danger, dangerSoft) { Downloads.deleteDone(t) })

        c.addView(top)
        c.setOnClickListener { openFile(t) }
        return c
    }

    private fun loadThumb(path: String, iv: android.widget.ImageView) {
        // Anahtar: yol + boyut + değişme zamanı. Eski video silinip aynı adla yenisi inince
        // önbellekteki ESKİ kare gösteriliyordu.
        val f0 = File(path)
        val key = path + "|" + f0.length() + "|" + f0.lastModified()
        thumbCache[key]?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        Thread {
            var bmp: android.graphics.Bitmap? = null
            // 1) MediaMetadataRetriever: birkaç farklı zamanı ve seçeneği dene
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val times = longArrayOf(1_000_000L, 0L, dur * 500L, 3_000_000L)
                loop@ for (t in times) {
                    for (opt in intArrayOf(
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    )) {
                        try {
                            bmp = r.getFrameAtTime(t, opt)
                        } catch (_: Throwable) {
                        }
                        if (bmp != null) break@loop
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { r.release() } catch (_: Throwable) {}
            }
            // 2) Olmadıysa sistemin küçük resim üreticisi
            if (bmp == null && android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    bmp = android.media.ThumbnailUtils.createVideoThumbnail(
                        File(path), android.util.Size(320, 240), null
                    )
                } catch (_: Throwable) {
                }
            }
            if (bmp != null) {
                val w = 320
                val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            } else {
                bmp = placeholder()
            }
            val out = bmp!!
            thumbCache[key] = out
            runOnUiThread { iv.setImageBitmap(out) }
        }.start()
    }

    /** Kare alınamayan dosyalar için "oynat" simgeli gri kutu. */
    private fun placeholder(): android.graphics.Bitmap {
        val w = 320
        val h = 240
        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        c.drawColor(Theme.of(Store.dark).surface2)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Theme.of(Store.dark).mut
        val path = android.graphics.Path()
        path.moveTo(w * 0.4f, h * 0.3f)
        path.lineTo(w * 0.4f, h * 0.7f)
        path.lineTo(w * 0.68f, h * 0.5f)
        path.close()
        c.drawPath(path, p)
        return bm
    }

    private fun uriOf(path: String): Uri =
        FileProvider.getUriForFile(this, "$packageName.files", File(path))

    private fun openFile(t: DownloadTask) {
        val p = t.path ?: return
        try {
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("path", p)
            i.putExtra("title", t.title)
            startActivity(i)
        } catch (_: Throwable) {
        }
    }

    private fun shareFile(t: DownloadTask) {
        val p = t.path ?: return
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "video/*"
            i.putExtra(Intent.EXTRA_STREAM, uriOf(p))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, null))
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    }
}

/** Bölümlü ilerleme çubuğu: dolgu + 4 eşit parçanın sınırında küçük işaretler. */
private class SegBar(
    ctx: android.content.Context, val trackC: Int, val fillC: Int, val frac: Float
) : View(ctx) {
    private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val bh = 8f * d
        val top = (h - bh) / 2f
        pt.color = trackC
        c.drawRoundRect(0f, top, w, top + bh, bh / 2, bh / 2, pt)
        pt.color = fillC
        c.drawRoundRect(0f, top, maxOf(w * frac, bh), top + bh, bh / 2, bh / 2, pt)
        for (i in 1..3) {
            val x = w * i / 4f
            c.drawRect(x - 2f * d, h / 2 - 5f * d, x + 2f * d, h / 2 + 5f * d, pt)
        }
    }
}
