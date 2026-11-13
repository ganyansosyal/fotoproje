package com.ganyansosyal.fotoproje

import android.content.Context
import android.webkit.CookieManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.IOException

/** yt-dlp + ffmpeg ile indirme: görüntü/ses birleştirme ve HLS/DASH'i kütüphane yapar. */
object Ytdlp {
    @Volatile private var ready = false
    @Volatile var initError: String? = null

    /** İlk çağrıda python/yt-dlp/ffmpeg dosyalarını açar (birkaç sn sürer). */
    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (ready) return true
        return try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
            initError = null
            true
        } catch (e: Throwable) {
            initError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** yt-dlp'yi günde en çok bir kez güncelle (siteler değişince indirme bozulmasın). Arka plan iş parçacığında çağır. */
    fun updateIfDue(ctx: Context) {
        try {
            if (!ensure(ctx)) return
            val sp = ctx.getSharedPreferences("fp_ytdlp", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // Instagram çözümleyici düzeltmeleri önce nightly'ye girer (STABLE haftalarca geriden gelir)
            if (now - sp.getLong("upd_nightly", 0L) < 6L * 3600_000L) return
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            sp.edit().putLong("upd_nightly", now).apply()
        } catch (_: Throwable) {
        }
    }

    private val SOCIAL = Regex("(instagram\\.com|cdninstagram\\.|fbcdn\\.|fb\\.watch)", RegexOption.IGNORE_CASE)
    private val YT = Regex("(youtube\\.com|youtu\\.be|googlevideo\\.com|ytimg\\.com|youtube-nocookie\\.com)", RegexOption.IGNORE_CASE)

    private val FB = Regex("(fb\\.watch|fbcdn\\.)", RegexOption.IGNORE_CASE)

    class PageInfo(val title: String, val duration: Double)

    /** Sayfayı yt-dlp'nin kendi çözücüleriyle inceler (indirmeden): başlık ve süre. Çözemezse null. */
    fun info(ctx: Context, page: String): PageInfo? {
        if (!ensure(ctx)) return null
        return try {
            val req = YoutubeDLRequest(page)
            req.addOption("--no-playlist")
            req.addOption("--socket-timeout", "15")
            req.addOption("--user-agent", Net.ua)
            val vi = YoutubeDL.getInstance().getInfo(req)
            val d = vi.duration.toDouble()
            if (d > 0) PageInfo(vi.title ?: "", d) else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isYoutube(vararg urls: String) = urls.any { YT.containsMatchIn(it) }
    fun isSocial(vararg urls: String) = urls.any { SOCIAL.containsMatchIn(it) }

    private val YTPAGE = Regex(
        "(youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|live/)[A-Za-z0-9_-]{6,}|youtu\\.be/[A-Za-z0-9_-]{6,})",
        RegexOption.IGNORE_CASE
    )

    /** Adaylar arasından YouTube video sayfası adresini döner; yoksa null. */
    fun youtubePage(vararg urls: String): String? =
        urls.firstOrNull { it.startsWith("http") && YTPAGE.containsMatchIn(it) }?.substringBefore("#")

    private val POST = Regex(
        "(instagram\\.com/(?:[^/?#]+/)?(?:reels?|p|tv)/(?!audio\\b)[A-Za-z0-9_-]+|fb\\.watch/)",
        RegexOption.IGNORE_CASE
    )

    private val XPOST = Regex("(?:x|twitter)\\.com/[A-Za-z0-9_]+/status/([0-9]+)", RegexOption.IGNORE_CASE)

    /** Adaylar arasından X/Twitter tweet adresini döner (yt-dlp bu adresten doğru videoyu bulur); yoksa null. */
    fun xPost(vararg urls: String): String? {
        for (u in urls) {
            if (!u.startsWith("http")) continue
            val m = XPOST.find(u) ?: continue
            return "https://x.com/i/status/" + m.groupValues[1]
        }
        return null
    }

    /** Adaylar arasından gerçek gönderi (reel/video) adresini döner; yoksa null. */
    fun postUrl(vararg urls: String): String? = urls.firstOrNull { it.startsWith("http") && POST.containsMatchIn(it) }?.substringBefore("#")

    /** WebView çerezlerini yt-dlp'nin okuyacağı Netscape çerez dosyasına yazar (Instagram girişi için). */
    private fun cookieFile(ctx: Context, url: String, base: String): File? {
        return try {
            if (isYoutube(url)) return null
            val host = java.net.URI(url).host ?: return null
            val ck = CookieManager.getInstance().getCookie(url)
            if (ck.isNullOrBlank()) return null
            val parts = host.split('.')
            val dom = "." + (if (isSocial(url) && parts.size >= 2) parts.takeLast(2).joinToString(".") else host)
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            for (c in ck.split(";")) {
                val t = c.trim()
                val i = t.indexOf('=')
                if (i <= 0) continue
                sb.append(dom).append("\tTRUE\t/\tTRUE\t2147483647\t")
                    .append(t.substring(0, i)).append('\t').append(t.substring(i + 1)).append('\n')
            }
            File(ctx.cacheDir, "$base.cookies.txt").also { it.writeText(sb.toString()) }
        } catch (_: Throwable) {
            null
        }
    }

    class Result(val path: String, val info: String)

    /** İndirilen dosyayı doğrular: null = sorun yok, aksi halde nedeni. */
    private fun check(path: String): String? {
        val f = File(path)
        if (!f.exists() || f.length() < 2048) return "dosya boş"
        val tr = Mux.tracks(path)
        if (tr != null && !tr.first) return "görüntü izi yok"
        if (!Mux.hasFrame(path)) return "görüntü karesi çözülemiyor"
        return null
    }

    /** "avc 1280x720 + mp4a-latm" gibi kısa teknik özet (İndirilenler ekranında görünür). */
    private fun describe(path: String): String = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val parts = ArrayList<String>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    parts.add(m.removePrefix("video/") + (if (w > 0) " ${w}x$h" else ""))
                } else if (m.startsWith("audio/")) {
                    parts.add(m.removePrefix("audio/"))
                }
            }
            parts.joinToString(" + ").ifEmpty { "iz yok" }
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    } catch (_: Throwable) {
        "okunamadı"
    }

    /**
     * İndirir, doğrular, gerekirse ikinci yöntemle yeniden dener. Sonuç: dosya yolu + teknik özet.
     * 1. deneme: H.264+AAC öncelikli, yt-dlp'nin kendi indiricisi.
     * 2. deneme (1. bozuksa): ffmpeg ile indir + kapsayıcıyı zorla onar.
     * Hata olursa IOException (mesajı yt-dlp'nin son hata satırı ya da doğrulama nedeni).
     */
    fun download(
        ctx: Context, task: DownloadTask, src: String, referer: String,
        report: (Double, String) -> Unit
    ): Result = downloadOne(ctx, task, src, referer, report)

    private fun downloadOne(
        ctx: Context, task: DownloadTask, src: String, referer: String,
        report: (Double, String) -> Unit
    ): Result {
        if (!ensure(ctx)) throw IOException("yt-dlp başlatılamadı: " + (initError ?: "?"))
        var why: String? = null
        var silent: String? = null // sesi olmayan ama sağlam çıkan ilk sonuç (ikinci deneme de olmazsa bu verilir)
        for (attempt in 1..2) {
            val path = try {
                runOnce(ctx, task, src, referer, attempt == 2, report, anyAudio = silent != null, noCookies = false)
            } catch (e: CanceledEx) {
                throw e
            } catch (e: Throwable) {
                if (silent != null) return Result(silent, "yt-dlp #1 · " + describe(silent) + " · SES YOK")
                throw e
            }
            why = check(path)
            if (why == null) {
                val tr = Mux.tracks(path)
                if (tr != null && !tr.second) {
                    // Görüntü var, ses izi yok: ses ayrı akışta olabilir. Her ses biçimini kabul ederek bir kez daha dene.
                    if (attempt == 1) {
                        val keep = File(ctx.cacheDir, "sil_" + File(path).name)
                        silent = if (File(path).renameTo(keep)) keep.path else path
                        report(-1.0, "Ses izi yok, yeniden deneniyor…")
                        continue
                    }
                    // İkinci denemede de ses yok: kaynak gerçekten sessiz olabilir
                    silent?.let { try { File(it).delete() } catch (_: Throwable) {} }
                    return Result(path, "yt-dlp #$attempt · " + describe(path) + " · SES YOK")
                }
                silent?.let { try { File(it).delete() } catch (_: Throwable) {} }
                return Result(path, "yt-dlp #$attempt · " + describe(path))
            }
            // Bozuk çıktı: sil ve diğer yöntemi dene
            try { File(path).delete() } catch (_: Throwable) {}
            report(-1.0, "Dosya doğrulanamadı, yeniden deneniyor…")
        }
        if (silent != null) return Result(silent, "yt-dlp #1 · " + describe(silent) + " · SES YOK")
        throw IOException("İndirilen dosya oynatılamıyor ($why)")
    }

    private val SIZE_RE = Regex("of\\s+([0-9.]+)\\s*([KMG])i?B", RegexOption.IGNORE_CASE) // "~" ile başlayan tahminler eşleşmez

    private fun runOnce(
        ctx: Context, task: DownloadTask, src: String, referer: String, second: Boolean,
        report: (Double, String) -> Unit, anyAudio: Boolean = false, noCookies: Boolean = false
    ): String {
        val dir = ctx.cacheDir
        val base = "yt_${task.createdAt}"
        // 1. denemede önceki (duraklatılan/yarım kalan) dosyalar silinmez: yt-dlp kaldığı yerden sürdürür
        if (second) dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() }

        val req = YoutubeDLRequest(src)
        req.addOption("--no-playlist")
        // H.264 + AAC (her telefonda oynar); olmazsa en iyi kombinasyon
        req.addOption(
            "-f",
            // 2. deneme (1. sessiz çıktı): Instagram'ta önce görüntü+ses birlikte gelen (muxed) biçim
            if (anyAudio && isSocial(src)) "b/bv*[vcodec^=avc]+ba/bv*+ba"
            else if (anyAudio) "bv*[vcodec^=avc]+ba/bv*+ba/b"
            else "bv*[vcodec^=avc1]+ba[acodec^=mp4a]/bv*[vcodec^=avc]+ba/b[vcodec^=avc1]/bv*+ba/b"
        )
        req.addOption("--merge-output-format", "mp4")
        req.addOption("-o", File(dir, "$base.%(ext)s").path)
        req.addOption("--no-mtime")
        req.addOption("--retries", "3")
        req.addOption("--fragment-retries", "5")
        req.addOption("--socket-timeout", "25")
        // WebView'in mobil UA'sı yt-dlp'nin masaüstü sayfa çözümleyicisiyle uyuşmaz (HTTP 500); yt-dlp'nin kendi UA'sı kullanılır
        if (!FB.containsMatchIn(src)) req.addOption("--user-agent", Net.ua)
        if (second) {
            req.addOption("--downloader", "ffmpeg")
            req.addOption("--fixup", "force")
        } else {
            req.addOption("--concurrent-fragments", "6")
        }
        if (referer.startsWith("http")) req.addOption("--referer", referer)
        val cf = if (noCookies) null else cookieFile(ctx, src, base)
        if (cf != null) req.addOption("--cookies", cf.path)

        val pid = "fp_${task.id}_" + (if (second) 2 else 1)
        var running = true
        val watcher = Thread {
            while (running) {
                if (task.cancel.on) {
                    try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Throwable) {}
                    break
                }
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            YoutubeDL.getInstance().execute(req, pid) { progress, _, line ->
                // "[download]  12.3% of ~ 1.95MiB at ..." satırından toplam boyutu al
                SIZE_RE.find(line)?.let { m ->
                    val n = m.groupValues[1].toDoubleOrNull()
                    if (n != null) {
                        val mul = when (m.groupValues[2].uppercase()) { "K" -> 1024.0; "M" -> 1048576.0; else -> 1073741824.0 }
                        task.total = maxOf(task.total, (n * mul).toLong())
                    }
                }
                if (progress >= 0f) {
                    report((progress / 100.0).coerceIn(0.0, 1.0), "İndiriliyor %" + progress.toInt())
                } else if (line.contains("Merger", true) || line.contains("ffmpeg", true)) {
                    report(-1.0, "Birleştiriliyor…")
                }
            }
        } catch (e: Throwable) {
            if (task.cancel.on) throw CanceledEx()
            val msg = (e.message ?: e.javaClass.simpleName).lines()
                .lastOrNull { it.contains("ERROR", true) }
                ?.substringAfter("ERROR:")?.trim()
                ?: (e.message ?: e.javaClass.simpleName).take(160)
            throw IOException("yt-dlp: " + msg.take(400))
        } finally {
            running = false
            watcher.interrupt()
            try { cf?.delete() } catch (_: Throwable) {}
        }
        if (task.cancel.on) throw CanceledEx()
        val f = dir.listFiles()
            ?.filter { it.name.startsWith("$base.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".cookies.txt") }
            ?.maxByOrNull { it.length() }
            ?: throw IOException("yt-dlp: çıktı dosyası bulunamadı")
        return f.path
    }
}
