package com.ganyansosyal.fotoproje

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Kenardan sürükleyince geri / ileri (Chrome tarzı beyaz yay göstergeli).
 * Sol kenardan sağa çek = geri, sağ kenardan sola çek = ileri.
 */
class SwipeNav(ctx: Context) : FrameLayout(ctx) {
    var canBack: () -> Boolean = { false }
    var canFwd: () -> Boolean = { false }
    var onBack: () -> Unit = {}
    var onFwd: () -> Unit = {}
    var allowed: () -> Boolean = { true }
    var isDark: () -> Boolean = { true }

    private val d = resources.displayMetrics.density
    private val edge = 24 * d
    private val trigger = 64 * d
    private val maxPull = 96 * d
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var side = 0 // -1 sol kenar (geri), 1 sağ kenar (ileri)
    private var downX = 0f
    private var downY = 0f
    private var curY = 0f
    private var pull = 0f
    private var dragging = false
    private var armed = false
    private var anim: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    init { setWillNotDraw(false) }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (Build.VERSION.SDK_INT >= 29) {
            // Sistem geri hareketi ile çakışmasın: kenarların orta bandını bize ayır.
            val h = b - t
            val band = minOf(h, (200 * d).toInt())
            val top = (h - band) / 2
            val w = (edge).toInt()
            systemGestureExclusionRects = listOf(
                Rect(0, top, w, top + band),
                Rect(r - l - w, top, r - l, top + band)
            )
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel(); pull = 0f; dragging = false; side = 0
                if (allowed()) {
                    if (e.x < edge && canBack()) side = -1
                    else if (e.x > width - edge && canFwd()) side = 1
                }
                downX = e.x; downY = e.y; curY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (side != 0 && !dragging) {
                val p = if (side < 0) e.x - downX else downX - e.x
                val dy = Math.abs(e.y - downY)
                // Yalnızca belirgin yatay çekiş geri/ileri sayılır; dikey kayma hemen iptal eder
                if (p > slop * 2 && p > dy * 2.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (dy > slop) side = 0
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> side = 0
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val p = if (side < 0) e.x - downX else downX - e.x
                pull = p.coerceIn(0f, maxPull)
                curY = e.y
                val a = pull >= trigger
                if (a && !armed) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                armed = a
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val fire = pull >= trigger
                val s = side
                dragging = false; side = 0; armed = false
                release()
                if (fire) { if (s < 0) onBack() else onFwd() }
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; side = 0; armed = false; release() }
        }
        return true
    }

    private fun release() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pull, 0f).apply {
            duration = 180
            addUpdateListener { pull = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (pull <= 0f || side == 0 && !dragging && anim?.isRunning != true) return
        val left = lastSide < 0
        val dark = isDark()
        fill.color = if (dark) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()
        stroke.color = if (dark) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val h = 52 * d + pull * 0.35f
        val cy = curY.coerceIn(h, height - h)
        val w = pull
        if (left) oval.set(-w, cy - h, w, cy + h) else oval.set(width - w, cy - h, width + w, cy + h)
        c.drawOval(oval, fill)
        // ok
        val prog = (pull / trigger).coerceIn(0f, 1f)
        stroke.alpha = (255 * (0.35f + 0.65f * prog)).toInt()
        val ax = if (left) w * 0.45f else width - w * 0.45f
        val dir = if (left) -1f else 1f // sol: ←, sağ: →
        val s = 7 * d
        c.drawLine(ax - dir * s, cy, ax + dir * s, cy, stroke)
        c.drawLine(ax + dir * s, cy, ax + dir * s - dir * s * 0.8f, cy - s * 0.8f, stroke)
        c.drawLine(ax + dir * s, cy, ax + dir * s - dir * s * 0.8f, cy + s * 0.8f, stroke)
    }

    private var lastSide = -1
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (side != 0) lastSide = side
        return super.dispatchTouchEvent(e)
    }
}
