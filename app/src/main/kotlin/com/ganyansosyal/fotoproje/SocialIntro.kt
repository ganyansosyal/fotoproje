package com.ganyansosyal.fotoproje

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sosyal medya (Instagram / X / Facebook / TikTok / Threads) açılınca sağdan kayarak gelen
 * tam ekran karşılama paneli. Her site için uygulama açıldıktan sonra bir kez gösterilir.
 * Kapatmak için: "Başla" düğmesi, sağa kaydırma ya da geri tuşu.
 */
object SocialIntro {
    private class Brand(val name: String, val glyph: String, val colors: IntArray, val accent: Int)

    private val IG = Brand(
        "Instagram", "IG",
        intArrayOf(0xFF833AB4.toInt(), 0xFFFD1D1D.toInt(), 0xFFFCB045.toInt()), 0xFFC13584.toInt()
    )
    private val X = Brand(
        "X", "X",
        intArrayOf(0xFF000000.toInt(), 0xFF0B1620.toInt(), 0xFF12344D.toInt()), 0xFF1D9BF0.toInt()
    )
    private val FB = Brand(
        "Facebook", "f",
        intArrayOf(0xFF1877F2.toInt(), 0xFF0F4FB8.toInt(), 0xFF0A1F4D.toInt()), 0xFF1877F2.toInt()
    )
    private val TT = Brand(
        "TikTok", "♪",
        intArrayOf(0xFF010101.toInt(), 0xFF1B0F2E.toInt(), 0xFF0E3B44.toInt()), 0xFFFE2C55.toInt()
    )
    private val TH = Brand(
        "Threads", "@",
        intArrayOf(0xFF000000.toInt(), 0xFF171717.toInt(), 0xFF2B2B2B.toInt()), 0xFF101010.toInt()
    )

    private val shown = HashSet<String>()
    private var view: View? = null
    private val anims = ArrayList<Animator>()

    val isShowing: Boolean get() = view != null

    private fun brandFor(url: String?): Brand? {
        val h = try { java.net.URI(url ?: return null).host?.lowercase() ?: return null } catch (_: Throwable) { return null }
        fun d(x: String) = h == x || h.endsWith(".$x")
        return when {
            d("instagram.com") -> IG
            d("x.com") || d("twitter.com") -> X
            d("facebook.com") || d("fb.com") || d("fb.watch") -> FB
            d("tiktok.com") -> TT
            d("threads.net") || d("threads.com") -> TH
            else -> null
        }
    }

    /** Sosyal medya adresiyse (ve bu uygulama açılışında henüz gösterilmediyse) paneli açar. */
    fun maybeShow(act: Activity, url: String?) {
        if (view != null || act.isFinishing) return
        val b = brandFor(url) ?: return
        if (!shown.add(b.name)) return
        try { show(act, b) } catch (_: Throwable) { view = null }
    }

    private fun show(act: Activity, b: Brand) {
        val tr = L.code == "tr"
        val dm = act.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val screenW = dm.widthPixels
        val host = act.findViewById<ViewGroup>(android.R.id.content) ?: return

        fun rounded(fill: Int, r: Int, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(r).toFloat(); if (sw > 0) setStroke(sw, stroke)
        }

        fun tv(s: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(act).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
        }

        val ov = FrameLayout(act)
        ov.isClickable = true
        ov.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, b.colors)

        // Arka planda yavaşça süzülen ışık halkaları
        fun blob(size: Int, x: Int, y: Int, a: Int, dx: Float, dy: Float, ms: Long) {
            val v = View(act)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(a, 255, 255, 255)) }
            ov.addView(v, FrameLayout.LayoutParams(dp(size), dp(size)))
            v.translationX = dp(x).toFloat(); v.translationY = dp(y).toFloat()
            val ax = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, dp(x).toFloat(), dp(x).toFloat() + dx * dm.density)
            val ay = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, dp(y).toFloat(), dp(y).toFloat() + dy * dm.density)
            for (an in listOf(ax, ay)) {
                an.duration = ms; an.repeatMode = ValueAnimator.REVERSE; an.repeatCount = ValueAnimator.INFINITE
                an.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                anims.add(an); an.start()
            }
        }
        blob(280, -90, -60, 38, 60f, 90f, 7000)
        blob(220, 220, 520, 30, -80f, -70f, 8500)
        blob(140, 250, 120, 24, -40f, 60f, 6000)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(28), dp(24), dp(28), dp(24))
        ov.addView(col, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
        ov.setOnApplyWindowInsetsListener { _, ins ->
            col.setPadding(
                dp(28), maxOf(ins.systemWindowInsetTop, dp(24)),
                dp(28), maxOf(ins.systemWindowInsetBottom, dp(24))
            )
            ins
        }

        val items = ArrayList<View>()
        fun add(v: View, topGap: Int = 0, w: Int = -2) {
            col.addView(v, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(topGap) })
            items.add(v)
        }

        val spacerTop = View(act)
        col.addView(spacerTop, LinearLayout.LayoutParams(0, 0, 1f))

        // Cam görünümlü logo dairesi
        val badge = FrameLayout(act)
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0x33FFFFFF); setStroke(dp(2), 0x66FFFFFF)
        }
        badge.addView(tv(b.glyph, 38f, Color.WHITE, true, true), FrameLayout.LayoutParams(-1, -1))
        col.addView(badge, LinearLayout.LayoutParams(dp(104), dp(104)))
        items.add(badge)

        add(tv(if (tr) "${b.name}'a hoş geldin" else "Welcome to ${b.name}", 29f, Color.WHITE, true, true), 26)
        add(
            tv(
                if (tr) "Gez, kaydır, beğendiğin videoyu yakala." else "Scroll, explore, grab the videos you love.",
                16f, 0xCCFFFFFF.toInt(), false, true
            ), 8
        )

        fun row(icon: String, text: String): LinearLayout {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(18), dp(14), dp(18), dp(14))
            r.background = rounded(0x26FFFFFF, 20, 0x33FFFFFF, dp(1))
            r.addView(tv(icon, 20f, Color.WHITE, true, true), LinearLayout.LayoutParams(dp(34), -2))
            r.addView(tv(text, 15.5f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            return r
        }
        add(row("▶", if (tr) "Videoyu oynat" else "Play the video"), 30, -1)
        add(row("↓", if (tr) "İndir düğmesine dokun" else "Tap the download button"), 10, -1)
        add(row("✓", if (tr) "Galerine kaydedilsin" else "It lands in your gallery"), 10, -1)

        val spacerMid = View(act)
        col.addView(spacerMid, LinearLayout.LayoutParams(0, 0, 1f))

        val go = tv(if (tr) "Başla" else "Let's go", 17f, b.accent, true, true)
        go.setPadding(0, dp(16), 0, dp(16))
        go.background = rounded(Color.WHITE, 30)
        go.elevation = dp(6).toFloat()
        go.setOnClickListener { dismiss() }
        add(go, 0, -1)
        add(
            tv(
                if (tr) "Sağa kaydırarak da kapatabilirsin" else "Swipe right to close",
                12f, 0x99FFFFFF.toInt(), false, true
            ), 12
        )

        // Sağa kaydırınca kapanır
        var downX = 0f
        var drag = false
        ov.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (dx > dp(8)) { drag = true; v.translationX = dx }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drag && v.translationX > screenW * 0.3f) dismiss()
                    else v.animate().translationX(0f).setDuration(180).start()
                    drag = false
                }
            }
            true
        }

        host.addView(ov, FrameLayout.LayoutParams(-1, -1))
        view = ov
        ov.requestApplyInsets()

        // Sağdan kayarak gelir, içerik sırayla yukarı süzülür
        ov.translationX = screenW.toFloat()
        for (v in items) { v.alpha = 0f; v.translationY = dp(28).toFloat() }
        ov.animate().translationX(0f).setDuration(430).setInterpolator(DecelerateInterpolator(2.2f)).start()
        items.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f).setStartDelay(180L + i * 75L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        val w = v.resources.displayMetrics.widthPixels.toFloat()
        v.animate().translationX(w).setDuration(280).withEndAction {
            for (a in anims) a.cancel()
            anims.clear()
            (v.parent as? ViewGroup)?.removeView(v)
        }.start()
    }
}
