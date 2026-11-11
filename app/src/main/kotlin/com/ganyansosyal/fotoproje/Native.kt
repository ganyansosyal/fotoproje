package com.ganyansosyal.fotoproje

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

class CanceledEx : IOException("canceled")

class HttpErr(val code: Int, val host: String, val retryAfterMs: Long = 0L) : IOException(buildMsg(code, host)) {
    companion object {
        private fun buildMsg(code: Int, host: String): String {
            val hint = when (code) {
                401, 403 -> "Sunucu isteği reddetti (oturum/token süresi dolmuş ya da bu içeriğin indirilmesine izin verilmiyor olabilir)"
                410 -> "Bağlantının süresi dolmuş; videoyu sayfada yeniden başlatıp tekrar dene"
                429 -> "Çok fazla istek; biraz bekleyip tekrar dene"
                else -> ""
            }
            return if (hint.isEmpty()) "HTTP $code · $host" else "HTTP $code · $host — $hint"
        }
    }
}

class CancelFlag {
    @Volatile
    var on = false
    private val conns = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun add(c: HttpURLConnection) {
        conns.add(c)
        if (on) try { c.disconnect() } catch (_: Exception) {}
    }

    fun remove(c: HttpURLConnection) {
        conns.remove(c)
    }

    fun fire() {
        on = true
        for (c in conns) try { c.disconnect() } catch (_: Exception) {}
    }
}

private class Lim(@Volatile var limit: Int)

object Http {
    /** Yönlendirmeleri elle izler (http -> https dahil). Hata kodu >= 400 ise HttpErr atar. */
    fun open(url: String, headers: Map<String, String>, range: String?, cancel: CancelFlag): HttpURLConnection {
        var u = url
        for (hop in 0..6) {
            if (cancel.on) throw CanceledEx()
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            // Keep the page's navigation context, but never forward a stale Cookie
            // header blindly across CDN redirects. Ask WebView's cookie store for
            // cookies valid for the current URL on every hop.
            val sameHost = URL(u).host.equals(URL(url).host, true)
            for ((k, v) in headers) {
                if (k.equals("Range", true) || k.equals("Accept-Encoding", true) ||
                    k.equals("Cookie", true)) continue
                // Kimlik bilgisi başlığı başka bir host'a yönlendirmede sızmasın
                if (!sameHost && k.equals("Authorization", true)) continue
                c.setRequestProperty(k, v)
            }
            try {
                val currentCookies = CookieManager.getInstance().getCookie(u)
                if (!currentCookies.isNullOrBlank()) c.setRequestProperty("Cookie", currentCookies)
            } catch (_: Throwable) {
                // Some downloads may run without a WebView cookie store.
            }
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            cancel.add(c)
            try {
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location")
                    val host = URL(u).host
                    cancel.remove(c)
                    c.disconnect()
                    if (loc == null) throw HttpErr(code, host)
                    u = URL(URL(u), loc).toString()
                    continue
                }
                if (code >= 400) {
                    val host = URL(u).host
                    // Sunucu "şu kadar sn sonra dene" diyorsa (Retry-After) onu kullan
                    val ra = (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                    cancel.remove(c)
                    c.disconnect()
                    throw HttpErr(code, host, ra)
                }
                return c
            } catch (e: Exception) {
                cancel.remove(c)
                try { c.disconnect() } catch (_: Exception) {}
                if (cancel.on) throw CanceledEx()
                throw e
            }
        }
        throw IOException("Çok fazla yönlendirme")
    }

    /** Bağlantıyı açar, [block] çalıştırır; hata olursa kapatır, bitince bağlantıyı havuza bırakır. */
    fun <T> get(
        url: String, headers: Map<String, String>, range: String?,
        cancel: CancelFlag, block: (HttpURLConnection) -> T
    ): T {
        val c = open(url, headers, range, cancel)
        try {
            val r = block(c)
            cancel.remove(c)
            return r
        } catch (e: Exception) {
            cancel.remove(c)
            try { c.disconnect() } catch (_: Exception) {}
            if (cancel.on) throw CanceledEx()
            throw e
        }
    }

    fun bytes(url: String, headers: Map<String, String>, cancel: CancelFlag): ByteArray =
        get(url, headers, null, cancel) { c ->
            val len = c.contentLength
            val out = ByteArrayOutputStream(if (len > 0) len else 65536)
            c.inputStream.use { ins ->
                val buf = ByteArray(262144)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (cancel.on) throw CanceledEx()
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
}

object Native {
    private val flags = ConcurrentHashMap<String, CancelFlag>()

    fun register(id: String): CancelFlag {
        val f = CancelFlag()
        flags[id] = f
        return f
    }

    fun unregister(id: String) {
        flags.remove(id)
    }

    fun cancel(id: String) {
        flags[id]?.fire()
    }

    // ------------------------------------------------ arka plan servisi
    private val fg = AtomicInteger(0)

    fun fgStart(ctx: Context) {
        if (fg.getAndIncrement() == 0) {
            try {
                val i = Intent(ctx, DlService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    fun fgStop(ctx: Context) {
        if (fg.decrementAndGet() <= 0) {
            fg.set(0)
            try {
                // stopService() servis startForeground() demeden çağrılırsa uygulama çöker;
                // bunun yerine servise "dur" komutu gönderilir, o da önce startForeground der.
                val i = Intent(ctx, DlService::class.java).setAction(DlService.ACTION_STOP)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------ yardımcılar
    private val G = 0x47.toByte()

    /** Bazı siteler TS parçalarının başına sahte resim başlığı ekler; gerçek TS başlayana kadar atar. */
    private fun clean(d: ByteArray): ByteArray {
        if (d.size < 600) return d
        if (d[0] == G && d[188] == G) return d
        val a = d[4].toInt().toChar()
        val b = d[5].toInt().toChar()
        val c = d[6].toInt().toChar()
        val e = d[7].toInt().toChar()
        val tag = "$a$b$c$e"
        if (tag == "ftyp" || tag == "moof" || tag == "styp") return d
        val lim = minOf(d.size - 377, 16384)
        for (i in 1 until lim) {
            if (d[i] == G && d[i + 188] == G && d[i + 376] == G) return d.copyOfRange(i, d.size)
        }
        return d
    }

    private fun seqIv(n0: Long): ByteArray {
        val iv = ByteArray(16)
        var n = n0
        for (x in 15 downTo 12) {
            iv[x] = (n and 0xffL).toByte()
            n = n shr 8
        }
        return iv
    }

    private fun decrypt(raw: ByteArray, key: ByteArray, iv0: ByteArray): ByteArray {
        var iv = iv0
        if (iv.size != 16) {
            val t = ByteArray(16)
            val k = minOf(16, iv.size)
            System.arraycopy(iv, iv.size - k, t, 16 - k, k)
            iv = t
        }
        val ks = SecretKeySpec(key, "AES")
        val ps = IvParameterSpec(iv)
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, ks, ps)
            c.doFinal(raw)
        } catch (e: Exception) {
            val len = raw.size - raw.size % 16
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, ks, ps)
            c.doFinal(raw, 0, len)
        }
    }

    private fun isThrottle(e: Exception) =
        e is HttpErr && (e.code == 429 || e.code == 503)

    // ------------------------------------------------ 1) HLS / DASH parçaları
    fun segments(
        urls: List<String>, init: String?, path: String, headers: Map<String, String>,
        keys: List<String?>?, ivs: List<ByteArray?>?, seq: Long, conns: Int,
        cancel: CancelFlag, progress: (Long, Long) -> Unit
    ) {
        val total = urls.size
        if (total == 0) throw IOException("Video parçaları bulunamadı")

        val results = arrayOfNulls<ByteArray>(total)
        val lk = ReentrantLock()
        val cv = lk.newCondition()
        val lim = Lim(conns)
        var next = 0
        var written = 0
        val err = AtomicReference<Throwable?>(null)
        val keyCache = HashMap<String, ByteArray>()
        // Duraklat/Devam: yazılan parça sayısı ve dosya boyutu yan dosyada tutulur
        val side = File("$path.seg")
        var resumed = false
        try {
            val f = File(path)
            val p = if (side.exists()) side.readText().trim().split(",") else emptyList()
            if (f.exists() && p.size == 3 && p[0].toIntOrNull() == total && p[2].toLongOrNull() == f.length()) {
                val w = p[1].toIntOrNull() ?: 0
                if (w in 1..total) { written = w; next = w; resumed = true }
            }
        } catch (_: Exception) {}
        val out = BufferedOutputStream(FileOutputStream(path, resumed), 1 shl 20)

        fun fail(e: Throwable) {
            err.compareAndSet(null, e)
            lk.withLock { cv.signalAll() }
        }

        // Kısıtlama yok: tüm bağlantılar tam hızda çalışır (istek aralığı / bağlantı düşürme kaldırıldı).

        fun fetch(url: String): ByteArray {
            var k = 0
            var th = 0
            while (true) {
                try {
                    val b = Http.bytes(url, headers, cancel)
                    return b
                } catch (e: CanceledEx) {
                    throw e
                } catch (e: Exception) {
                    if (cancel.on) throw CanceledEx()
                    if (isThrottle(e)) {
                        // Sunucu "çok fazla istek" dedi: bağlantıları düşürmeden üstel bekleyip tekrar dene
                        if (th++ >= 12) throw e
                        val ra = (e as? HttpErr)?.retryAfterMs ?: 0L
                        val wait = maxOf(minOf(15000L, 1000L shl minOf(th - 1, 4)), minOf(ra, 60000L))
                        var w = 0L
                        while (w < wait) {
                            if (cancel.on) throw CanceledEx()
                            Thread.sleep(250)
                            w += 250
                        }
                    } else {
                        if (k++ >= 3) throw e
                        Thread.sleep(300L * k)
                    }
                }
            }
        }

        fun keyOf(url: String): ByteArray =
            synchronized(keyCache) { keyCache.getOrPut(url) { fetch(url) } }

        fun process(i: Int, raw: ByteArray): ByteArray {
            val ku = keys?.getOrNull(i)
            val body = if (ku == null) raw
            else decrypt(raw, keyOf(ku), ivs?.getOrNull(i) ?: seqIv(seq + i))
            return clean(body)
        }

        fun flush() {
            while (written < total) {
                val r = results[written] ?: break
                out.write(r)
                results[written] = null
                written++
            }
            progress(written.toLong(), total.toLong())
        }

        fun worker(id: Int) {
            while (true) {
                val i = lk.withLock {
                    while (true) {
                        if (err.get() != null || cancel.on) return
                        if (next >= total) return
                        if (id < lim.limit && next - written <= 64) break
                        cv.await(100, TimeUnit.MILLISECONDS)
                    }
                    next++
                }
                try {
                    val data = process(i, fetch(urls[i]))
                    lk.withLock {
                        results[i] = data
                        flush()
                        cv.signalAll()
                    }
                } catch (e: Throwable) {
                    fail(e)
                    return
                }
            }
        }

        try {
            if (init != null && !resumed) out.write(fetch(init))
            progress(written.toLong(), total.toLong())
            val threads = (0 until minOf(conns, total)).map { id ->
                Thread {
                    try {
                        worker(id)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            err.get()?.let { throw it }
            if (cancel.on) throw CanceledEx()
            lk.withLock { flush() }
            if (written < total) throw IOException("Eksik parça")
        } finally {
            try { out.close() } catch (_: Exception) {}
            try {
                if (written >= total) side.delete()
                else side.writeText("$total,$written,${File(path).length()}")
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------ 3) normal dosya (Range ile paralel)
    fun direct(
        url: String, path: String, headers: Map<String, String>, conns: Int, strict: Boolean,
        cancel: CancelFlag, progress: (Long, Long) -> Unit
    ) {
        var total = 0L
        try {
            val c = Http.open(url, headers, "bytes=0-0", cancel)
            try {
                val cr = c.getHeaderField("Content-Range") ?: ""
                val m = Regex("/(\\d+)\\s*$").find(cr)
                if (c.responseCode == 206 && m != null) total = m.groupValues[1].toLong()
            } finally {
                cancel.remove(c)
                try { c.disconnect() } catch (_: Exception) {}
            }
        } catch (e: CanceledEx) {
            throw e
        } catch (_: Exception) {
        }
        if (strict && total <= 0) throw IOException("Parçalı indirme desteklenmiyor")

        // Küçük dosya ya da Range yok: tek bağlantı (Range varsa duraklatınca kaldığı yerden sürer)
        if (total < 4L * 1024 * 1024) {
            val side = File("$path.sm")
            val ident = url.substringBefore("?")
            var have = 0L
            try {
                val f = File(path)
                val ln = if (side.exists()) side.readText().split("\n") else emptyList()
                if (total > 0 && f.exists() && ln.size == 2 && ln[0] == ident &&
                    ln[1].trim().toLongOrNull() == f.length() && f.length() > 0 && f.length() < total
                ) have = f.length()
            } catch (_: Exception) {}
            var complete = false
            try {
                Http.get(url, headers, if (have > 0) "bytes=$have-" else null, cancel) { c ->
                    val resume = have > 0 && c.responseCode == 206
                    val len0 = c.contentLengthLong
                    val len = if (resume) (if (len0 > 0) have + len0 else -1L) else len0
                    var got = if (resume) have else 0L
                    c.inputStream.use { ins ->
                        FileOutputStream(path, resume).use { fo ->
                            val buf = ByteArray(262144)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                if (cancel.on) throw CanceledEx()
                                fo.write(buf, 0, n)
                                got += n
                                progress(got, if (len > 0) len else -1L)
                            }
                        }
                    }
                    if (len > 0 && got != len) throw IOException("İndirme eksik kaldı ($got/$len bayt)")
                    progress(got, if (len > 0) len else -1L)
                    complete = true
                }
            } finally {
                try {
                    if (complete || total <= 0) side.delete()
                    else side.writeText(ident + "\n" + File(path).length())
                } catch (_: Exception) {}
            }
            return
        }

        val chunk = 16L * 1024 * 1024
        val n = ((total + chunk - 1) / chunk).toInt()
        // Duraklat/Devam: biten 16 MB'lık parçaların numaraları yan dosyada tutulur
        val side = File("$path.chk")
        val done = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        var resumed = false
        try {
            val f = File(path)
            val ln = if (side.exists()) side.readText().trim().split(",") else emptyList()
            if (f.exists() && f.length() == total && ln.size >= 2 && ln[0].toLongOrNull() == total) {
                for (x in ln.drop(1)) { val d = x.toIntOrNull(); if (d != null && d in 0 until n) done.add(d) }
                resumed = done.isNotEmpty()
            }
        } catch (_: Exception) {}
        var got0 = 0L
        for (d in done) got0 += minOf(total, d * chunk + chunk) - d * chunk
        val raf = RandomAccessFile(path, "rw")
        try {
            if (!resumed) {
                raf.setLength(0)
                raf.setLength(total)
            }
            val ch = raf.channel
            val got = AtomicLong(got0)
            val nextIdx = AtomicInteger(0)
            val retry = ConcurrentLinkedQueue<Int>()
            val lim = Lim(conns)
            val err = AtomicReference<Throwable?>(null)

            fun saveSide() {
                try { synchronized(done) { side.writeText("$total," + done.joinToString(",")) } } catch (_: Exception) {}
            }

            fun writeAt(buf: ByteArray, len: Int, pos: Long, c: FileChannel) {
                val bb = ByteBuffer.wrap(buf, 0, len)
                var p = pos
                while (bb.hasRemaining()) p += c.write(bb, p)
            }

            fun worker(id: Int) {
                while (err.get() == null) {
                    if (cancel.on) return
                    if (id >= lim.limit && retry.isEmpty()) return
                    val i = retry.poll() ?: nextIdx.getAndIncrement()
                    if (i >= n) return
                    if (done.contains(i)) continue
                    val from = i * chunk
                    val to = minOf(total, from + chunk) - 1
                    var k = 0
                    while (true) {
                        var part = 0L
                        try {
                            Http.get(url, headers, "bytes=$from-$to", cancel) { c ->
                                if (c.responseCode != 206) throw IOException("Parça eksik geldi")
                                c.inputStream.use { ins ->
                                    val buf = ByteArray(262144)
                                    var pos = from
                                    while (true) {
                                        val r = ins.read(buf)
                                        if (r < 0) break
                                        if (cancel.on) throw CanceledEx()
                                        writeAt(buf, r, pos, ch)
                                        pos += r
                                        part += r
                                        progress(got.addAndGet(r.toLong()), total)
                                    }
                                    if (pos != to + 1) throw IOException("Parça eksik geldi")
                                }
                            }
                            done.add(i)
                            saveSide()
                            break
                        } catch (e: CanceledEx) {
                            err.compareAndSet(null, e)
                            return
                        } catch (e: Exception) {
                            got.addAndGet(-part)
                            if (isThrottle(e)) {
                                // Tek bağlantıda bile kısıtlanıyorsa üstel bekleyip daha çok dene
                                if (k++ >= 12) {
                                    err.compareAndSet(null, e)
                                    return
                                }
                                var w = 0L
                                val wait = minOf(15000L, 1000L shl minOf(k - 1, 4))
                                while (w < wait) {
                                    if (cancel.on) { err.compareAndSet(null, CanceledEx()); return }
                                    Thread.sleep(250)
                                    w += 250
                                }
                                continue
                            }
                            if (k++ >= 3) {
                                err.compareAndSet(null, e)
                                return
                            }
                        }
                    }
                }
            }

            progress(0, total)
            val threads = (0 until minOf(conns, n)).map { id ->
                Thread {
                    try {
                        worker(id)
                    } catch (e: Throwable) {
                        err.compareAndSet(null, e)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            // kısıtlanan işçilerin bıraktığı parçalar kaldıysa burada bitir
            if (err.get() == null && !cancel.on && !retry.isEmpty()) worker(0)
            err.get()?.let { throw it }
            if (cancel.on) throw CanceledEx()
            try { side.delete() } catch (_: Exception) {}
            progress(total, total)
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }
}

/** İndirme sürerken uygulamanın arka planda kesilmemesi için ön plan servisi. */
class DlService : Service() {
    companion object { const val ACTION_STOP = "dl_stop" }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun goForeground(): Boolean {
        return try {
            val chId = "dl"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "İndirmeler", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pi = if (launch != null) PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ) else null
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId)
            else Notification.Builder(this)
            b.setContentTitle("Video indiriliyor")
                .setContentText("İndirme arka planda devam ediyor")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
            if (pi != null) b.setContentIntent(pi)
            val n = b.build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(4711, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(4711, n)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground() // mümkün olan en erken anda
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = goForeground() // her komutta tekrar: sistem bunu şart koşar
        if (intent?.action == ACTION_STOP || !ok) {
            try { stopForeground(true) } catch (_: Throwable) {}
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
