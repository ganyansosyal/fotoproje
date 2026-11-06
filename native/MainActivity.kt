package com.ganyansosyal.fotoproje

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : FlutterActivity() {
    private lateinit var nat: MethodChannel

    companion object {
        /** Yerel tarayıcı ekranı (BrowserActivity) Dart tarafıyla bu kanaldan konuşur. */
        @JvmStatic
        var browser: MethodChannel? = null
    }
    private val ui = Handler(Looper.getMainLooper())

    // Ekranın aynı çözünürlükteki en yüksek yenileme hızını (90/120 Hz) ister.
    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            val d = if (Build.VERSION.SDK_INT >= 30) display
            else windowManager.defaultDisplay
            if (d == null) return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        maybeOpenNativeAtLaunch(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    /**
     * "Yerel tarayıcıyı ana ekran yap" ayarı (Flutter tarafında, SharedPreferences'ta)
     * açıksa, uygulama simgesinden başlatılınca Flutter beklenmeden yerel ekran açılır.
     * Koruma: yerel ekran açılışta çökerse bir sonraki başlatmada otomatik açma atlanır,
     * böylece kullanıcı Flutter ekranından ayarı kapatabilir.
     */
    private fun maybeOpenNativeAtLaunch(saved: Bundle?) {
        try {
            if (saved != null || intent?.action != Intent.ACTION_MAIN) return
            val fp = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            if (!fp.getBoolean("flutter.nativeDefault", false)) return
            val np = getSharedPreferences("fp_native", MODE_PRIVATE)
            if (np.getBoolean("boot_pending", false)) {
                np.edit().putBoolean("boot_pending", false).apply()
                return
            }
            np.edit().putBoolean("boot_pending", true).apply()
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("root", true))
        } catch (_: Throwable) {
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val msg = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(msg, "app/mux").setMethodCallHandler { call, result ->
            if (call.method == "mux") {
                val v = call.argument<String>("video")!!
                val a = call.argument<String>("audio")!!
                val o = call.argument<String>("out")!!
                Thread {
                    try {
                        mux(v, a, o)
                        runOnUiThread { result.success(true) }
                    } catch (e: Exception) {
                        runOnUiThread { result.error("mux", e.message, null) }
                    }
                }.start()
            } else {
                result.notImplemented()
            }
        }

        val br = MethodChannel(msg, "app/browser")
        br.setMethodCallHandler { call, result ->
            val b = BrowserActivity.current
            when (call.method) {
                "readBlob" -> {
                    if (b == null) {
                        result.error("blob", "Yerel tarayıcı açık değil", null)
                    } else {
                        b.readBlob(
                            call.argument<String>("url") ?: "",
                            call.argument<String>("path") ?: "",
                            call.argument<Number>("tab")?.toInt(),
                            result
                        )
                    }
                }
                else -> result.notImplemented()
            }
        }
        browser = br
        nat = MethodChannel(msg, "app/native")
        nat.setMethodCallHandler { call, result ->
            when (call.method) {
                "segments", "direct" -> startJob(call, result)
                "cancel" -> {
                    Native.cancel(call.argument<String>("id") ?: "")
                    result.success(true)
                }
                "blobWrite" -> blobWrite(call, result)
                "cacheCfg" -> {
                    // Dart ayarları yerel tarayıcının önbelleğine yazar (ilk açılışta hazır olsun)
                    try {
                        val e = getSharedPreferences("fp_native", MODE_PRIVATE).edit()
                        e.putBoolean("dark", call.argument<Boolean>("dark") ?: false)
                        e.putBoolean("blockAds", call.argument<Boolean>("blockAds") ?: true)
                        e.putString("engine", call.argument<String>("engine") ?: "https://www.google.com/search?q=")
                        e.putString("hook", call.argument<String>("hook") ?: "")
                        e.putString("noAuto", call.argument<String>("noAutoplay") ?: "")
                        val st = call.argument<Map<String, String>>("strings")
                        if (st != null) e.putString("strings", org.json.JSONObject(st).toString())
                        val bm = call.argument<List<String>>("bookmarks")
                        if (bm != null) e.putString("bookmarks", org.json.JSONArray(bm).toString())
                        e.apply()
                    } catch (_: Throwable) {
                    }
                    result.success(true)
                }
                "openNative" -> {
                    val u = call.argument<String>("url") ?: ""
                    val cur = BrowserActivity.current
                    if (cur != null) {
                        // Zaten açık (arka planda): yeni adresi sekme olarak ekle, öne getir
                        cur.openFromApp(u)
                        startActivity(
                            Intent(this, BrowserActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    } else {
                        startActivity(
                            Intent(this, BrowserActivity::class.java)
                                .putExtra("url", u)
                                .putExtra("hook", call.argument<String>("hook") ?: "")
                                .putExtra("noAutoplay", call.argument<String>("noAutoplay") ?: "")
                        )
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ------------------------------------------------ ilerleme (en çok ~150 ms'de bir)
    private class Throttle(val ui: Handler, val send: (Long, Long) -> Unit) {
        private var last = 0L
        private var la = 0L
        private var lb = 0L

        @Synchronized
        fun emit(a: Long, b: Long) {
            la = a
            lb = b
            val t = SystemClock.uptimeMillis()
            if (t - last < 150) return
            last = t
            ui.post { send(a, b) }
        }

        @Synchronized
        fun finish() {
            val a = la
            val b = lb
            ui.post { send(a, b) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun startJob(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id") ?: ""
        val kind = call.method
        val path = call.argument<String>("path")!!
        val headers = call.argument<Map<String, String>>("headers") ?: emptyMap()
        val conns = (call.argument<Number>("conns") ?: 10).toInt()
        val cancel = Native.register(id) // iptal bayrağı ana iş parçacığında hemen kaydedilir
        Native.fgStart(applicationContext)

        val th = Throttle(ui) { a, b ->
            nat.invokeMethod("p", mapOf("id" to id, "a" to a, "b" to b))
        }

        Thread {
            var code: String? = null
            var message: String? = null
            try {
                if (kind == "segments") {
                    Native.segments(
                        urls = call.argument<List<String>>("urls")!!,
                        init = call.argument<String>("init"),
                        path = path,
                        headers = headers,
                        keys = call.argument<List<String?>>("keys"),
                        ivs = call.argument<List<ByteArray?>>("ivs"),
                        seq = (call.argument<Number>("seq") ?: 0).toLong(),
                        conns = conns,
                        cancel = cancel,
                        progress = { a, b -> th.emit(a, b) }
                    )
                } else {
                    Native.direct(
                        url = call.argument<String>("url")!!,
                        path = path,
                        headers = headers,
                        conns = conns,
                        strict = call.argument<Boolean>("strict") ?: false,
                        cancel = cancel,
                        progress = { a, b -> th.emit(a, b) }
                    )
                }
                th.finish()
            } catch (e: CanceledEx) {
                code = "canceled"
                message = "canceled"
            } catch (e: HttpErr) {
                code = "http"
                message = e.message
            } catch (e: Throwable) {
                code = if (cancel.on) "canceled" else "err"
                message = e.message ?: e.toString()
            } finally {
                Native.unregister(id)
                Native.fgStop(applicationContext)
            }
            val c = code
            val m = message
            ui.post {
                if (c == null) result.success(true) else result.error(c, m, null)
            }
        }.start()
    }

    // ------------------------------------------------ 2) blob parçası yaz
    private fun blobWrite(call: MethodCall, result: MethodChannel.Result) {
        val data = call.argument<String>("data") ?: ""
        val path = call.argument<String>("path") ?: ""
        val append = call.argument<Boolean>("append") ?: false
        Thread {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                FileOutputStream(path, append).use { it.write(bytes) }
                ui.post { result.success(true) }
            } catch (e: Throwable) {
                ui.post { result.error("err", e.message, null) }
            }
        }.start()
    }

    // ------------------------------------------------ ses + görüntü birleştirici
    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    private fun mux(video: String, audio: String, out: String) {
        val ve = MediaExtractor()
        ve.setDataSource(video)
        val ae = MediaExtractor()
        ae.setDataSource(audio)
        val vt = pick(ve, "video")
        val at = pick(ae, "audio")
        if (vt < 0 || at < 0) throw Exception("track yok")
        ve.selectTrack(vt)
        ae.selectTrack(at)
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vi = muxer.addTrack(ve.getTrackFormat(vt))
        val ai = muxer.addTrack(ae.getTrackFormat(at))
        muxer.start()
        copy(ve, muxer, vi)
        copy(ae, muxer, ai)
        muxer.stop()
        muxer.release()
        ve.release()
        ae.release()
    }

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}
