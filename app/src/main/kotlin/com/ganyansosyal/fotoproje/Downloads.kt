package com.ganyansosyal.fotoproje

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

enum class DlStatus { RUNNING, PAUSED, DONE, FAILED, CANCELED }

class DownloadTask(val id: String, val url: String, val referer: String, var title: String) {
    var createdAt = System.currentTimeMillis()
    @Volatile var status = DlStatus.RUNNING
    @Volatile var path: String? = null
    @Volatile var error: String? = null
    @Volatile var note: String? = null
    @Volatile var size = 0L
    @Volatile var progress = 0.0 // 0..1, <0: belirsiz
    @Volatile var label = ""
    @Volatile var cancel = CancelFlag()
    @Volatile var pausing = false
    @Volatile var canPause = true
    var body: (() -> Unit)? = null
    var kind: String? = null   // mp4 / m3u8 / mpd / blob
    var yt = false             // YouTube görevi mi
}

/** blob: içeriğini sayfadan okuyan yerel ekranın sonucu. */
interface BlobRes {
    fun ok()
    fun fail(msg: String)
}

/** İndirme yöneticisi (eski download_manager.dart). */
object Downloads {
    val active = CopyOnWriteArrayList<DownloadTask>()
    val done = CopyOnWriteArrayList<DownloadTask>()
    @Volatile var unseen = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private var lastNotify = 0L
    private val tick = Runnable { fire() }

    private val ctx: Context get() = Store.app
    private val prefs get() = ctx.getSharedPreferences("fp_dl", Context.MODE_PRIVATE)

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun fire() {
        lastNotify = System.currentTimeMillis()
        for (l in listeners) try { l() } catch (_: Throwable) {}
    }

    /** Arayüzü bildirir (ilerleme için en çok ~5 kez/sn). */
    private fun notifyUi(force: Boolean = false) {
        if (force) {
            ui.removeCallbacks(tick)
            ui.post(tick)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify >= 200) {
            ui.removeCallbacks(tick)
            ui.post(tick)
        } else {
            ui.removeCallbacks(tick)
            ui.postDelayed(tick, 200)
        }
    }

    fun markSeen() {
        if (unseen == 0) return
        unseen = 0
        notifyUi(true)
    }

    fun load() {
        try {
            val raw = prefs.getString("done", null) ?: return
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val p = m.optString("p")
                if (p.isEmpty() || !File(p).exists()) continue
                val t = DownloadTask(m.getString("id"), m.optString("u"), "", m.optString("t"))
                t.path = p
                t.size = m.optLong("s")
                t.createdAt = m.optLong("c")
                t.status = DlStatus.DONE
                t.progress = 1.0
                done.add(t)
            }
        } catch (_: Throwable) {
        }
        try {
            val raw = prefs.getString("pending", null) ?: return
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val t = DownloadTask(m.getString("id"), m.optString("u"), m.optString("r"), m.optString("t"))
                t.createdAt = m.optLong("c")
                t.kind = m.optString("k").ifEmpty { null }
                t.yt = m.optBoolean("y")
                // Uygulama kapanınca çalışan indirme de "duraklatıldı" sayılır; Devam ile başlar
                t.status = if (m.optString("st") == "FAILED") DlStatus.FAILED else DlStatus.PAUSED
                t.error = m.optString("e").ifEmpty { null }
                t.progress = 0.0
                if (t.status == DlStatus.PAUSED) t.label = L.t("paused")
                t.body = bodyFor(t, null)
                active.add(t)
            }
        } catch (_: Throwable) {
        }
    }

    private fun persist() {
        val a = JSONArray()
        for (t in done) a.put(
            JSONObject().put("id", t.id).put("u", t.url).put("t", t.title)
                .put("p", t.path).put("s", t.size).put("c", t.createdAt)
        )
        val pend = JSONArray()
        for (t in active) {
            if (t.kind == "blob") continue // blob sayfaya bağlıdır, yeniden başlatılamaz
            if (t.status == DlStatus.CANCELED) continue
            pend.put(
                JSONObject().put("id", t.id).put("u", t.url).put("r", t.referer).put("t", t.title)
                    .put("c", t.createdAt).put("k", t.kind ?: "").put("y", t.yt)
                    .put("st", t.status.name).put("e", t.error ?: "")
            )
        }
        prefs.edit().putString("done", a.toString()).putString("pending", pend.toString()).apply()
    }

    private val appDir: String
        get() {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            return File(base, "VideoDownloader").path
        }

    val displayPath: String
        get() = if (Store.publicFolder) "/storage/emulated/0/Download/VideoDownloader" else appDir

    private fun wifiOk(): Boolean {
        if (!Store.wifiOnly) return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Throwable) {
            true
        }
    }

    /** Görevin gövdesi (ilk başlatmada ve yeniden açılışta Devam için aynı kod). */
    private fun bodyFor(
        task: DownloadTask, blobReader: ((String, String, BlobRes) -> Unit)?
    ): () -> Unit {
        val url = task.url
        val referer = task.referer
        val kind = task.kind ?: "mp4"
        return {
            val ts = task.createdAt
            val stream = kind == "m3u8" || kind == "mpd" || kind == "blob"
            val tmp = ctx.cacheDir
            val path = File(tmp, "video_$ts.${if (stream) "mp4" else kind}").path
            val apath = File(tmp, "ses_$ts.m4a").path
            val report: Report = { p, l ->
                task.progress = p
                task.label = l
                notifyUi()
            }
            var sepAudio = false
            when (kind) {
                "m3u8" -> sepAudio = Streams.hls(url, path, apath, referer, report, task.cancel)
                "mpd" -> sepAudio = Streams.dash(url, path, apath, referer, report, task.cancel)
                "blob" -> {
                    if (blobReader == null) throw IOException(L.t("blobFail"))
                    val latch = CountDownLatch(1)
                    var err: String? = null
                    blobReader(url, path, object : BlobRes {
                        override fun ok() = latch.countDown()
                        override fun fail(msg: String) {
                            err = msg
                            latch.countDown()
                        }
                    })
                    while (!latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (task.cancel.on) throw CanceledEx()
                    }
                    err?.let { throw IOException(it) }
                }
                else -> Streams.direct(
                    url, path, Net.smartHeaders(url, referer, task.cancel),
                    task.cancel, report
                )
            }
            finish(task, path, if (sepAudio) apath else null)
        }
    }

    /** Hata mesajı döner (null: başladı). */
    fun start(
        url: String, referer: String, hint: String?, title: String?,
        blobReader: ((String, String, BlobRes) -> Unit)?
    ): String? {
        if (!wifiOk()) return L.t("wifiBlocked")
        val kind = Media.kind(url) ?: hint ?: "mp4"
        val name = if (!title.isNullOrBlank()) title.trim() else nameOf(url)
        val task = DownloadTask(System.nanoTime().toString(), url, referer, name)
        task.canPause = kind != "blob"
        task.kind = kind
        active.add(0, task)
        notifyUi(true)
        run(task, bodyFor(task, blobReader))
        return null
    }

    private fun run(task: DownloadTask, body: () -> Unit) {
        task.body = body
        persist()
        Native.fgStart(ctx)
        exec.execute {
            try {
                body()
                task.status = DlStatus.DONE
                task.progress = 1.0
                active.remove(task)
                done.add(0, task)
                unseen++
                persist()
            } catch (e: Throwable) {
                val canceled = e is CanceledEx || task.cancel.on
                if (canceled && task.pausing) {
                    task.status = DlStatus.PAUSED
                    if (task.progress < 0) task.progress = 0.0
                    task.label = L.t("paused")
                } else {
                    task.status = if (canceled) DlStatus.CANCELED else DlStatus.FAILED
                    task.error = if (canceled) L.t("canceled") else (e.message ?: e.toString())
                    if (canceled) active.remove(task)
                }
                persist()
            } finally {
                Native.fgStop(ctx)
            }
            notifyUi(true)
        }
    }


    fun cancel(t: DownloadTask) {
        t.pausing = false
        t.cancel.fire()
        if (t.status == DlStatus.FAILED || t.status == DlStatus.PAUSED) {
            t.status = DlStatus.CANCELED
            active.remove(t)
            persist()
            notifyUi(true)
        }
    }

    /** Duraklat: çalışan indirmeyi durdurur (Devam, baştan yeniden başlatır). */
    fun pause(t: DownloadTask) {
        if (t.status != DlStatus.RUNNING || !t.canPause) return
        t.pausing = true
        t.label = L.t("pausing")
        t.cancel.fire()
        notifyUi(true)
    }

    fun resume(t: DownloadTask) {
        val b = t.body ?: return
        if (t.status != DlStatus.PAUSED && t.status != DlStatus.FAILED) return
        t.pausing = false
        t.cancel = CancelFlag()
        t.status = DlStatus.RUNNING
        t.error = null
        t.progress = 0.0
        t.label = ""
        notifyUi(true)
        run(t, b)
    }

    fun removeFailed(t: DownloadTask) {
        active.remove(t)
        persist()
        notifyUi(true)
    }

    fun deleteDone(t: DownloadTask, deleteFile: Boolean = true) {
        done.remove(t)
        if (deleteFile && t.path != null) try { File(t.path!!).delete() } catch (_: Throwable) {}
        persist()
        notifyUi(true)
    }

    // ------------------------------------------------------------------ bitirme
    private fun targetDir(): File {
        if (Store.publicFolder) {
            try {
                val d = File(Environment.getExternalStorageDirectory(), "Download/VideoDownloader")
                d.mkdirs()
                val f = File(d, ".w")
                f.writeText("1")
                f.delete()
                return d
            } catch (_: Throwable) {
            }
        }
        val d = File(appDir)
        d.mkdirs()
        return d
    }

    private fun finish(task: DownloadTask, tmpPath: String, audioTmp: String?) {
        var src = tmpPath
        var sepAudio = audioTmp
        task.progress = -1.0
        if (audioTmp != null) {
            task.label = L.t("merging")
            notifyUi(true)
            val out = tmpPath + "_m.mp4"
            try {
                Mux.run(tmpPath, audioTmp, out)
                File(tmpPath).delete()
                File(audioTmp).delete()
                src = out
                sepAudio = null
            } catch (_: Throwable) {
                task.note = L.t("mergeFail")
                File(out).delete()
            }
        }
        task.label = L.t("saving")
        notifyUi(true)
        val dir = targetDir()
        val base = safe(task.title)
        val ext = src.substringAfterLast('.')
        var dest = File(dir, "$base.$ext")
        var n = 1
        while (dest.exists()) dest = File(dir, "$base (${n++}).$ext")
        File(src).copyTo(dest, overwrite = false, bufferSize = 262144)
        File(src).delete()
        task.path = dest.path
        task.size = dest.length()
        if (sepAudio != null) {
            val ad = File(dest.path.replace(Regex("\\.[^.]+$"), "_ses.m4a"))
            File(sepAudio).copyTo(ad, overwrite = true, bufferSize = 262144)
            File(sepAudio).delete()
        }
        if (Store.syncGallery) {
            Gallery.putVideo(ctx, dest)
        }
    }

    private fun nameOf(url: String): String {
        val seg = try {
            java.net.URI(url).path.split('/').filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
        var n = if (seg.isEmpty()) "video" else seg.last()
        n = n.replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
        if (n.length < 2 || n == "index" || n == "master" || n == "playlist") {
            n = "video_" + System.currentTimeMillis() / 1000
        }
        return n
    }

    private fun safe(s: String): String {
        var r = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        if (r.isEmpty()) r = "video"
        return if (r.length > 80) r.substring(0, 80) else r
    }
}

/** Ses + görüntü birleştirici (MediaMuxer). */
object Mux {
    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    fun run(video: String, audio: String, out: String) {
        val ve = MediaExtractor()
        ve.setDataSource(video)
        val ae = MediaExtractor()
        ae.setDataSource(audio)
        val vt = pick(ve, "video")
        val at = pick(ae, "audio")
        if (vt < 0 || at < 0) throw IOException("track yok")
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

/** Biten videoyu galeriye (MediaStore) ekler. */
object Gallery {
    fun putAudio(ctx: Context, f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                v.put(MediaStore.Audio.Media.MIME_TYPE, when (f.extension) { "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; else -> "audio/webm" })
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VideoDownloader")
                v.put(MediaStore.Audio.Media.IS_PENDING, 1)
                val r = ctx.contentResolver
                val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return
                r.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out, 262144) } }
                v.clear()
                v.put(MediaStore.Audio.Media.IS_PENDING, 0)
                r.update(uri, v, null, null)
            } else {
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
            }
        } catch (_: Throwable) {
        }
    }

    fun putVideo(ctx: Context, f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoDownloader")
                v.put(MediaStore.Video.Media.IS_PENDING, 1)
                val r = ctx.contentResolver
                val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return
                r.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out, 262144) } }
                v.clear()
                v.put(MediaStore.Video.Media.IS_PENDING, 0)
                r.update(uri, v, null, null)
            } else {
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
            }
        } catch (_: Throwable) {
        }
    }
}
