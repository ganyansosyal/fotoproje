package com.ganyansosyal.fotoproje

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.widget.TextView

/** Alt çubuk için çizgi (outline) ikon. Rengi metin rengiyle (tema) aynıdır; 24x24 ızgarada çizilir. */
class VecIcon(ctx: Context, private val kind: Int, private val sizeDp: Float = 28f) : TextView(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    companion object {
        const val DOWNLOAD = 1
        const val SETTINGS = 2
        const val HOME = 3
        const val BACK = 4
        const val FORWARD = 5
    }

    override fun onDraw(c: Canvas) {
        p.color = currentTextColor
        val s = sizeDp * d / 24f
        p.strokeWidth = 2f * s
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)
        p.strokeWidth = 2f
        when (kind) {
            DOWNLOAD -> {
                c.drawCircle(12f, 12f, 9f, p)
                path.reset()
                path.moveTo(12f, 7f); path.lineTo(12f, 15f)
                path.moveTo(8.5f, 12f); path.lineTo(12f, 15.5f); path.lineTo(15.5f, 12f)
                c.drawPath(path, p)
            }
            HOME -> {
                path.reset()
                path.moveTo(5f, 12f); path.lineTo(3f, 12f); path.lineTo(12f, 3f); path.lineTo(21f, 12f); path.lineTo(19f, 12f)
                path.moveTo(5f, 12f); path.lineTo(5f, 19f)
                path.cubicTo(5f, 20.1f, 5.9f, 21f, 7f, 21f); path.lineTo(17f, 21f)
                path.cubicTo(18.1f, 21f, 19f, 20.1f, 19f, 19f); path.lineTo(19f, 12f)
                path.moveTo(9f, 21f); path.lineTo(9f, 15f)
                path.cubicTo(9f, 13.9f, 9.9f, 13f, 11f, 13f); path.lineTo(13f, 13f)
                path.cubicTo(14.1f, 13f, 15f, 13.9f, 15f, 15f); path.lineTo(15f, 21f)
                c.drawPath(path, p)
            }
            BACK -> {
                path.reset()
                path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f)
                c.drawPath(path, p)
            }
            FORWARD -> {
                path.reset()
                path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f)
                c.drawPath(path, p)
            }
            SETTINGS -> {
                c.drawCircle(12f, 12f, 5.5f, p)
                c.drawCircle(12f, 12f, 1.5f, p)
                path.reset()
                for (i in 0 until 8) {
                    val a = Math.PI * 2 * i / 8
                    val cs = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    path.moveTo(12f + 7.6f * cs, 12f + 7.6f * sn)
                    path.lineTo(12f + 10.2f * cs, 12f + 10.2f * sn)
                }
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}
