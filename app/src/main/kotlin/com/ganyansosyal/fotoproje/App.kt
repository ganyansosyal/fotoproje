package com.ganyansosyal.fotoproje

import android.app.Application
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Çökme olursa nedenini dosyaya yaz: Android/data/com.ganyansosyal.fotoproje/files/crash.txt
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash.txt").appendText(
                    "---- ${java.util.Date()} thread=${t.name}\n${android.util.Log.getStackTraceString(e)}\n"
                )
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
        Store.init(this)
        Downloads.load()
        FavIcons.load()
        FavIcons.ensure(HomeHtml.hosts())
        // yt-dlp / ffmpeg'i arka planda hazırla (ilk açılışta birkaç sn sürer)
        Thread { Ytdlp.ensure(this); Ytdlp.updateIfDue(this) }.start()
    }
}
