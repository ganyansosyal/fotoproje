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

class DownloadTask(val id: String, @Volatile var url: String, val referer: String, var title: String) {
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
    var audio = ""             // Görüntüyle birleştirilecek ayrı ses dosyası adresi (Instagram/Facebook)
    val alts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>() // yedek kaynaklar (adres, tür)
    @Volatile var allowSilent = false // Facebook: ses izi hiç bulunamazsa sessiz kaydetmeye izin
    var page = ""              // Çözümleyiciye gönderilen sayfa adresi (süre dolunca yeniden çözmek için)
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
                t.page = m.optString("pg")
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
                JSONObject().put("id", t.id).put("u", t.url).put("r", t.referer).put("pg", t.page).put("t", t.title)
                    .put("c", t.createdAt).put("k", t.kind ?: "")
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
        val referer = task.referer
        return body@{
            val kind = task.kind ?: "mp4"
            var silentRes: Ytdlp.Result? = null // yt-dlp'nin sessiz çıkardığı dosya (diğer yöntem de olmazsa bu kaydedilir)
            // Ana yol: yt-dlp + ffmpeg (ses/görüntü birleştirme, HLS/DASH, kodek uyumu)
            val yt = Ytdlp.isYoutube(task.url, referer)
            val social = Ytdlp.isSocial(task.url, referer)
            // blob: (MSE) videolar adresten indirilemez; Instagram/Facebook/YouTube'da sayfa adresi kullanılır
            val xPost = if (!social && !yt) Ytdlp.xPost(referer, task.page) else null
            // Facebook: gönderi adresi bulunamadıysa (akış/feed) ama doğrudan video adresi varsa, yt-dlp'yi atlayıp doğrudan indir
            val fbDirect = social && !yt && kind != "blob" && task.url.startsWith("http") &&
                Ytdlp.postUrl(referer, task.page) == null &&
                Regex("(fbcdn|facebook)\\.", RegexOption.IGNORE_CASE).containsMatchIn(task.url)
            if ((kind != "blob" || yt || social || xPost != null) && !fbDirect) {
                val post = if (social) Ytdlp.postUrl(referer, task.page) else xPost
                val ytPage = if (yt) Ytdlp.youtubePage(referer, task.page) else null
                if ((social && post == null) || (yt && ytPage == null)) {
                    throw IOException(
                        if (L.code == "tr") "Video sayfası adresi bulunamadı. Videoyu kendi sayfasında açıp yeniden indir."
                        else "Video page link not found. Open the video on its own page and try again."
                    )
                }
                val src = ytPage ?: post ?: task.url.substringBefore("#fpq=").substringBefore("#fpa=")
                var ytNote: String? = null
                // YouTube: önce NewPipeExtractor (kendi JavaScript motoru var), olmazsa yt-dlp
                if (ytPage != null) {
                    try {
                        task.label = if (L.code == "tr") "YouTube çözülüyor…" else "Resolving YouTube…"
                        notifyUi(true)
                        val pk = NewPipeYt.pick(ytPage)
                        val tmpDir = ctx.cacheDir
                        val vp = File(tmpDir, "video_${task.createdAt}.mp4").path
                        val ap = File(tmpDir, "ses_${task.createdAt}.m4a").path
                        Streams.direct(pk.videoUrl, vp, NewPipeYt.headers, task.cancel, { p, l ->
                            task.progress = p
                            task.label = l
                            notifyUi()
                        })
                        if (pk.audioUrl != null) {
                            task.label = if (L.code == "tr") "Ses indiriliyor…" else "Downloading audio…"
                            notifyUi(true)
                            Streams.direct(pk.audioUrl, ap, NewPipeYt.headers, task.cancel, { _, _ -> })
                            task.note = "NewPipe · " + pk.desc
                            finish(task, vp, ap)
                        } else {
                            task.note = "NewPipe · " + pk.desc
                            finish(task, vp, null)
                        }
                        return@body
                    } catch (e: CanceledEx) {
                        throw e
                    } catch (e: Throwable) {
                        if (task.cancel.on) throw CanceledEx()
                        ytNote = (e.message ?: e.javaClass.simpleName).take(120)
                    }
                }
                if (src.startsWith("http")) {
                    try {
                        task.label = if (L.code == "tr") "Hazırlanıyor…" else "Preparing…"
                        notifyUi(true)
                        val r = Ytdlp.download(ctx, task, src, referer) { p, l ->
                            task.progress = p
                            task.label = l
                            notifyUi()
                        }
                        task.note = r.info
                        if (r.info.contains("SES YOK") && !social && !yt && xPost == null && kind != "blob") {
                            // yt-dlp görüntüyü aldı ama sesi alamadı: kendi indiricimizle (ayrı ses akışını da alır) dene.
                            // O da olmazsa sessiz dosya yine de kaydedilir.
                            silentRes = r
                            task.label = if (L.code == "tr") "Ses izi yok, başka yöntem deneniyor…" else "No audio, trying another method…"
                            notifyUi(true)
                        } else {
                            finish(task, r.path, null)
                            return@body
                        }
                    } catch (e: CanceledEx) {
                        throw e
                    } catch (e: Throwable) {
                        if (task.cancel.on) throw CanceledEx()
                        // Instagram/Facebook/YouTube: eski yöntem sessiz/bozuk video üretiyordu, net hata göster
                        // Facebook: yt-dlp olmazsa doğrudan video adresi varsa eski (ses birleştirmeli) yönteme düş
                        val fbFallback = social && !yt && kind != "blob" &&
                            Regex("fbcdn\\.", RegexOption.IGNORE_CASE).containsMatchIn(task.url)
                        if ((social && !fbFallback) || yt || (kind == "blob" && xPost == null)) {
                            if (ytNote != null) throw IOException("NewPipe: " + ytNote + " | " + (e.message ?: "?"))
                            throw e
                        }
                        task.note = "yt-dlp olmadı (" + (e.message ?: "?").take(400) + "), eski yöntem"
                    }
                }
            }
            try {
            // "#fpa=" ile gelen ayrı ses adresini ayır (adres tazelenirken kaybolmasın)
            if (kind != "blob" && task.url.contains("#fpa=")) {
                task.audio = try {
                    java.net.URLDecoder.decode(task.url.substringAfter("#fpa=").substringBefore("#"), "UTF-8")
                } catch (_: Throwable) { "" }
                task.url = task.url.substringBefore("#fpa=")
            }
            // Ağdan yakalanıp eşleşen ayrı ses adresi (Facebook/Instagram): görüntü adresine göre bul
            if (kind != "blob" && task.audio.isEmpty() &&
                Regex("(fbcdn|cdninstagram)\\.", RegexOption.IGNORE_CASE).containsMatchIn(task.url)
            ) {
                AvPairs.audioFor(task.url, referer)?.let { task.audio = it }
            }
            // Düğmeye basıldığı/Devam edildiği anda: WebView'in gördüğü daha taze adres varsa onu kullan
            if (kind != "blob") {
                Streams.refresh(task.url, referer, task.cancel, false)?.let { task.url = it }
            }
            val url = task.url
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
            var ffDone = false // master/mpd çözüldü, FFmpeg video+sesi tek MP4'e yazdı
            if (kind == "m3u8" || kind == "mpd") {
                task.label = if (L.code == "tr") "Akış çözümleniyor…" else "Parsing stream…"
                notifyUi(true)
                val pk = ManifestDl.download(ctx, url, path, referer, task, report)
                if (pk != null) {
                    ffDone = true
                    task.progress = -1.0
                    addNote(
                        task,
                        "FFmpeg · Video: " + pk.videoId + " + Ses: " + (pk.audioId ?: "içinde/yok"),
                        "FFmpeg · Video: " + pk.videoId + " + Audio: " + (pk.audioId ?: "embedded/none")
                    )
                }
            }
            when (if (ffDone) "ff" else kind) {
                "ff" -> {}
                "m3u8" -> sepAudio = hlsSafe(url, path, apath, referer, report, task)
                "mpd" -> sepAudio = dashSafe(url, path, apath, referer, report, task)
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
                else -> {
                    Streams.direct(
                        url, path, Net.smartHeaders(url, referer, task.cancel),
                        task.cancel, report
                    )
                    // Bozuk / video olmayan dosya "indi" sayılmaz: hata verir, yedek kaynak denenir
                    checkPlayable(path, kind)
                    // Dosyada ses izi yoksa: sayfada yakalanan ayrı ses dosyasını bul (eşleştirme: süre)
                    if (task.audio.isEmpty() && Mux.tracks(path)?.second == false) {
                        val dur = try {
                            val r = android.media.MediaMetadataRetriever()
                            try {
                                r.setDataSource(path)
                                (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                            } finally { try { r.release() } catch (_: Throwable) {} }
                        } catch (_: Throwable) { 0.0 }
                        Probe.audioFor(url, dur)?.let { task.audio = it.url }
                    }
                    if (task.audio.isEmpty() && Mux.tracks(path)?.second == false &&
                        Regex("(cdninstagram|fbcdn)\\.", RegexOption.IGNORE_CASE).containsMatchIn(url)
                    ) {
                        // Ayrı ses yakalanamadı: sayfadaki sesli+görüntülü (progressive) dosyayı bul ve onu indir
                        val alt = findProgressive(referer, task.cancel)
                        if (alt != null) {
                            task.label = if (L.code == "tr") "Sesli sürüm indiriliyor…" else "Downloading version with audio…"
                            notifyUi()
                            File(path).delete()
                            Streams.direct(alt, path, Net.smartHeaders(alt, referer, task.cancel), task.cancel, report)
                            checkPlayable(path, kind)
                        }
                    }
                    // Facebook: ses hiçbir yoldan bulunamadıysa indirmeyi çöpe atma, sessiz kaydet ve not düş
                    if (task.audio.isEmpty() && Mux.tracks(path)?.second == false &&
                        Regex("fbcdn\\.", RegexOption.IGNORE_CASE).containsMatchIn(url)
                    ) {
                        try { File(ctx.cacheDir, "fb_net.txt").writeText(FbNetLog.dump()) } catch (_: Throwable) {}
                        // Son çare: oynayan videoyu SES DAHİL kaydet (gerçek zamanlı); sessiz dosya asla kaydedilmez
                        var recOk = false
                        if (blobReader != null) {
                            task.label = if (L.code == "tr") "Sesli kayıt alınıyor (video süresi kadar sürer, sayfada kal)…"
                            else "Recording with sound (takes the video's length, stay on the page)…"
                            task.progress = -1.0
                            notifyUi(true)
                            val rp = File(tmp, "rec_$ts.mp4").path
                            val rl = CountDownLatch(1)
                            var rerr: String? = null
                            blobReader("fprec:", rp, object : BlobRes {
                                override fun ok() = rl.countDown()
                                override fun fail(msg: String) {
                                    rerr = msg
                                    rl.countDown()
                                }
                            })
                            while (!rl.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                if (task.cancel.on) throw CanceledEx()
                            }
                            if (rerr == null && File(rp).exists() && File(rp).length() > 1024 &&
                                FfmpegFix.hasAudioStream(ctx, rp, task.cancel)
                            ) {
                                // webm/mp4 kaydı -> temiz MP4 (görüntü copy, ses AAC)
                                recOk = SafeMux.single(ctx, rp, path + ".rec.mp4", task.cancel)
                                if (recOk) {
                                    File(path).delete()
                                    File(path + ".rec.mp4").renameTo(File(path))
                                    addNote(
                                        task,
                                        "Ses ağdan alınamadı; video sesli olarak kaydedildi (gerçek zamanlı kayıt).",
                                        "Audio not available from network; recorded with sound in real time."
                                    )
                                }
                            }
                            try { File(rp).delete() } catch (_: Throwable) {}
                        }
                        if (!recOk) {
                            try { File(path).delete() } catch (_: Throwable) {}
                            throw IOException(
                                if (L.code == "tr") "Facebook: ses alınamadı, sessiz kaydedilmedi. Videoyu kendi sayfasında açıp sesi açarak oynat ve tekrar indir. [" + FbNetLog.summary() + "]"
                                else "Facebook: audio unavailable, not saved silently. Open the video on its own page, play it with sound and retry. [" + FbNetLog.summary() + "]"
                            )
                        }
                    }
                    if (task.audio.isNotEmpty()) {
                        // Ses kesin gelmeli: 3 deneme, olmazsa sessiz video kaydetmek yerine hata ver
                        var ok = false
                        var lastErr: Throwable? = null
                        for (k in 0 until 3) {
                            try {
                                task.label = if (L.code == "tr") "Ses indiriliyor…" else "Downloading audio…"
                                notifyUi()
                                File(apath).delete()
                                Streams.direct(
                                    task.audio, apath, Net.smartHeaders(task.audio, referer, task.cancel),
                                    task.cancel, { _, _ -> }
                                )
                                if (FfmpegFix.hasAudioFile(apath)) { ok = true; break }
                            } catch (e: CanceledEx) {
                                throw e
                            } catch (e: Throwable) {
                                lastErr = e
                                File(apath).delete()
                            }
                        }
                        if (!ok) {
                            // İPTAL YOK: ses inmedi, videoyla (tekli dosya) devam edilir
                            try { File(apath).delete() } catch (_: Throwable) {}
                            task.audio = ""
                            addNote(
                                task,
                                "Ayrı ses indirilemedi (" + (lastErr?.message ?: "?").take(80) + "), tekli dosyadan devam edildi.",
                                "Separate audio failed (" + (lastErr?.message ?: "?").take(80) + "), continued with the single file."
                            )
                        } else {
                            sepAudio = true
                        }
                    }
                }
            }
            // Ayrı ses dosyası yoksa/boşsa süreç KESİLMEZ, tekli akışla devam edilir
            if (sepAudio && !FfmpegFix.hasAudioFile(apath)) {
                sepAudio = false
                try { File(apath).delete() } catch (_: Throwable) {}
                addNote(
                    task,
                    "Ayrı ses dosyası boş/yok, tekli akıştan aktarıldı.",
                    "Separate audio file empty/missing, remuxed from the single stream."
                )
            }
            // HLS/DASH tek parça çıktısı ham TS / parçalı MP4'tür: temiz MP4'e çevir (siyah görüntü önlenir)
            var outPath = path
            if (!ffDone && !sepAudio && (kind == "m3u8" || kind == "mpd")) {
                val np = "$path.n.mp4"
                task.label = if (L.code == "tr") "Video düzenleniyor…" else "Fixing video…"
                notifyUi(true)
                var fixed = SafeMux.single(ctx, path, np, task.cancel)
                if (!fixed) {
                    try { File(np).delete() } catch (_: Throwable) {}
                    try {
                        Mux.remux(path, np)
                        fixed = true
                    } catch (e: CanceledEx) {
                        throw e
                    } catch (_: Throwable) {
                        try { File(np).delete() } catch (_: Throwable) {}
                    }
                }
                if (fixed) {
                    File(path).delete()
                    outPath = np
                }
            }
            finish(task, outPath, if (sepAudio) apath else null)
            } catch (e: CanceledEx) {
                throw e
            } catch (e: Throwable) {
                // Kendi yöntemimiz de olmadı: yt-dlp'nin sessiz ama sağlam dosyası varsa onu kaydet
                val sr = silentRes
                if (sr == null || task.cancel.on) throw e
                task.note = sr.info
                finish(task, sr.path, null)
            }
        }
    }

    /** Instagram/Facebook sayfasından (ve embed sayfasından) hem görüntü hem ses izi olan .mp4 adresini bulur. */
    private fun findProgressive(page: String, cancel: CancelFlag): String? {
        val pages = ArrayList<String>()
        if (page.startsWith("http")) pages.add(page)
        Regex("instagram\\.com/(?:[^/]+/)?(reels?|p|tv)/([A-Za-z0-9_-]+)").find(page)?.let {
            val k = if (it.groupValues[1].startsWith("reel")) "reel" else it.groupValues[1]
            pages.add("https://www.instagram.com/$k/${it.groupValues[2]}/embed/captioned/")
        }
        for (pg in pages) {
            val hits = try { PageScan.scan(pg) { cancel.on } } catch (_: Throwable) { continue }
            for (h in hits) {
                if (cancel.on) throw CanceledEx()
                val tr = Probe.avTracks(h.url, h.ref.ifEmpty { pg }) ?: continue
                if (tr.first && tr.second) return h.url
            }
        }
        return null
    }

    /** İndirilen dosya gerçekten oynatılabilir video mu: değilse siler ve hata verir. */
    private fun checkPlayable(path: String, kind: String) {
        val f = File(path)
        val bad = IOException("Video dosyası bozuk")
        if (!f.exists() || f.length() < 2048) { f.delete(); throw bad }
        val head = try { f.inputStream().use { i -> ByteArray(16).also { i.read(it) } } } catch (_: Throwable) { ByteArray(16) }
        if (head[0] == '<'.code.toByte() || head[0] == '{'.code.toByte()) { f.delete(); throw bad }
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(path)
            var video = false
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: "").startsWith("video/")) video = true
            }
            if (!video) { f.delete(); throw bad }
        } catch (e: IOException) {
            if (e === bad || e.message == bad.message) throw e
            // Çözücü bu kapsayıcıyı (avi/flv/wmv...) tanımıyor: doğrulanamaz, dosya kabul edilir
            if (kind in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")) { f.delete(); throw bad }
        } catch (_: Throwable) {
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** Hata mesajı döner (null: başladı). */
    fun start(
        url0: String, referer0: String, hint: String?, title: String?,
        alts: List<Pair<String, String>> = emptyList(),
        blobReader: ((String, String, BlobRes) -> Unit)?
    ): String? {
        if (!wifiOk()) return L.t("wifiBlocked")
        var url = url0
        var referer = referer0
        var pageUrl = ""
        var title2 = title
        var kind = Media.kind(url) ?: hint ?: "mp4"
        if (Resolver.enabled()) {
            // Backend: sayfa adresi gider, net indirme adresi (.mp4 / .m3u8) döner
            pageUrl = referer0.ifBlank { url0 }
            if (!pageUrl.startsWith("http")) return "Sayfa adresi bulunamadı"
            val r = try {
                Resolver.resolve(pageUrl)
            } catch (e: Throwable) {
                return "Çözümleyici: " + (e.message ?: e.javaClass.simpleName)
            }
            url = r.url
            kind = r.kind
            referer = r.referer ?: pageUrl
            if (!r.title.isNullOrBlank()) title2 = r.title
        } else if (kind == "blob") {
            // blob: bellekteki geçici nesnedir; önce arkasındaki düz kaynak (m3u8/mp4) var mı bak.
            val real = try { Streams.resolveBlob(referer, CancelFlag()) } catch (_: Throwable) { null }
            if (real != null) {
                url = real
                kind = Media.kind(real) ?: "mp4"
            }
        }
        // Yalnızca gerçek video adresleri; blob: sayfadan okunur
        if (kind != "blob" && pathExt(url) !in ALLOWED_EXT) {
            // Uzantısız adres: Content-Type / ilk baytlarla gerçek türü bul (start() arka plan iş parçacığında çalışır)
            val sk = try { Net.sniffKind(url, referer) } catch (_: Throwable) { null }
            // Tür okunamazsa yine de indirilir; dosya sonradan doğrulanır, bozuksa yedek kaynak denenir
            kind = sk ?: hint?.takeIf { it in ALLOWED_EXT } ?: "mp4"
        }
        val name = pickName(title2, url)
        val task = DownloadTask(System.nanoTime().toString(), url, referer, name)
        task.canPause = kind != "blob"
        task.kind = kind
        task.page = pageUrl
        task.alts.addAll(alts)
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
                // İlk istekte geçici 403/429/503 gelirse birkaç kez otomatik yeniden dene
                var attempt = 0
                while (true) {
                    try {
                        body()
                        break
                    } catch (e: Throwable) {
                        val canceled = e is CanceledEx || task.cancel.on
                        if (canceled) throw e
                        if (e !is HttpErr || attempt >= 2) {
                            // Bu kaynak olmadı: sıradaki yedek kaynağı dene, hiç kalmadıysa hata ver
                            val next = if (task.alts.isNotEmpty()) task.alts.removeAt(0) else null
                            if (next == null) throw e
                            task.url = next.first
                            task.kind = next.second
                            task.audio = ""
                            task.progress = 0.0
                            task.label = "Başka kaynak deneniyor…"
                            attempt = 0
                            notifyUi(false)
                            persist()
                            continue
                        }
                        // Adres bayatlamış olabilir (401/403/410): sayfadan taze adres almayı dene
                        if ((e.code == 401 || e.code == 403 || e.code == 410) && task.kind != "blob") {
                            task.label = "Adres yenileniyor…"
                            notifyUi(false)
                            val fresh = if (Resolver.enabled() && task.page.isNotEmpty()) {
                                try { Resolver.resolve(task.page, task.cancel).url } catch (_: Throwable) { null }
                            } else {
                                Streams.refresh(task.url, task.referer, task.cancel, true)
                            }
                            if (fresh != null && fresh != task.url) {
                                task.url = fresh
                                persist()
                            }
                        }
                        attempt++
                        task.label = "Yeniden deneniyor…"
                        notifyUi(false)
                        // Çok fazla istek (429/503) ise daha uzun bekle; iptale basılırsa hemen çık
                        var wait = if (e.code == 429 || e.code == 503) 10000L * attempt else 1500L * attempt
                        while (wait > 0 && !task.cancel.on) {
                            Thread.sleep(250L)
                            wait -= 250L
                        }
                    }
                }
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

    private fun freeDest(base: String, ext: String): File {
        val dir = targetDir()
        var dest = File(dir, "$base.$ext")
        var n = 1
        while (dest.exists()) dest = File(dir, "$base (${n++}).$ext")
        return dest
    }

    private fun finish(task: DownloadTask, tmpPath: String, audioTmp: String?) {
        val src = tmpPath
        var sepAudio = audioTmp
        var dest: File? = null
        task.progress = -1.0
        // Ayrı ses dosyası yoksa / boşsa süreç KESİLMEZ: tekli dosyadan zorunlu aktarıma geçilir
        if (sepAudio != null && !FfmpegFix.hasAudioFile(sepAudio)) {
            try { File(sepAudio).delete() } catch (_: Throwable) {}
            sepAudio = null
            addNote(
                task,
                "Ayrı ses dosyası boş/yok, tekli akıştan aktarıldı.",
                "Separate audio file empty/missing, remuxed from the single stream."
            )
        }
        val aud = sepAudio
        if (aud != null) {
            task.label = L.t("merging")
            notifyUi(true)
            // Birleştirmeyi doğrudan hedef klasöre yaz: ikinci bir kopyalama gerekmez
            val d = freeDest(safe(task.title), "mp4")
            var merged = false
            try {
                var lastP = -1
                Mux.run(tmpPath, aud, d.path) { p ->
                    val pc = (p * 100).toInt()
                    if (pc != lastP) {
                        lastP = pc
                        task.progress = p.coerceIn(0.0, 1.0)
                        task.label = L.t("merging") + " %" + pc
                        notifyUi()
                    }
                }
                merged = true
            } catch (e: CanceledEx) {
                d.delete()
                throw e
            } catch (_: Throwable) {
                d.delete()
                // MediaMuxer birleştiremedi (ör. opus ses): FFmpeg ile -c:v copy -c:a aac
                merged = SafeMux.merge(ctx, tmpPath, aud, d.path, task.cancel)
                if (!merged) d.delete()
            }
            if (merged) {
                File(tmpPath).delete()
                File(aud).delete()
                dest = d
                sepAudio = null
            } else {
                // İPTAL YOK: birleşmediyse video kaydedilir, ses de ayrı _ses.m4a olarak yanına konur
                addNote(
                    task,
                    "Ses ve görüntü birleştirilemedi; video kaydedildi, ses ayrı dosya olarak yanında.",
                    "Could not merge audio and video; video saved, audio kept as a separate file."
                )
            }
        }
        if (dest == null) {
            task.label = L.t("saving")
            notifyUi(true)
            val ext = src.substringAfterLast('.')
            val d = freeDest(safe(task.title), ext)
            var muxed = false
            // Tekli dosyada ses izi görünmüyorsa: -c:v copy -c:a aac ile zorunlu aktarım (iptal yok)
            if (ext.lowercase() == "mp4" && Mux.tracks(src)?.second != true) {
                task.label = L.t("merging")
                notifyUi(true)
                muxed = SafeMux.single(ctx, src, d.path, task.cancel)
                if (muxed) File(src).delete() else d.delete()
                if (muxed && FfmpegFix.lastWasSilent) addNote(
                    task,
                    "Kaynakta ses akışı bulunamadı; dosyaya sessiz ses izi eklendi.",
                    "No audio stream in the source; a silent audio track was added."
                )
            }
            if (!muxed) {
                File(src).copyTo(d, overwrite = false, bufferSize = 262144)
                File(src).delete()
            }
            dest = d
        }
        // Doğrulama artık İPTAL ETMEZ: görüntü izi yoksa geçersiz dosya silinir, ses yoksa not düşülür
        val tr = Mux.tracks(dest.path)
        if (tr != null && !tr.first) {
            try { dest.delete() } catch (_: Throwable) {}
            throw IOException(if (L.code == "tr") "Dosyada görüntü yok, indirme geçersiz" else "File has no video track")
        }
        val expectAudio = audioTmp != null || task.audio.isNotEmpty() ||
            Regex("(cdninstagram|fbcdn)\\.", RegexOption.IGNORE_CASE).containsMatchIn(task.url)
        if (expectAudio && tr != null && !tr.second) {
            addNote(
                task,
                "Kaynakta ses akışı bulunamadı; video sessiz kaydedildi. Videoyu oynatıp tekrar indirmeyi dene.",
                "No audio stream found in the source; saved without sound. Play the video and retry."
            )
        }
        task.path = dest.path
        task.size = dest.length()
        if (dest.extension.lowercase() in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv") && !Mux.hasFrame(dest.path)) {
            val codec = try {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(dest.path)
                    (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                        .firstOrNull { it.startsWith("video/") }?.removePrefix("video/") ?: "?"
                } finally { try { ex.release() } catch (_: Throwable) {} }
            } catch (_: Throwable) { "?" }
            addNote(
                task,
                "Uyarı: bu cihaz görüntüyü çözemiyor olabilir (siyah ekran). Kodek: $codec",
                "Warning: this device may not decode the picture (black screen). Codec: $codec"
            )
        }
        val leftover = sepAudio
        if (leftover != null) {
            val ad = File(dest.path.replace(Regex("\\.[^.]+$"), "_ses.m4a"))
            File(leftover).copyTo(ad, overwrite = true, bufferSize = 262144)
            File(leftover).delete()
        }
        if (Store.syncGallery) {
            Gallery.putVideo(ctx, dest)
        }
    }

    private fun addNote(task: DownloadTask, tr: String, en: String) {
        val m = if (L.code == "tr") tr else en
        task.note = if (task.note.isNullOrEmpty()) m else task.note + " " + m
    }

    /**
     * HLS indirme, ses hatasında İPTAL ETMEZ.
     * 1) Ses dosyası boş/eksikse: video zaten inmiştir, tekli akışla devam (false döner).
     * 2) Başka hata olursa: ana listedeki tekli (muxed) varyant doğrudan indirilir; ses varsa o akışın içindedir.
     */
    private fun hlsSafe(
        url: String, path: String, apath: String, referer: String, report: Report, task: DownloadTask
    ): Boolean {
        try {
            return Streams.hls(url, path, apath, referer, report, task.cancel)
        } catch (e: CanceledEx) {
            throw e
        } catch (e: AudioMissingException) {
            try { File(apath).delete() } catch (_: Throwable) {}
            addNote(
                task,
                "Ayrı ses bulunamadı, ana akıştaki tekli yapı kullanıldı.",
                "Separate audio not found, using the single-stream structure."
            )
            return false
        } catch (e: Throwable) {
            if (task.cancel.on) throw CanceledEx()
            val single = singleVariant(url, referer, task.cancel) ?: throw e
            try { File(path).delete() } catch (_: Throwable) {}
            try { File(apath).delete() } catch (_: Throwable) {}
            task.label = if (L.code == "tr") "Tekli akış deneniyor…" else "Trying single stream…"
            notifyUi(true)
            Streams.hls(single, path, apath, referer, report, task.cancel)
            addNote(
                task,
                "Ses/görüntü ayrı alınamadı, tekli akıştan indirildi.",
                "Separate tracks failed, downloaded from the single stream."
            )
            return false
        }
    }

    /** DASH: ses dosyası boş/eksikse video zaten inmiştir; iptal etmeden tekli dosyayla devam. */
    private fun dashSafe(
        url: String, path: String, apath: String, referer: String, report: Report, task: DownloadTask
    ): Boolean {
        try {
            return Streams.dash(url, path, apath, referer, report, task.cancel)
        } catch (e: CanceledEx) {
            throw e
        } catch (e: AudioMissingException) {
            try { File(apath).delete() } catch (_: Throwable) {}
            addNote(
                task,
                "Ayrı ses bulunamadı, görüntü akışıyla devam edildi.",
                "Separate audio not found, continued with the video stream."
            )
            return false
        }
    }

    /**
     * Ana listeden (master m3u8) tekli varyant adresini seçer: seçilen kalite (bant genişliği) varsa o,
     * yoksa AUDIO grubu OLMAYAN (sesi içinde taşıyan) en yüksek varyant, o da yoksa en yüksek varyant.
     * Zaten medya listesi ise null döner.
     */
    private fun singleVariant(url0: String, referer: String, cancel: CancelFlag): String? = try {
        val frag = url0.substringAfter("#fpq=", "")
        val wantBw = frag.substringBefore('_').toIntOrNull()
        val url = url0.substringBefore("#fpq=")
        val ls = Net.fetchText(url, referer, cancel).body.split('\n').map { it.trim() }
        var exact: String? = null
        var muxed: String? = null
        var muxedBw = -1
        var any: String? = null
        var anyBw = -1
        for (i in ls.indices) {
            if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Media.hlsAttr(ls[i], "BANDWIDTH")?.toIntOrNull() ?: 0
            var j = i + 1
            while (j < ls.size && (ls[j].isEmpty() || ls[j].startsWith("#"))) j++
            if (j >= ls.size) continue
            val u = ls[j]
            if (wantBw != null && bw == wantBw && exact == null) exact = u
            if (bw > anyBw) { anyBw = bw; any = u }
            if (Media.hlsAttr(ls[i], "AUDIO") == null && bw > muxedBw) { muxedBw = bw; muxed = u }
        }
        (exact ?: muxed ?: any)?.let { Media.resolve(url, it) }
    } catch (_: Throwable) {
        null
    }

    /** İndirmeye izin verilen uzantılar (sorgu dizisine değil, URL yoluna bakılır). */
    private val ALLOWED_EXT = setOf("m3u8", "mpd", "mp4", "webm", "mov", "m4v", "mkv", "flv", "3gp", "3g2", "ogv", "avi", "wmv", "mpg", "mpeg", "f4v", "asf", "m2ts", "mts")

    private fun pathExt(url: String): String = try {
        java.net.URI(url.substringBefore("#")).path.orEmpty()
            .substringAfterLast('/').substringAfterLast('.', "").lowercase()
    } catch (_: Throwable) {
        ""
    }

    private val GENERIC_TITLES = setOf(
        "new tab", "yeni sekme", "about:blank", "untitled", "başlıksız", "video", "loading", "yükleniyor"
    )

    /**
     * Dosya adı: sayfa başlığı yalnızca anlamlıysa kullanılır (boş, URL/host benzeri ya da genel
     * başlıklar elenir, " - Site" / " | Site" eki atılır); aksi halde adresten türetilir.
     */
    private fun pickName(title: String?, url: String): String {
        var t = title?.trim().orEmpty()
        t = t.replace(Regex("\\s+[|\\-–—·]\\s+[^|\\-–—·]{2,40}$"), "").trim()
        val bad = t.length < 2 || t.lowercase() in GENERIC_TITLES ||
            t.startsWith("http", true) || t.startsWith("www.", true) ||
            Regex("^[\\w.-]+\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(t)
        return if (bad) nameOf(url) else t
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

/**
 * Korumalı FFmpeg aktarımı: "ses yok" diye süreç KESİLMEZ.
 * Asıl iş [FfmpegFix.smart]'ta: ayrı ses -> videonun kendi sesi -> sessiz AAC izi (sırayla).
 * Görüntü -c:v copy, ses -c:a aac.
 */
object SafeMux {
    /** Tek dosya (ham TS / parçalı MP4 / sessiz görünen MP4) -> temiz MP4. Çıktıda hep ses izi olur. */
    fun single(ctx: Context, src: String, out: String, cancel: CancelFlag?): Boolean =
        FfmpegFix.smart(ctx, src, null, out, cancel)

    /** Ayrı görüntü + ayrı ses -> tek MP4. Başarısızsa sessiz iz eklenmez (ses dosyası korunur). */
    fun merge(ctx: Context, video: String, audio: String, out: String, cancel: CancelFlag?): Boolean =
        FfmpegFix.smart(ctx, video, audio, out, cancel, allowSilent = false)
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

    private fun rotation(f: MediaFormat): Int =
        try { if (f.containsKey("rotation-degrees")) f.getInteger("rotation-degrees") else 0 } catch (_: Throwable) { 0 }

    private fun isKey(ex: MediaExtractor) = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

    /**
     * Görüntü + ayrı ses -> temiz MP4.
     * Siyah görüntü nedenleri giderilir: ilk anahtar kareden önceki çözülemez kareler atılır,
     * zaman damgaları sıfırdan başlatılır (görüntü sesten geç başlamaz), dönüş bilgisi korunur.
     */
    fun run(video: String, audio: String, out: String, onProgress: (Double) -> Unit = {}) {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ve.setDataSource(video)
            ae.setDataSource(audio)
            val vt = pick(ve, "video")
            val at = pick(ae, "audio")
            if (vt < 0 || at < 0) throw IOException("track yok")
            ve.selectTrack(vt)
            ae.selectTrack(at)
            // İlk anahtar kareye kadar olan kareler çözülemez (ekran siyah kalır): atla
            while (ve.sampleTime >= 0 && !isKey(ve)) ve.advance()
            if (ve.sampleTime < 0) {
                // Dosyada anahtar kare bayrağı yok: ilk kareden başla
                ve.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val v0 = ve.sampleTime
            if (v0 < 0) throw IOException("Görüntü karesi yok")
            val a0 = ae.sampleTime
            val base = if (a0 in 0 until v0) a0 else v0
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vf = ve.getTrackFormat(vt)
            val vi = muxer.addTrack(vf)
            val ai = muxer.addTrack(ae.getTrackFormat(at))
            try { muxer.setOrientationHint(rotation(vf)) } catch (_: Throwable) {}
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val totalUs = try { vf.getLong(MediaFormat.KEY_DURATION) } catch (_: Throwable) { 0L }
            // Kareleri zaman sırasına göre iç içe yaz (art arda yazmak takılma yapıyordu)
            while (true) {
                val vTime = ve.sampleTime
                val aTime = ae.sampleTime
                if (vTime < 0 && aTime < 0) break
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                val ex = if (useVideo) ve else ae
                val track = if (useVideo) vi else ai
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    ex.advance()
                    if (ex.sampleTime < 0) { if (useVideo) { if (aTime < 0) break } else { if (vTime < 0) break } }
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (ex.sampleTime - base).coerceAtLeast(0L)
                info.flags = if (isKey(ex)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buf, info)
                ex.advance()
                if (useVideo && totalUs > 0) onProgress((info.presentationTimeUs.toDouble() / totalUs).coerceIn(0.0, 1.0))
            }
        } finally {
            try { if (started) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { ve.release() } catch (_: Throwable) {}
            try { ae.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Tek dosyayı (ham TS, parçalı fMP4 birleşimi) yeniden kodlamadan temiz MP4'e çevirir.
     * HLS parçaları ham TS olarak .mp4 adıyla kaydedildiğinde ses gelip görüntü siyah kalıyordu.
     */
    fun remux(src: String, out: String, onProgress: (Double) -> Unit = {}) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src)
            val vt = pick(ex, "video")
            val at = pick(ex, "audio")
            if (vt < 0) throw IOException("track yok")
            ex.selectTrack(vt)
            if (at >= 0) ex.selectTrack(at)
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vf = ex.getTrackFormat(vt)
            val vi = muxer.addTrack(vf)
            val ai = if (at >= 0) muxer.addTrack(ex.getTrackFormat(at)) else -1
            try { muxer.setOrientationHint(rotation(vf)) } catch (_: Throwable) {}
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val totalUs = try { vf.getLong(MediaFormat.KEY_DURATION) } catch (_: Throwable) { 0L }
            var base = -1L
            var wrote = false
            // Ön tarama: ilk 300 görüntü karesinde anahtar kare bayrağı var mı?
            var anyKey = false
            var seen = 0
            while (seen < 300) {
                val ti0 = ex.sampleTrackIndex
                if (ti0 < 0) break
                if (ti0 == vt) {
                    seen++
                    if (isKey(ex)) { anyKey = true; break }
                }
                ex.advance()
            }
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val t = ex.sampleTime
                val isVideo = ti == vt
                if (base < 0) {
                    // İlk anahtar kareden önceki kareler (ve ses) atılır; bayrak hiç yoksa ilk görüntü karesinden başla
                    if (!isVideo || (anyKey && !isKey(ex))) { ex.advance(); continue }
                    base = t
                }
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (t - base).coerceAtLeast(0L)
                info.flags = if (isKey(ex)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(if (isVideo) vi else ai, buf, info)
                wrote = true
                ex.advance()
                if (isVideo && totalUs > 0) onProgress((info.presentationTimeUs.toDouble() / totalUs).coerceIn(0.0, 1.0))
            }
            if (!wrote) throw IOException("Görüntü karesi yok")
        } finally {
            try { if (started) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** Dosyadaki gerçek izler (görüntü, ses). Okunamazsa null. */
    fun tracks(path: String): Pair<Boolean, Boolean>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            var v = false
            var a = false
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) v = true
                if (m.startsWith("audio/")) a = true
            }
            Pair(v, a)
        } catch (_: Throwable) {
            null
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** Dosyadan gerçekten bir görüntü karesi alınabiliyor mu (false: siyah ekran riski). Bilinmezse true. */
    fun hasFrame(path: String): Boolean {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = minOf(d * 500L, 1_000_000L)
            r.getFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) != null
        } catch (_: Throwable) {
            true
        } finally {
            try { r.release() } catch (_: Throwable) {}
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

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "3g2" -> "video/3gpp2"
        "ogv" -> "video/ogg"
        "avi" -> "video/x-msvideo"
        "wmv", "asf" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "m2ts", "mts" -> "video/mp2t"
        else -> "video/mp4"
    }

    fun putVideo(ctx: Context, f: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                v.put(MediaStore.Video.Media.MIME_TYPE, mimeOf(f.name))
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
