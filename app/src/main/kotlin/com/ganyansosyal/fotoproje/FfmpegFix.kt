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
 * FFmpeg yedeği. MediaMuxer birleştiremezse / çeviremezse devreye girer.
 * youtubedl-android kütüphanesindeki hazır ffmpeg dosyasını (libffmpeg.so) doğrudan çalıştırır.
 * Yeniden kodlama yapmaz (-c copy): hızlıdır, kalite kaybı olmaz.
 */
object FfmpegFix {
    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun libPath(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return File(base, "ffmpeg/usr/lib").absolutePath + ":" + File(base, "python/usr/lib").absolutePath
    }

    private fun run(ctx: Context, args: List<String>): Boolean {
        val exe = bin(ctx)
        if (!exe.exists()) return false
        return try {
            val pb = ProcessBuilder(listOf(exe.absolutePath, "-hide_banner", "-loglevel", "error", "-y") + args)
            pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().use { it.readText() } // çıktıyı boşalt, takılmasın
            p.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun ok(f: File) = f.exists() && f.length() > 1024

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

    /** Ham TS / parçalı MP4 dosyasını temiz MP4'e çevirir. Başarılıysa true. */
    fun remux(ctx: Context, src: String, out: String): Boolean {
        val o = File(out)
        val good = run(
            ctx,
            listOf("-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", out)
        ) && ok(o)
        // aac_adtstoasc bazı ses türlerinde hata verir: filtresiz bir kez daha dene
        if (good) return true
        o.delete()
        val again = run(ctx, listOf("-i", src, "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", out)) && ok(o)
        if (!again) o.delete()
        return again
    }

    /**
     * Ayrı görüntü + ayrı ses dosyasını tek MP4'te birleştirir. Başarılıysa true.
     *
     * Birleştirmeye geçmeden ÖNCE ses dosyası doğrulanır: yoksa / boşsa FFmpeg hiç çalıştırılmaz,
     * [AudioMissingException] fırlatılır (sessiz / rastgele mp4 üretilmez).
     * Görüntü dosyası da yoksa ya da boşsa [IOException] fırlatılır.
     */
    fun merge(ctx: Context, video: String, audio: String, out: String): Boolean {
        requireAudio(audio)
        val v = File(video)
        if (!v.exists() || v.length() <= 0L) {
            throw IOException(if (L.code == "tr") "Görüntü dosyası yok ya da boş" else "Video file missing or empty")
        }
        val o = File(out)
        val good = run(
            ctx,
            listOf("-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", out)
        ) && ok(o)
        if (!good) o.delete()
        return good
    }
}
