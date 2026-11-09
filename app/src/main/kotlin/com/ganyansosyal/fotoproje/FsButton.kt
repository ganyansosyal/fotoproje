package com.ganyansosyal.fotoproje

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Yüzen "Tam ekran" hapı. Instagram / YouTube / X / Facebook açılınca sağdan kayarak gelir,
 * yazısı birkaç saniye sonra kapanır ve yuvarlak bir düğme olarak kalır.
 * Tam ekrandayken aynı düğme "çıkış" olur.
 */
class FsButton(act: Activity, private val onTap: () -> Unit) : LinearLayout(act) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val icon = VecIcon(act, VecIcon.MAXIMIZE, 20f)
    private val label = TextView(act)
    private val h = Handler(Looper.getMainLooper())
    private var labelW = 0
    private var anim: ValueAnimator? = null
    private val collapseRun = Runnable { setLabel(false, true) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Theme.LIGHT.accent)
        }
        elevation = 6f * d
        alpha = 0.85f
        icon.setTextColor(Color.WHITE)
        icon.gravity = Gravity.CENTER
        addView(icon, LayoutParams(dp(40), dp(40)))
        label.textSize = 12f
        label.typeface = Typeface.DEFAULT_BOLD
        label.setTextColor(Color.WHITE)
        label.maxLines = 1
        label.setPadding(0, 0, dp(14), 0)
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
        isClickable = true
        contentDescription = L.t("fullScreen")
        setOnClickListener { onTap() }
        setFull(false)
    }

    private fun measureLabel() {
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        labelW = label.measuredWidth
    }

    private fun setLabel(open: Boolean, animate: Boolean) {
        anim?.cancel()
        val target = if (open) labelW else 0
        val from = label.layoutParams.width
        alpha = if (open) 1f else 0.85f
        if (!animate) { label.layoutParams.width = target; label.requestLayout(); return }
        anim = ValueAnimator.ofInt(from, target).apply {
            duration = 320
            addUpdateListener {
                label.layoutParams.width = it.animatedValue as Int
                label.requestLayout()
            }
            start()
        }
    }

    /** Tam ekran / çıkış görünümü. */
    fun setFull(on: Boolean) {
        icon.kind = if (on) VecIcon.MINIMIZE else VecIcon.MAXIMIZE
        label.text = L.t(if (on) "exitFull" else "fullScreen")
        contentDescription = label.text
        measureLabel()
    }

    /** Sağdan kayarak gelir, yazı açık; 3,6 sn sonra yalnızca yuvarlak düğme kalır. */
    fun slideIn() {
        h.removeCallbacks(collapseRun)
        animate().cancel()
        visibility = View.VISIBLE
        setLabel(true, false)
        translationX = dp(190).toFloat()
        animate().translationX(0f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
        h.postDelayed(collapseRun, 3600)
    }

    /** Animasyonsuz göster (aynı sitede gezinirken / sekme değişince). */
    fun showQuiet() {
        if (visibility == View.VISIBLE) return
        animate().cancel()
        translationX = 0f
        setLabel(false, false)
        visibility = View.VISIBLE
    }

    /** Yazıyı kısa süre aç (tam ekrana girince "çık" yazısı görünsün). */
    fun flash() {
        h.removeCallbacks(collapseRun)
        setLabel(true, true)
        h.postDelayed(collapseRun, 1800)
    }

    fun hideNow() {
        h.removeCallbacks(collapseRun)
        animate().cancel()
        visibility = View.GONE
    }
}
