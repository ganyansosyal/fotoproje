package com.ganyansosyal.fotoproje

import android.webkit.CookieManager
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Tarayıcıdaki gibi istek başlıkları ve metin getirme (eski engine.dart başı). */
object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** İndirmelerde WebView'in gerçek User-Agent'ı kullanılır (CDN'ler belirteci UA'ya bağlayabilir). */
    @Volatile var ua: String = UA

    class TextGot(val headers: Map<String, String>, val body: String)

    private val hdrIdx = ConcurrentHashMap<String, Int>()
    // Ekleme sırasını koruyan, en eski kaydı atan sınırlı önbellek
    private val capturedHeaders = object : LinkedHashMap<String, Map<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) =
            size > 300
    }

    /** WebView'in medya isteğinde kullandığı oturum/kimlik doğrulama başlıklarını sakla. */
    fun rememberRequestHeaders(url: String, source: Map<String, String>?) {
        if (url.isBlank()) return
        noteMediaUrl(url)
        if (source.isNullOrEmpty()) return
        val keep = linkedMapOf<String, String>()
        for ((rawName, rawValue) in source) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name.isEmpty() || value.isEmpty()) continue
            val lower = name.lowercase()
            if (lower in setOf(
                "host", "connection", "content-length", "cookie", "range",
                "accept-encoding", "proxy-authorization", "proxy-connection"
            )) continue
            if (lower == "authorization" || lower == "referer" || lower == "origin" ||
                lower == "user-agent" || lower == "accept" || lower == "accept-language" ||
                lower.startsWith("x-") || lower.startsWith("sec-fetch-") ||
                lower.startsWith("x-csrf-")) {
                keep[name] = value
            }
        }
        if (keep.isNotEmpty()) {
            synchronized(capturedHeaders) { capturedHeaders[url] = keep }
        }
    }

    // ---- Taze adres takibi: WebView'in en son istediği .mp4/.m3u8 adresi (token yenilenmiş olabilir)
    private val seenMedia = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    /** Host + yol (sorgu/fragment olmadan): token yalnızca sorguda değişen adresleri eşleştirir. */
    private fun pathKey(u: String): String = try {
        val p = URI(u.substringBefore("#"))
        (p.host ?: "") + p.path.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun noteMediaUrl(u: String) {
        if (!u.startsWith("http")) return
        val key = pathKey(u)
        val ext = key.substringAfterLast('.', "").lowercase()
        if (key.isEmpty() || (ext != "mp4" && ext != "m3u8")) return
        synchronized(seenMedia) { seenMedia[key] = u.substringBefore("#") }
    }

    /** Aynı host+yol için WebView'in gördüğü en yeni adres (yoksa null). */
    fun newestFor(u: String): String? {
        val key = pathKey(u)
        if (key.isEmpty()) return null
        return synchronized(seenMedia) { seenMedia[key] }
    }

    fun origin(u: String): String = try {
        val p = URI(u)
        if (p.host == null || p.scheme?.startsWith("http") != true) ""
        else p.scheme + "://" + p.host + (if (p.port > 0) ":${p.port}" else "")
    } catch (_: Throwable) {
        ""
    }

    fun headers(url: String, referer: String): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        if (referer.isNotEmpty()) {
            h["Referer"] = referer
            val o = origin(referer)
            if (o.isNotEmpty()) h["Origin"] = o
        }
        // Önce WebView'in o medya URL'si için gerçekten gönderdiği başlıkları uygula.
        val cap = synchronized(capturedHeaders) { capturedHeaders[url] }
        cap?.forEach { (k, v) ->
            if (!k.equals("Cookie", true) && !k.equals("Range", true)) h[k] = v
        }
        try {
            if (url.startsWith("http")) {
                val c = CookieManager.getInstance().getCookie(url)
                if (!c.isNullOrEmpty()) h["Cookie"] = c
            }
        } catch (_: Throwable) {
        }
        return h
    }

    /** Bazı CDN'ler yalnızca belirli Referer/Origin kabul eder: sırayla denenecek takımlar. */
    fun variants(url: String, referer: String): List<Map<String, String>> {
        val refs = ArrayList<String>()
        fun add(r: String) {
            if (!refs.contains(r)) refs.add(r)
        }
        if (referer.isNotEmpty()) {
            add(referer)
            val o = origin(referer)
            if (o.isNotEmpty()) add("$o/")
        }
        val self = origin(url)
        if (self.isNotEmpty()) add("$self/")
        add("")
        return refs.map { headers(url, it) }
    }

    private fun retryOther(e: Throwable): Boolean =
        e is HttpErr && (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 406 || e.code == 429)

    fun fetchText(url: String, referer: String, cancel: CancelFlag = CancelFlag()): TextGot {
        val vars = variants(url, referer)
        val key = hostOf(url) + "|" + referer
        val start = (hdrIdx[key] ?: 0).coerceIn(0, vars.size - 1)
        var last: Throwable? = null
        for (k in vars.indices) {
            val i = (start + k) % vars.size
            try {
                val body = Http.get(url, vars[i], null, cancel) { c ->
                    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                hdrIdx[key] = i
                return TextGot(vars[i], body)
            } catch (e: Throwable) {
                last = e
                if (e is CanceledEx || !retryOther(e)) throw e
            }
        }
        throw last ?: IOException("İstek başarısız")
    }

    /** Doğrudan dosya için çalışan başlık takımını bulur (1 baytlık aralık isteğiyle). */
    fun smartHeaders(url: String, referer: String, cancel: CancelFlag): Map<String, String> {
        val vars = variants(url, referer)
        val key = hostOf(url) + "|" + referer
        val start = (hdrIdx[key] ?: 0).coerceIn(0, vars.size - 1)
        for (k in vars.indices) {
            val i = (start + k) % vars.size
            try {
                Http.get(url, vars[i], "bytes=0-0", cancel) { c -> c.disconnect() }
                hdrIdx[key] = i
                return vars[i]
            } catch (e: Throwable) {
                if (e is CanceledEx) throw e
                // Hangi hata olursa olsun diğer başlık takımını dene (bazı siteler 404/405/416 da döner).
            }
        }
        // Hiçbiri doğrulanamadı: yine de sayfa başlıklarıyla asıl indirmeyi dene;
        // gerçek hata (403 vb.) indirme sırasında açıkça görünür.
        return vars.first()
    }
}
