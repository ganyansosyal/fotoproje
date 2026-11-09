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
 * tam ekran karşılama paneli ("Kâğıt" tasarım sistemi, bkz. Theme.kt). Her site için uygulama açıldıktan sonra bir kez gösterilir.
 * Kapatmak için: "Başla" düğmesi, sağa kaydırma ya da geri tuşu.
 */
object SocialIntro {
    private class Brand(val name: String, val glyph: String)

    private val IG = Brand("Instagram", "IG")
    private val X = Brand("X", "X")
    private val FB = Brand("Facebook", "f")
    private val TT = Brand("TikTok", "♪")
    private val TH = Brand("Threads", "@")

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
        val th = Theme.of(Store.dark)
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
        ov.setBackgroundColor(th.bg)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(24), dp(24), dp(24))
        ov.addView(col, FrameLayout.LayoutParams(-1, -1))
        ov.setOnApplyWindowInsetsListener { _, ins ->
            col.setPadding(
                dp(24), maxOf(ins.systemWindowInsetTop, dp(24)),
                dp(24), maxOf(ins.systemWindowInsetBottom, dp(24))
            )
            ins
        }

        val items = ArrayList<View>()
        fun add(v: View, topGap: Int = 0, w: Int = -2) {
            col.addView(v, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(topGap) })
            items.add(v)
        }
        col.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))

        // Logo kutusu: ince çizgili, keskin köşeli
        val badge = FrameLayout(act)
        badge.background = rounded(th.surface, Theme.R, th.line, dp(1))
        badge.addView(tv(b.glyph, 30f, th.accent, true, true), FrameLayout.LayoutParams(-1, -1))
        col.addView(badge, LinearLayout.LayoutParams(dp(84), dp(84)))
        items.add(badge)

        add(tv(if (tr) "${b.name}'a hoş geldin" else "Welcome to ${b.name}", 26f, th.fg, true, true), 24)
        add(
            tv(
                if (tr) "Gez, kaydır, beğendiğin videoyu yakala." else "Scroll, explore, grab the videos you love.",
                15f, th.mut, false, true
            ), 8
        )

        fun row(icon: String, text: String): LinearLayout {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(16), dp(14), dp(16), dp(14))
            r.background = rounded(th.surface, Theme.R, th.line, dp(1))
            r.addView(tv(icon, 18f, th.accent, true, true), LinearLayout.LayoutParams(dp(30), -2))
            r.addView(tv(text, 15f, th.fg), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            return r
        }
        add(row("▶", if (tr) "Videoyu oynat" else "Play the video"), 30, -1)
        add(row("↓", if (tr) "İndir düğmesine dokun" else "Tap the download button"), 8, -1)
        add(row("✓", if (tr) "Galerine kaydedilsin" else "It lands in your gallery"), 8, -1)

        col.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))

        val go = tv(if (tr) "Başla" else "Let's go", 16f, th.onAccent, true, true)
        go.setPadding(0, dp(15), 0, dp(15))
        go.background = rounded(th.accent, Theme.R)
        go.setOnClickListener { dismiss() }
        add(go, 0, -1)
        add(
            tv(
                if (tr) "Sağa kaydırarak da kapatabilirsin" else "Swipe right to close",
                12f, th.mut, false, true
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
