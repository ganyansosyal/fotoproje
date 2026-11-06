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
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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

class HttpErr(val code: Int, val host: String) : IOException("HTTP $code · $host")

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
            for ((k, v) in headers) {
                if (k.equals("Range", true) || k.equals("Accept-Encoding", true)) continue
                c.setRequestProperty(k, v)
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
                    cancel.remove(c)
                    c.disconnect()
                    throw HttpErr(code, host)
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
                val buf = ByteArray(65536)
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
                ctx.stopService(Intent(ctx, DlService::class.java))
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
        e is HttpErr && (e.code == 403 || e.code == 429 || e.code == 503)

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
        val out = BufferedOutputStream(FileOutputStream(path), 1 shl 20)

        fun fail(e: Throwable) {
            err.compareAndSet(null, e)
            lk.withLock { cv.signalAll() }
        }

        fun fetch(url: String): ByteArray {
            var k = 0
            while (true) {
                try {
                    return Http.bytes(url, headers, cancel)
                } catch (e: CanceledEx) {
                    throw e
                } catch (e: Exception) {
                    if (cancel.on) throw CanceledEx()
                    if (isThrottle(e) && lim.limit > 4) {
                        lim.limit -= 2
                        Thread.sleep(400)
                    }
                    if (k++ >= 3) throw e
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
                        if (id >= lim.limit || next >= total) return
                        if (next - written <= 32) break
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
            if (init != null) out.write(fetch(init))
            progress(0, total.toLong())
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

        // Küçük dosya ya da Range yok: tek bağlantı
        if (total < 4L * 1024 * 1024) {
            Http.get(url, headers, null, cancel) { c ->
                val len = c.contentLengthLong
                var got = 0L
                c.inputStream.use { ins ->
                    FileOutputStream(path).use { fo ->
                        val buf = ByteArray(65536)
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
                progress(got, if (len > 0) len else -1L)
            }
            return
        }

        val chunk = 3L * 1024 * 1024
        val n = ((total + chunk - 1) / chunk).toInt()
        val raf = RandomAccessFile(path, "rw")
        try {
            raf.setLength(0)
            raf.setLength(total)
            val ch = raf.channel
            val got = AtomicLong(0)
            val nextIdx = AtomicInteger(0)
            val retry = ConcurrentLinkedQueue<Int>()
            val lim = Lim(conns)
            val err = AtomicReference<Throwable?>(null)

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
                    val from = i * chunk
                    val to = minOf(total, from + chunk) - 1
                    var k = 0
                    while (true) {
                        var part = 0L
                        try {
                            Http.get(url, headers, "bytes=$from-$to", cancel) { c ->
                                if (c.responseCode != 206) throw IOException("Parça eksik geldi")
                                c.inputStream.use { ins ->
                                    val buf = ByteArray(65536)
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
                            break
                        } catch (e: CanceledEx) {
                            err.compareAndSet(null, e)
                            return
                        } catch (e: Exception) {
                            got.addAndGet(-part)
                            if (isThrottle(e) && lim.limit > 2) {
                                lim.limit -= 2
                                if (id >= lim.limit) {
                                    retry.add(i)
                                    return
                                }
                                Thread.sleep(400)
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
            progress(total, total)
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }
}

/** İndirme sürerken uygulamanın arka planda kesilmemesi için ön plan servisi. */
class DlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_NOT_STICKY
    }
}
