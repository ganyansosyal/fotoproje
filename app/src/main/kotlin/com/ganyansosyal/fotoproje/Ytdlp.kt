package com.ganyansosyal.fotoproje

import android.content.Context
import android.webkit.CookieManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
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

    private val SOCIAL = Regex("(instagram\\.com|cdninstagram\\.|fbcdn\\.|facebook\\.com|fb\\.watch)", RegexOption.IGNORE_CASE)
    private val YT = Regex("(youtube\\.com|youtu\\.be|googlevideo\\.com|ytimg\\.com|youtube-nocookie\\.com)", RegexOption.IGNORE_CASE)

    fun isYoutube(vararg urls: String) = urls.any { YT.containsMatchIn(it) }
    fun isSocial(vararg urls: String) = urls.any { SOCIAL.containsMatchIn(it) }

    /**
     * İndirir ve ortaya çıkan dosyanın yolunu döner. Hata olursa IOException (mesajı yt-dlp'nin son hata satırı).
     * [src]: sayfa adresi (Instagram/Facebook) ya da doğrudan akış adresi (m3u8/mpd/mp4).
     */
    fun download(
        ctx: Context, task: DownloadTask, src: String, referer: String,
        report: (Double, String) -> Unit
    ): String {
        if (!ensure(ctx)) throw IOException("yt-dlp başlatılamadı: " + (initError ?: "?"))
        val dir = ctx.cacheDir
        val base = "yt_${task.createdAt}"
        dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() }

        val req = YoutubeDLRequest(src)
        req.addOption("--no-playlist")
        req.addOption("-f", "bv*+ba/b")
        // H.264 + AAC tercih edilir: her telefonda siyah ekransız oynar
        req.addOption("-S", "vcodec:h264,res,acodec:m4a")
        req.addOption("--merge-output-format", "mp4")
        req.addOption("-o", File(dir, "$base.%(ext)s").path)
        req.addOption("--no-mtime")
        req.addOption("--retries", "3")
        req.addOption("--fragment-retries", "5")
        req.addOption("--socket-timeout", "25")
        req.addOption("--concurrent-fragments", "4")
        req.addOption("--user-agent", Net.ua)
        if (referer.startsWith("http")) req.addOption("--referer", referer)
        try {
            val ck = CookieManager.getInstance().getCookie(if (referer.startsWith("http")) referer else src)
            if (!ck.isNullOrBlank()) req.addOption("--add-header", "Cookie:$ck")
        } catch (_: Throwable) {
        }

        val pid = "fp_${task.id}"
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
                if (progress >= 0f) {
                    val p = (progress / 100.0).coerceIn(0.0, 1.0)
                    report(p, "İndiriliyor %" + progress.toInt())
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
            throw IOException("yt-dlp: " + msg.take(200))
        } finally {
            running = false
            watcher.interrupt()
        }
        if (task.cancel.on) throw CanceledEx()
        val f = dir.listFiles()
            ?.filter { it.name.startsWith("$base.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IOException("yt-dlp: çıktı dosyası bulunamadı")
        return f.path
    }
}
