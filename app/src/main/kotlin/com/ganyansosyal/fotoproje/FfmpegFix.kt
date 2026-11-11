package com.ganyansosyal.fotoproje

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Ses dosyası yok / boş olduğunda fırlatılır. Bu hata yakalanıp "sessiz mp4" kaydına çevrilmez:
 * indirme güvenli biçimde kesilir ve kullanıcıya hata olarak gösterilir.
 */
class AudioMissingException(message: String) : IOException(message)

/**
 * FFmpeg yedeği ve akıllı birleştirici.
 * youtubedl-android kütüphanesindeki hazır ffmpeg dosyasını (libffmpeg.so) doğrudan çalıştırır.
 *
 * Akıllı birleştirme ([smart]) çıktının HER ZAMAN ses izi olmasını sağlar:
 *  1. Ayrı ses dosyası geçerliyse:      -map 0:v:0 -map 1:a:0
 *  2. Yoksa videonun kendi ses izi:     -map 0:v:0 -map 0:a:0   (ffmpeg ile aranır)
 *  3. O da yoksa sessiz AAC izi eklenir: anullsrc + -shortest
 * Görüntü her zaman -c:v copy (kalite kaybı yok), ses -c:a aac.
 */
object FfmpegFix {
    /** Son [smart] çağrısında sessiz AAC izi eklendiyse true (kaynakta gerçekten ses yoktu). */
    @Volatile var lastWasSilent = false

    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun libPath(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return File(base, "ffmpeg/usr/lib").absolutePath + ":" + File(base, "python/usr/lib").absolutePath
    }

    private class Res(val code: Int, val log: String)

    /** FFmpeg'i çalıştırır; çıktıyı dosyaya yönlendirir (boru dolup takılmaz), iptali dinler. */
    private fun exec(ctx: Context, args: List<String>, cancel: CancelFlag?, level: String = "error"): Res? {
        val exe = bin(ctx)
        if (!exe.exists()) return null
        val logFile = File(ctx.cacheDir, "ffmpeg_${System.nanoTime()}.log")
        return try {
            val pb = ProcessBuilder(listOf(exe.absolutePath, "-hide_banner", "-loglevel", level, "-y") + args)
            pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            val p = pb.start()
            while (true) {
                try {
                    p.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    if (cancel != null && cancel.on) {
                        p.destroy()
                        throw CanceledEx()
                    }
                    Thread.sleep(300)
                }
            }
            Res(p.exitValue(), try { logFile.readText() } catch (_: Throwable) { "" })
        } catch (ce: CanceledEx) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            try { logFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun ok(f: File) = f.exists() && f.length() > 1024

    /** Dosyada ses akışı var mı? FFmpeg'in kendi taramasıyla bakar (MediaExtractor opus vb. kaçırabilir). */
    fun hasAudioStream(ctx: Context, path: String, cancel: CancelFlag? = null): Boolean {
        val r = exec(
            ctx,
            listOf("-analyzeduration", "100M", "-probesize", "100M", "-i", path),
            cancel,
            "info"
        )
        if (r != null) return Regex("Stream #\\d+:\\d+.*: Audio:").containsMatchIn(r.log)
        return Mux.tracks(path)?.second == true // ffmpeg yoksa Android çıkarıcıya güven
    }

    private fun msgAudio(path: String, why: String): String =
        if (L.code == "tr") "Ses dosyası $why, indirme geçersiz (${File(path).name})"
        else "Audio file $why, download invalid (${File(path).name})"

    /**
     * KATI DOĞRULAMA: ses dosyası diskte var ve boş değil mi?
     * (file.exists() && length() > 0). Değilse [AudioMissingException] fırlatır; çağıran süreç kesilir.
     * Dosya yoksa/boşsa kalıntı olarak diskte bırakılmaz.
     */
    fun requireAudio(audioPath: String?) {
        if (audioPath.isNullOrEmpty()) throw AudioMissingException(msgAudio("", if (L.code == "tr") "yok" else "missing"))
        val f = File(audioPath)
        if (!f.exists()) throw AudioMissingException(msgAudio(audioPath, if (L.code == "tr") "bulunamadı" else "not found"))
        if (f.length() <= 0L) {
            try { f.delete() } catch (_: Throwable) {}
            throw AudioMissingException(msgAudio(audioPath, if (L.code == "tr") "boş" else "is empty"))
        }
    }

    /** Doğrulama sonucunu istisnasız verir (true: ses dosyası geçerli). */
    fun hasAudioFile(audioPath: String?): Boolean =
        !audioPath.isNullOrEmpty() && File(audioPath).let { it.exists() && it.length() > 0L }

    private val PROBE = listOf("-fflags", "+genpts", "-analyzeduration", "100M", "-probesize", "100M")
    private val OUT_OPTS = listOf(
        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-strict", "experimental",
        "-max_muxing_queue_size", "4096", "-movflags", "+faststart"
    )

    /**
     * AKILLI BİRLEŞTİRME. Başarılıysa true; çıktıda her zaman bir ses izi olur
     * ([allowSilent] true ise kaynakta ses yoksa sessiz AAC izi eklenir).
     *
     * @param audio ayrı ses dosyası (yoksa null). Geçerliyse önce o kullanılır.
     * @param allowSilent false: ses bulunamazsa sessiz iz EKLENMEZ, false döner
     *        (ayrı ses dosyası varken birleşme başarısız olursa o dosya kaybolmasın diye).
     */
    fun smart(
        ctx: Context, video: String, audio: String?, out: String, cancel: CancelFlag?,
        allowSilent: Boolean = audio.isNullOrEmpty()
    ): Boolean {
        lastWasSilent = false
        val v = File(video)
        if (!v.exists() || v.length() <= 0L) {
            throw IOException(if (L.code == "tr") "Görüntü dosyası yok ya da boş" else "Video file missing or empty")
        }
        val o = File(out)
        fun attempt(args: List<String>): Boolean {
            val r = exec(ctx, args + out, cancel)
            val good = r != null && r.code == 0 && ok(o)
            if (!good) try { o.delete() } catch (_: Throwable) {}
            return good
        }

        // 1) Ayrı ses dosyası: zorunlu iki harita
        if (hasAudioFile(audio)) {
            if (attempt(PROBE + listOf("-i", video, "-i", audio!!, "-map", "0:v:0", "-map", "1:a:0") + OUT_OPTS)) return true
            if (!allowSilent) return false
        }
        // 2) Videonun kendi ses izi (varsa) korunur
        if (hasAudioStream(ctx, video, cancel)) {
            if (attempt(PROBE + listOf("-i", video, "-map", "0:v:0", "-map", "0:a:0") + OUT_OPTS)) return true
            // Harita vermeden (FFmpeg kendi seçsin) bir kez daha
            if (attempt(PROBE + listOf("-i", video) + OUT_OPTS)) return true
        }
        if (!allowSilent) return false
        // 3) Ses hiç yok: sessiz AAC izi ekle (dosya "ses izi yok" halinde kalmasın)
        val silent = attempt(
            PROBE + listOf(
                "-i", video,
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-map", "0:v:0", "-map", "1:a:0", "-shortest"
            ) + OUT_OPTS
        )
        if (silent) lastWasSilent = true
        return silent
    }

    /** Ham TS / parçalı MP4 dosyasını temiz MP4'e çevirir (ses korunur / yoksa sessiz iz eklenir). */
    fun remux(ctx: Context, src: String, out: String): Boolean = smart(ctx, src, null, out, null)

    /**
     * Ayrı görüntü + ayrı ses dosyasını tek MP4'te birleştirir. Başarılıysa true.
     *
     * Birleştirmeden ÖNCE ses dosyası doğrulanır: yoksa / boşsa FFmpeg hiç çalıştırılmaz,
     * [AudioMissingException] fırlatılır. Başarısız olursa sessiz iz EKLENMEZ (ses dosyası korunur).
     */
    fun merge(ctx: Context, video: String, audio: String, out: String): Boolean {
        requireAudio(audio)
        return smart(ctx, video, audio, out, null, allowSilent = false)
    }
}
