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

    class TextGot(val headers: Map<String, String>, val body: String)

    private val hdrIdx = ConcurrentHashMap<String, Int>()

    fun origin(u: String): String = try {
        val p = URI(u)
        if (p.host == null || p.scheme?.startsWith("http") != true) ""
        else p.scheme + "://" + p.host + (if (p.port > 0) ":${p.port}" else "")
    } catch (_: Throwable) {
        ""
    }

    fun headers(url: String, referer: String): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to UA,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        if (referer.isNotEmpty()) {
            h["Referer"] = referer
            val o = origin(referer)
            if (o.isNotEmpty()) h["Origin"] = o
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
        var last: Throwable? = null
        for (k in vars.indices) {
            val i = (start + k) % vars.size
            try {
                Http.get(url, vars[i], "bytes=0-0", cancel) { c ->
                    // A successful response validates this exact header set.
                    c.disconnect()
                }
                hdrIdx[key] = i
                return vars[i]
            } catch (e: Throwable) {
                if (e is CanceledEx) throw e
                last = e
                // Try alternate page-context headers only for common request
                // negotiation failures. Do not silently pretend a 403 worked.
                if (!retryOther(e)) throw e
            }
        }
        throw last ?: IOException("Video isteği doğrulanamadı")
    }
}
