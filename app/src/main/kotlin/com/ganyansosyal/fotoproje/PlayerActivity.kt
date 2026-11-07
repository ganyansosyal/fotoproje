package com.ganyansosyal.fotoproje

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.abs

/** Uygulama içi video oynatıcı: Media3 (ExoPlayer) + hareket kontrolleri + kaldığı yerden devam. */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var center: View
    private lateinit var playBtn: TextView
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tDur: TextView
    private lateinit var hud: TextView
    private lateinit var speedBtn: TextView
    private lateinit var subBtn: TextView

    private val accent = Color.parseColor("#FF8A3D")
    private val h = Handler(Looper.getMainLooper())
    private var path = ""
    private var isAudio = false
    private var barsVisible = true
    private var dragging = false
    private var subsOn = true
    private var hasSubs = false
    private var speedIdx = 1
    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var aspectIdx = 0
    private val aspects = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val aspectNames = arrayOf("Sığdır", "Doldur", "Uzat")

    // hareket durumu
    private var mode = 0 // 1 sar, 2 ses, 3 parlaklık
    private var startPos = 0L
    private var seekTarget = 0L
    private var startVol = 0
    private var startBr = 0.5f
    private var fast = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            h.postDelayed(this, 400)
        }
    }
    private val hider = Runnable { if (player?.isPlaying == true && !isAudio) showBars(false) }
    private val hudHider = Runnable { hud.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("fp_player", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun fmt(ms: Long): String {
        val s = (if (ms < 0) 0 else ms) / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%02d:%02d".format(mm, ss)
    }

    private fun label(text: String, size: Float = 18f, onClick: (() -> Unit)? = null): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.gravity = Gravity.CENTER
        t.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        if (onClick != null) t.setOnClickListener { onClick(); poke() }
        return t
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        path = intent.getStringExtra("path") ?: ""
        val title = intent.getStringExtra("title") ?: File(path).nameWithoutExtension
        if (path.isEmpty() || !File(path).exists()) {
            finish()
            return
        }
        isAudio = path.lowercase().endsWith(".m4a") || path.lowercase().endsWith(".mp3")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        pv = PlayerView(this)
        pv.useController = false
        pv.setShutterBackgroundColor(Color.BLACK)
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        // Dokunma katmanı (hareketler)
        val touch = View(this)
        root.addView(touch, FrameLayout.LayoutParams(-1, -1))
        val gd = GestureDetector(this, gestures())
        touch.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }

        // Üst çubuk
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(8), dp(10), dp(8), dp(24))
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
        val back = label("‹", 30f) { finish() }
        back.setPadding(dp(14), 0, dp(14), 0)
        val ttl = label(title, 16f)
        ttl.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        ttl.maxLines = 1
        ttl.ellipsize = android.text.TextUtils.TruncateAt.END
        subBtn = label("CC", 14f) { toggleSubs() }
        subBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
        speedBtn = label("1x", 14f) { cycleSpeed() }
        speedBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
        val asp = label("⤢", 20f) { cycleAspect() }
        asp.setPadding(dp(12), dp(4), dp(12), dp(4))
        topBar.addView(back)
        topBar.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(subBtn)
        topBar.addView(speedBtn)
        if (!isAudio) topBar.addView(asp)
        top = topBar
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Orta düğmeler
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.gravity = Gravity.CENTER
        val rew = label("↺10", 18f) { skip(-10000) }
        val fwd = label("10↻", 18f) { skip(10000) }
        playBtn = label("▶", 30f) { togglePlay() }
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(0x99000000.toInt())
        circle.setStroke(dp(2), accent)
        playBtn.background = circle
        mid.addView(rew, LinearLayout.LayoutParams(dp(64), dp(64)))
        mid.addView(playBtn, LinearLayout.LayoutParams(dp(78), dp(78)).also { it.setMargins(dp(24), 0, dp(24), 0) })
        mid.addView(fwd, LinearLayout.LayoutParams(dp(64), dp(64)))
        center = mid
        root.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Alt çubuk
        val bot = LinearLayout(this)
        bot.orientation = LinearLayout.HORIZONTAL
        bot.gravity = Gravity.CENTER_VERTICAL
        bot.setPadding(dp(14), dp(24), dp(14), dp(14))
        bot.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        tCur = label("00:00", 13f)
        tDur = label("00:00", 13f)
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        seek.secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
        seek.setPadding(dp(14), dp(10), dp(14), dp(10))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
                h.removeCallbacks(hider)
            }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                player?.seekTo(s.progress.toLong())
                poke()
            }
        })
        bot.addView(tCur)
        bot.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        bot.addView(tDur)
        bottom = bot
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Hareket bilgi balonu
        hud = label("", 22f)
        val hb = GradientDrawable()
        hb.cornerRadius = dp(18).toFloat()
        hb.setColor(0xAA000000.toInt())
        hud.background = hb
        hud.setPadding(dp(22), dp(12), dp(22), dp(12))
        hud.visibility = View.GONE
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).also { it.topMargin = dp(90) })

        setContentView(root)
        immersive()
        startPlayer()
    }

    private fun startPlayer() {
        val f = File(path)
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        for (ext in listOf("srt", "vtt")) {
            val sf = File(f.parentFile, f.nameWithoutExtension + "." + ext)
            if (sf.exists()) {
                subs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sf))
                        .setMimeType(if (ext == "srt") MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                        .setLanguage("tr")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }
        hasSubs = subs.isNotEmpty()
        subBtn.visibility = if (hasSubs) View.VISIBLE else View.GONE
        val item = MediaItem.Builder().setUri(Uri.fromFile(f)).setSubtitleConfigurations(subs).build()

        val p = ExoPlayer.Builder(this).build()
        player = p
        pv.player = p
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > v.height && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.text = if (isPlaying) "❚❚" else "▶"
                if (isPlaying) poke() else showBars(true)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    prefs().edit().remove(path).apply()
                    showBars(true)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                openExternal()
            }
        })
        val saved = prefs().getLong(path, 0L)
        p.prepare()
        if (saved > 5000) {
            p.seekTo(saved)
            hudShow("Devam: ${fmt(saved)}")
        }
        p.playWhenReady = true
        h.post(ticker)
    }

    private fun gestures() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showBars(!barsVisible)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isAudio) return true
            val fwd = e.x > root.width / 2f
            skip(if (fwd) 10000 else -10000)
            hudShow(if (fwd) "+10 sn ▶▶" else "◀◀ −10 sn")
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isAudio) return
            fast = true
            player?.setPlaybackSpeed(2f)
            hudShow("2x ▶▶", 100000)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val a = e1 ?: return false
            val p = player ?: return false
            if (fast) return false
            val w = root.width.toFloat()
            val hgt = root.height.toFloat()
            if (mode == 0) {
                if (abs(e2.x - a.x) > abs(e2.y - a.y)) {
                    mode = 1
                    startPos = p.currentPosition
                } else if (a.x < w / 2) {
                    mode = 3
                    val cur = window.attributes.screenBrightness
                    startBr = if (cur >= 0) cur else 0.5f
                } else {
                    mode = 2
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }
            when (mode) {
                1 -> {
                    val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                    val d = ((e2.x - a.x) / w * 120000f).toLong()
                    seekTarget = (startPos + d).coerceIn(0L, dur)
                    val sign = if (d >= 0) "+" else "−"
                    hudShow("${fmt(seekTarget)}  (${sign}${fmt(abs(d))})", 100000)
                }
                2 -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (startVol + (a.y - e2.y) / (hgt * 0.7f) * max).toInt().coerceIn(0, max)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    hudShow("Ses  ${v * 100 / max}%", 100000)
                }
                3 -> {
                    val br = (startBr + (a.y - e2.y) / (hgt * 0.7f)).coerceIn(0.02f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = br
                    window.attributes = lp
                    hudShow("Parlaklık  ${(br * 100).toInt()}%", 100000)
                }
            }
            return true
        }
    }

    private fun endGesture() {
        if (mode == 1) player?.seekTo(seekTarget)
        mode = 0
        if (fast) {
            fast = false
            player?.setPlaybackSpeed(speeds[speedIdx])
        }
        h.removeCallbacks(hudHider)
        h.postDelayed(hudHider, 700)
    }

    private fun hudShow(t: String, hideAfter: Long = 800) {
        hud.text = t
        hud.visibility = View.VISIBLE
        h.removeCallbacks(hudHider)
        h.postDelayed(hudHider, hideAfter)
    }

    private fun skip(ms: Long) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0L, dur))
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.isPlaying
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        player?.setPlaybackSpeed(speeds[speedIdx])
        speedBtn.text = speeds[speedIdx].toString().removeSuffix(".0") + "x"
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspects.size
        pv.resizeMode = aspects[aspectIdx]
        hudShow(aspectNames[aspectIdx])
    }

    private fun toggleSubs() {
        val p = player ?: return
        subsOn = !subsOn
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).build()
        subBtn.alpha = if (subsOn) 1f else 0.4f
        hudShow(if (subsOn) "Altyazı açık" else "Altyazı kapalı")
    }

    private fun showBars(show: Boolean) {
        barsVisible = show
        val v = if (show) 1f else 0f
        for (x in listOf(top, bottom, center)) {
            if (show) x.visibility = View.VISIBLE
            x.animate().alpha(v).setDuration(200).withEndAction {
                if (!barsVisible) x.visibility = View.GONE
            }.start()
        }
        h.removeCallbacks(hider)
        if (show) poke()
    }

    /** Çubukları 3 sn sonra gizlemek için sayacı yeniler. */
    private fun poke() {
        h.removeCallbacks(hider)
        h.postDelayed(hider, 3000)
    }

    private fun tick() {
        val p = player ?: return
        if (dragging) return
        val dur = if (p.duration > 0) p.duration else 0L
        seek.max = dur.toInt()
        seek.progress = p.currentPosition.toInt()
        seek.secondaryProgress = p.bufferedPosition.toInt()
        tCur.text = fmt(p.currentPosition)
        tDur.text = fmt(dur)
    }

    private fun savePos() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0 && pos > 5000 && pos < dur - 10000) prefs().edit().putLong(path, pos).apply()
        else prefs().edit().remove(path).apply()
    }

    /** Uygulama içinde oynatılamazsa sistemdeki başka oynatıcıya bırak. */
    private fun openExternal() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(
                FileProvider.getUriForFile(this, "$packageName.files", File(path)),
                if (isAudio) "audio/*" else "video/*"
            )
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (_: Throwable) {
        }
        finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isAudio && player?.isPlaying == true) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        if (inPip) {
            top.visibility = View.GONE
            bottom.visibility = View.GONE
            center.visibility = View.GONE
        } else {
            showBars(true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onPause() {
        savePos()
        val pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!pip) player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
