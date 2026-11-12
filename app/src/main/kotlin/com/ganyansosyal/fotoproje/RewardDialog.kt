package com.ganyansosyal.fotoproje

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

object RewardDialog {

    private const val BG = 0xFFFFFFFF.toInt()
    private const val TEAL = 0xFF14B8A6.toInt()
    private const val TEAL_DARK = 0xFF0D9488.toInt()
    private const val TEXT_MAIN = 0xFF111C26.toInt()
    private const val TEXT_SUB = 0xFF5B6B78.toInt()
    private const val TEXT_MUTED = 0xFF8A98A5.toInt()

    fun show(
        activity: Activity,
        hak: Int = 5,
        tr: Boolean = true,
        onWatch: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Kart
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(28), px(24), px(16))
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = px(28).toFloat()
                setStroke(px(1), 0x22000000)
            }
        }

        // Ikon
        val icon = TextView(activity).apply {
            text = "⬇"
            textSize = 28f
            setTextColor(TEAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE6F7F5.toInt())
                setStroke(px(2), TEAL)
            }
            layoutParams = LinearLayout.LayoutParams(px(72), px(72))
        }
        root.addView(icon)

        // Baslik
        root.addView(TextView(activity).apply {
            text = if (tr) "İndirme hakkın bitti" else "No downloads left"
            textSize = 22f
            setTextColor(TEXT_MAIN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(18) }
        })

        // Alt yazi
        root.addView(TextView(activity).apply {
            text = if (tr) "Kısa bir reklam izle, indirmeye devam et." else "Watch a short ad to keep downloading."
            textSize = 15f
            setTextColor(TEXT_SUB)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
        })

        // Odul rozeti
        root.addView(TextView(activity).apply {
            text = if (tr) "🎁  +$hak indirme hakkı" else "🎁  +$hak downloads"
            textSize = 16f
            setTextColor(TEAL)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(18), px(10), px(18), px(10))
            background = GradientDrawable().apply {
                setColor(0x2214B8A6)
                cornerRadius = px(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(18) }
        })

        // Ana buton
        val btnBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(TEAL, TEAL_DARK)
        ).apply { cornerRadius = px(16).toFloat() }

        root.addView(TextView(activity).apply {
            text = if (tr) "▶   Reklam izle" else "▶   Watch ad"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), btnBg, null)
            isClickable = true
            setOnClickListener {
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                onWatch()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(54)
            ).apply { topMargin = px(24) }
        })

        // Vazgec
        root.addView(TextView(activity).apply {
            text = if (tr) "Vazgeç" else "Cancel"
            textSize = 15f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { dialog.cancel() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(48)
            ).apply { topMargin = px(4) }
        })

        dialog.setContentView(root)
        dialog.setOnCancelListener { onCancel() }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            val w = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Acilis animasyonu
        root.alpha = 0f
        root.scaleX = 0.9f
        root.scaleY = 0.9f
        dialog.show()
        root.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }
}
