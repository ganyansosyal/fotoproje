package com.ganyansosyal.fotoproje

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** "Link ile indir" alt sayfası: link yapıştır → YouTube ise video / ses seçenekleri. */
object LinkSheet {
    fun show(act: Activity, dark: Boolean, openOther: (String) -> Unit, autoUrl: String? = null) {
        Sheet(act, dark, openOther).start(autoUrl)
    }

    private fun tr(a: String, b: String) = if (L.code == "tr") a else b

    private fun isYt(u: String): Boolean {
        val x = u.lowercase()
        return x.contains("youtu.be/") ||
            (x.contains("youtube.com/") &&
                (x.contains("watch") || x.contains("/shorts/") || x.contains("/live/") || x.contains("/embed/")))
    }

    private class Sheet(val act: Activity, val dark: Boolean, val openOther: (String) -> Unit) {
        val bg = if (dark) 0xFF303134.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        val sub = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        val card = if (dark) 0xFF3C4043.toInt() else 0xFFF1F3F4.toInt()
        val line = if (dark) 0xFF5F6368.toInt() else 0xFFDADCE0.toInt()
        val acc = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        val accBg = if (dark) 0xFF2B3A55.toInt() else 0xFFE8F0FE.toInt()
        val onAcc = if (dark) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
        val red = 0xFFD93025.toInt()

        val dlg = Dialog(act)
        val box = LinearLayout(act)

        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

        fun rounded(fill: Int, r: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(r).toFloat()
                if (sw > 0) setStroke(dp(sw), stroke)
            }

        fun label(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(act).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        fun gap(h: Int): View = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }

        fun button(s: String, onClick: () -> Unit): TextView = TextView(act).apply {
            text = s
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(onAcc)
            background = rounded(acc, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener { onClick() }
        }

        fun start(autoUrl: String?) {
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val root = LinearLayout(act)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(20), dp(10), dp(20), dp(20))
            root.background = GradientDrawable().apply {
                setColor(bg)
                val r = dp(22).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            val handle = View(act)
            handle.background = rounded(line, 2)
            val hp = LinearLayout.LayoutParams(dp(36), dp(4))
            hp.gravity = Gravity.CENTER_HORIZONTAL
            hp.bottomMargin = dp(14)
            root.addView(handle, hp)
            box.orientation = LinearLayout.VERTICAL
            root.addView(box)
            dlg.setContentView(root)
            dlg.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            if (autoUrl != null) {
                showLoading()
                dlg.show()
                find(autoUrl)
            } else {
                showInput(clipLink(), null)
                dlg.show()
            }
        }

        fun clipLink(): String {
            return try {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (t.startsWith("http://") || t.startsWith("https://")) t else ""
            } catch (_: Throwable) {
                ""
            }
        }

        fun clipAny(): String {
            return try {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        fun showInput(prefill: String, err: String?) {
            box.removeAllViews()
            box.addView(label(tr("Link ile indir", "Download from link"), 22f, fg, true))
            box.addView(gap(4))
            box.addView(
                label(
                    tr("Linki yapıştır, videoyu bulalım.", "Paste a link and we'll find the video."),
                    14f, sub
                )
            )
            box.addView(gap(18))

            // Arama çubuğu: [🔎  link ............ Yapıştır/✕]
            val pill = LinearLayout(act)
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER_VERTICAL
            pill.background = rounded(card, 28, if (err != null) red else line, 1)
            pill.setPadding(dp(18), 0, dp(8), 0)
            pill.addView(label("\uD83D\uDD0E", 17f, sub))

            val et = EditText(act)
            et.setText(prefill)
            et.hint = tr("youtube.com/watch?v=…", "youtube.com/watch?v=…")
            et.setHintTextColor(sub)
            et.setTextColor(fg)
            et.textSize = 15f
            et.isSingleLine = true
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            et.imeOptions = EditorInfo.IME_ACTION_GO
            et.background = null
            et.setPadding(dp(12), dp(16), dp(8), dp(16))
            pill.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val act2 = TextView(act)
            act2.textSize = 13f
            act2.typeface = Typeface.DEFAULT_BOLD
            act2.gravity = Gravity.CENTER
            act2.setTextColor(acc)
            act2.background = rounded(accBg, 20)
            act2.setPadding(dp(14), 0, dp(14), 0)
            fun refresh() {
                act2.text = if (et.text.isNullOrEmpty()) tr("Yapıştır", "Paste") else "\u2715"
            }
            refresh()
            act2.setOnClickListener {
                if (et.text.isNullOrEmpty()) et.setText(clipAny()) else et.setText("")
                et.setSelection(et.text.length)
            }
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refresh()
            })
            pill.addView(act2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            box.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

            et.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) {
                    find(et.text.toString())
                    true
                } else false
            }
            if (err != null) {
                box.addView(gap(8))
                box.addView(label(err, 13f, red))
            }
            box.addView(gap(16))
            box.addView(button(tr("Videoyu bul", "Find video")) { find(et.text.toString()) })
            box.addView(gap(12))
            val hint = label(
                tr(
                    "YouTube, Shorts ve canlı yayın linkleri desteklenir. Diğer siteler tarayıcıda açılır.",
                    "YouTube, Shorts and live links are supported. Other sites open in the browser."
                ), 12f, sub
            )
            hint.gravity = Gravity.CENTER
            box.addView(hint)
            if (prefill.isEmpty()) {
                dlg.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                et.requestFocus()
            }
        }

        fun showLoading() {
            box.removeAllViews()
            box.addView(label(tr("Video aranıyor…", "Looking for the video…"), 16f, fg, true))
            box.addView(gap(6))
            box.addView(label(tr("Birkaç saniye sürebilir.", "This can take a few seconds."), 13f, sub))
            box.addView(gap(12))
        }

        fun find(raw: String) {
            val s = raw.trim()
            if (s.isEmpty()) {
                showInput("", tr("Önce bir link yapıştır.", "Paste a link first."))
                return
            }
            val url = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
            if (!isYt(url)) {
                dlg.dismiss()
                openOther(url)
                Toast.makeText(
                    act,
                    tr("Sayfa açıldı. Video bulununca indirme düğmesi yanar.", "Page opened. The download button lights up when a video is found."),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            showLoading()
            Thread {
                try {
                    val info = YouTube.info(url)
                    act.runOnUiThread { if (dlg.isShowing) showOptions(info) }
                } catch (_: Throwable) {
                    act.runOnUiThread {
                        if (dlg.isShowing) showInput(
                            s, tr("Video bulunamadı. Linki kontrol et.", "Video not found. Check the link.")
                        )
                    }
                }
            }.start()
        }

        fun fmtDur(sec: Long): String {
            if (sec <= 0) return ""
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }

        fun showOptions(info: YouTube.Info) {
            box.removeAllViews()
            val title = label(info.title, 18f, fg, true)
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            box.addView(title)
            val d = fmtDur(info.duration)
            box.addView(gap(4))
            box.addView(label(if (d.isEmpty()) "YouTube" else "$d  ·  YouTube", 13f, sub))
            box.addView(gap(16))

            val modes = ArrayList<String>()
            val names = ArrayList<String>()
            val vCount: Int
            for (h in info.heights) {
                modes.add("v$h")
                names.add("${h}p")
            }
            vCount = modes.size
            if (info.hasAudio) {
                modes.add("m")
                names.add("MP3")
                modes.add("a")
                names.add("M4A")
            }
            var sel = 0
            val chips = ArrayList<TextView>()
            fun paint() {
                for (i in chips.indices) {
                    val on = i == sel
                    chips[i].background = if (on) rounded(accBg, 14, acc, 2) else rounded(card, 14)
                    chips[i].setTextColor(if (on) acc else fg)
                    chips[i].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            }
            fun grid(from: Int, to: Int) {
                var i = from
                while (i < to) {
                    val row = LinearLayout(act)
                    row.orientation = LinearLayout.HORIZONTAL
                    for (c in 0 until 3) {
                        val idx = i + c
                        val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                        if (idx < to) {
                            val t = TextView(act)
                            t.text = names[idx]
                            t.textSize = 15f
                            t.gravity = Gravity.CENTER
                            t.setOnClickListener {
                                sel = idx
                                paint()
                            }
                            chips.add(t)
                            row.addView(t, lp)
                        } else {
                            row.addView(View(act), lp)
                        }
                    }
                    val rp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    rp.setMargins(-dp(4), 0, -dp(4), 0)
                    box.addView(row, rp)
                    i += 3
                }
            }
            if (vCount > 0) {
                box.addView(label(tr("VİDEO", "VIDEO"), 11f, sub, true))
                box.addView(gap(4))
                grid(0, vCount)
                box.addView(gap(10))
            }
            if (modes.size > vCount) {
                box.addView(label(tr("SES", "AUDIO"), 11f, sub, true))
                box.addView(gap(4))
                grid(vCount, modes.size)
                box.addView(gap(10))
            }
            paint()
            box.addView(gap(6))
            box.addView(button(tr("İndir", "Download")) {
                val mode = modes[sel]
                dlg.dismiss()
                Thread {
                    val err = try {
                        Downloads.startYoutube(info.url, info.title, mode)
                    } catch (e: Throwable) {
                        e.message ?: e.toString()
                    }
                    act.runOnUiThread {
                        Toast.makeText(act, err ?: L.t("downloadStarted"), Toast.LENGTH_SHORT).show()
                    }
                }.start()
            })
        }
    }
}
