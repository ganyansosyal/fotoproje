package com.ganyansosyal.fotoproje

import android.content.Context
import java.io.File

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

    /** Ayrı görüntü + ayrı ses dosyasını tek MP4'te birleştirir. Başarılıysa true. */
    fun merge(ctx: Context, video: String, audio: String, out: String): Boolean {
        val o = File(out)
        val good = run(
            ctx,
            listOf("-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", out)
        ) && ok(o)
        if (!good) o.delete()
        return good
    }
}
