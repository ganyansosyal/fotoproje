package com.ganyansosyal.fotoproje

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections

/** Yakalanan tek bir video / akış adresi. */
class VideoHit(
    val url: String,
    /** m3u8 | mpd | mp4 | webm | ts | manifest */
    val kind: String,
    /** net (ağ isteği) | fetch | xhr | fetch-ct | xhr-ct ... */
    val source: String,
    val pageUrl: String,
    /** İndirirken gerekebilecek başlıklar: Referer, Origin, User-Agent, Cookie */
    val headers: Map<String, String>,
    val time: Long = System.currentTimeMillis()
)

/** Adres sınıflandırma kuralları (hem ağ hem JS tarafı kullanır). */
object VideoRules {
    private val AD = Regex(
        "(doubleclick|googlesyndication|imasdk|adservice|adnxs|taboola|outbrain|/ads?/|[?&]ad_|vast|vpaid|beacon|analytics|thumb|sprite|storyboard)",
        RegexOption.IGNORE_CASE
    )
    private val MANIFEST_WORD = Regex("(^|[/._-])(manifest|playlist|master)([._/-]|$)", RegexOption.IGNORE_CASE)

    // Uzantısız veya sunucu uzantılı manifest adreslerine izin verilir; json/js/css gibi dosyalara verilmez.
    private val MANIFEST_EXT = setOf("", "php", "aspx", "ashx", "txt")

    fun isAd(url: String): Boolean = AD.containsMatchIn(url)

    /** Adres bir video/akış adayıysa türünü döner, değilse null. */
    fun classify(url: String): String? {
        if (url.length > 2500 || !url.startsWith("http", ignoreCase = true)) return null
        if (isAd(url)) return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val file = path.substringAfterLast('/')
        val ext = if (file.contains('.')) file.substringAfterLast('.') else ""
        return when {
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".mpd") -> "mpd"
            path.endsWith(".mp4") -> "mp4"
            path.endsWith(".webm") -> "webm"
            path.endsWith(".ts") || path.endsWith(".m4s") -> "ts"
            path.endsWith(".ism/manifest") -> "manifest"
            ext in MANIFEST_EXT && MANIFEST_WORD.containsMatchIn(file) -> "manifest"
            else -> null
        }
    }
}

/**
 * AŞAMA 1 — Ağ seviyesi dinleyici.
 *
 * Kullanım: WebViewClient.shouldInterceptRequest içinde [inspect] çağrılır.
 * [onFound] ana (UI) iş parçacığında çağrılır; aynı adres ikinci kez bildirilmez.
 * Aşama 2 (JS kancası) da aynı nesneye [offer] ile bildirir; böylece tekrarlar tek yerde elenir.
 *
 * @param includeSegments true ise .ts / .m4s parçaları da bildirilir (çok kalabalık olur, genelde false).
 */
class VideoSniffer(
    private val includeSegments: Boolean = false,
    private val onFound: (VideoHit) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())

    // En son 500 adresi hatırlar (LRU); bellek şişmez.
    private val seen: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > 500
        }
    )

    /** shouldInterceptRequest içinden çağır. Arka plan iş parçacığında çalışması güvenlidir. */
    fun inspect(req: WebResourceRequest) {
        try {
            if (!req.method.equals("GET", ignoreCase = true)) return
            val url = req.url.toString()
            val kind = VideoRules.classify(url) ?: return

            val headers = HashMap<String, String>()
            req.requestHeaders?.forEach { (k, v) ->
                if (k.equals("Referer", true) || k.equals("Origin", true) || k.equals("User-Agent", true)) {
                    headers[k] = v
                }
            }
            CookieManager.getInstance().getCookie(url)?.let { if (it.isNotEmpty()) headers["Cookie"] = it }

            offer(VideoHit(url, kind, "net", headers["Referer"] ?: "", headers))
        } catch (_: Throwable) {
            // Dinleyici asla sayfa yüklemesini bozmamalı.
        }
    }

    /** Hem ağ hem JS tarafı bulduğu adresi buraya verir. Tekrarları eler, UI iş parçacığına geçirir. */
    fun offer(hit: VideoHit) {
        if (hit.kind == "ts" && !includeSegments) return
        if (seen.put(hit.url, true) != null) return
        ui.post {
            try {
                onFound(hit)
            } catch (_: Throwable) {
            }
        }
    }

    /** Sayfa değişince çağırırsan aynı adresler yeniden bildirilebilir. */
    fun reset() {
        seen.clear()
    }
}

/** Hazır WebViewClient: kendi client'ın yoksa bunu kullan. Varsa yalnızca sniffer.inspect(req) satırını ekle. */
open class SniffingWebViewClient(private val sniffer: VideoSniffer) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request != null) sniffer.inspect(request)
        return super.shouldInterceptRequest(view, request)
    }
}
