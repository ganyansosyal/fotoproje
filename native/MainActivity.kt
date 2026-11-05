package com.ganyansosyal.fotoproje

import android.Manifest
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
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
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

        nat = MethodChannel(msg, "app/native")
        nat.setMethodCallHandler { call, result ->
            when (call.method) {
                "segments", "direct" -> startJob(call, result)
                "cancel" -> {
                    Native.cancel(call.argument<String>("id") ?: "")
                    result.success(true)
                }
                "blobWrite" -> blobWrite(call, result)
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
