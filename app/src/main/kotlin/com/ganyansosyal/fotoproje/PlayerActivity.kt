package com.ganyansosyal.fotoproje

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File

/** Uygulama içi video oynatıcı (İndirmeler > Bitti listesinden açılır). */
class PlayerActivity : Activity() {
    private lateinit var video: VideoView
    private var path = ""
    private var pos = 0
    private var triedPath = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        path = intent.getStringExtra("path") ?: ""
        pos = b?.getInt("pos") ?: 0
        val title = intent.getStringExtra("title") ?: ""
        if (path.isEmpty() || !File(path).exists()) {
            finish()
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        val ctrl = MediaController(this)
        ctrl.setAnchorView(root)
        video.setMediaController(ctrl)

        // Geri düğmesi + başlık
        val back = TextView(this)
        back.text = "‹  " + title
        back.setTextColor(Color.WHITE)
        back.textSize = 18f
        back.maxLines = 1
        back.ellipsize = android.text.TextUtils.TruncateAt.END
        back.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        back.setPadding(dp(16), dp(14), dp(16), dp(14))
        back.setOnClickListener { finish() }
        root.addView(back, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        video.setOnPreparedListener { mp ->
            // Yatay videoyu yatay göster
            if (mp.videoWidth > mp.videoHeight) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (pos > 0) video.seekTo(pos)
            video.start()
        }
        video.setOnCompletionListener { ctrl.show(0) }
        video.setOnErrorListener { _, _, _ ->
            if (!triedPath) {
                // İçerik adresi olmadı: doğrudan dosya yolunu dene
                triedPath = true
                try {
                    video.setVideoPath(path)
                    return@setOnErrorListener true
                } catch (_: Throwable) {
                }
            }
            openExternal()
            true
        }
        setContentView(root)
        immersive()
        try {
            video.setVideoURI(FileProvider.getUriForFile(this, "$packageName.files", File(path)))
        } catch (_: Throwable) {
            video.setVideoPath(path)
        }
    }

    /** Uygulama içinde oynatılamazsa sistemdeki başka oynatıcıya bırak. */
    private fun openExternal() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(
                FileProvider.getUriForFile(this, "$packageName.files", File(path)),
                if (path.endsWith(".m4a")) "audio/*" else "video/*"
            )
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: Throwable) {
        }
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onPause() {
        if (::video.isInitialized) {
            pos = video.currentPosition
            video.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("pos", pos)
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
