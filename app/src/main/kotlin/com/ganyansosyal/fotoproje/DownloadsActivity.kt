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

    private val bg get() = if (Store.dark) 0xFF202124.toInt() else 0xFFF1F3F4.toInt()
    private val card get() = if (Store.dark) 0xFF303134.toInt() else 0xFFFFFFFF.toInt()
    private val fg get() = if (Store.dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    private val sub get() = if (Store.dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    private val line get() = if (Store.dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
    private val accent = 0xFF1A73E8.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        setTheme(
            if (Store.dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(b)
        showDone = intent.getBooleanExtra("done", false)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(this)
        back.text = "‹"
        back.textSize = 30f
        back.setTextColor(fg)
        back.gravity = Gravity.CENTER
        back.setOnClickListener { finish() }
        top.addView(back, LinearLayout.LayoutParams(dp(52), dp(52)))
        val title = TextView(this)
        title.text = L.t("downloads")
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(fg)
        top.addView(title)
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabProg = tabView(L.t("progress")) { showDone = false; render() }
        tabDone = tabView(L.t("done")) { showDone = true; Downloads.markSeen(); render() }
        tabs.addView(tabProg, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(tabDone, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(44)))

        val sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(14), dp(8), dp(14), dp(20))
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
        t.setTextColor(if (on) accent else sub)
        t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun render() {
        style(tabProg, !showDone)
        style(tabDone, showDone)
        tabDone.text = L.t("done") + if (Downloads.unseen > 0) " (${Downloads.unseen})" else ""
        list.removeAllViews()
        if (!showDone) {
            if (Downloads.active.isEmpty()) empty(L.t("noActive")) else for (t in Downloads.active) list.addView(activeRow(t))
        } else {
            if (Downloads.done.isEmpty()) empty(L.t("noDownloads")) else for (t in Downloads.done) list.addView(doneRow(t))
        }
    }

    private fun empty(txt: String) {
        val t = TextView(this)
        t.text = txt
        t.setTextColor(sub)
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        list.addView(t, LinearLayout.LayoutParams(-1, dp(260)))
    }

    private fun cardBox(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(12), dp(12), dp(12))
        c.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(card)
            setStroke(dp(1), line)
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(5), 0, dp(5))
        c.layoutParams = lp
        return c
    }

    private fun action(txt: String, click: () -> Unit): TextView {
        val b = TextView(this)
        b.text = txt
        b.textSize = 14f
        b.setTextColor(accent)
        b.setPadding(dp(12), dp(8), dp(4), dp(8))
        b.setOnClickListener { click() }
        return b
    }

    private fun activeRow(t: DownloadTask): View {
        val c = cardBox()
        val failed = t.status == DlStatus.FAILED
        val paused = t.status == DlStatus.PAUSED
        val name = TextView(this)
        name.text = t.title
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setTextColor(fg)
        name.textSize = 16f
        c.addView(name)
        if (failed) {
            val e = TextView(this)
            e.text = L.t("failed") + ": " + (t.error ?: "")
            e.setTextColor(0xFFD93025.toInt())
            e.maxLines = 3
            e.textSize = 13f
            c.addView(e)
        } else {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            val p = t.progress
            pb.isIndeterminate = !paused && p < 0
            pb.max = 1000
            pb.progress = if (p < 0) 0 else (p.coerceIn(0.0, 1.0) * 1000).toInt()
            c.addView(pb, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
            val l = TextView(this)
            l.text = if (t.label.isEmpty()) L.t("queued") else t.label
            l.setTextColor(sub)
            l.textSize = 12f
            c.addView(l)
        }
        val row = LinearLayout(this)
        row.gravity = Gravity.END
        if (failed || paused) row.addView(action(L.t(if (failed) "retry" else "resume")) { Downloads.resume(t) })
        else if (t.canPause) row.addView(action(L.t("pause")) { Downloads.pause(t) })
        row.addView(action(L.t("cancel")) { if (failed) Downloads.removeFailed(t) else Downloads.cancel(t) })
        c.addView(row)
        return c
    }

    private fun doneRow(t: DownloadTask): View {
        val c = cardBox()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        // Küçük resim (videonun bir karesi)
        val thumb = android.widget.ImageView(this)
        thumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        thumb.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (Store.dark) 0xFF3C4043.toInt() else 0xFFE8EAED.toInt())
        }
        thumb.clipToOutline = true
        val p = t.path
        if (p != null) loadThumb(p, thumb)
        top.addView(thumb, LinearLayout.LayoutParams(dp(88), dp(64)).apply { rightMargin = dp(12) })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = t.title
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setTextColor(fg)
        name.textSize = 16f
        col.addView(name)
        val s = TextView(this)
        val sz = Media.fmtBytes(t.size)
        s.text = if (t.note == null) sz else "$sz · ${t.note}"
        s.setTextColor(sub)
        s.textSize = 12f
        col.addView(s)
        top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)

        val row = LinearLayout(this)
        row.gravity = Gravity.END
        row.addView(action(L.t("share")) { shareFile(t) })
        row.addView(action(L.t("delete")) { Downloads.deleteDone(t) })
        c.addView(row)
        c.setOnClickListener { openFile(t) }
        return c
    }

    private fun loadThumb(path: String, iv: android.widget.ImageView) {
        thumbCache[path]?.let { iv.setImageBitmap(it); return }
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
            thumbCache[path] = out
            runOnUiThread { iv.setImageBitmap(out) }
        }.start()
    }

    /** Kare alınamayan dosyalar için "oynat" simgeli gri kutu. */
    private fun placeholder(): android.graphics.Bitmap {
        val w = 320
        val h = 240
        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        c.drawColor(if (Store.dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = if (Store.dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
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
