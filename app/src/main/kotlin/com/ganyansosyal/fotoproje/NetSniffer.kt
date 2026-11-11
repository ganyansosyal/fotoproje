package com.ganyansosyal.fotoproje

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.Collections

/** Yakalanan tek bir video / akış adresi. */
class VideoHit(
    val url: String,
    /** m3u8 | mpd | mp4 | webm | ts | manifest | m4a */
    val kind: String,
    /** net (ağ isteği) | fetch | xhr | fetch-ct | xhr-ct ... */
    val source: String,
    val pageUrl: String,
    /** İndirirken gerekebilecek başlıklar: Referer, Origin, User-Agent, Cookie */
    val headers: Map<String, String>,
    val time: Long = System.currentTimeMillis(),
    /** "audio" | "video" | "muxed" (ses+görüntü bir arada) | "" (bilinmiyor) */
    val role: String = ""
) {
    /** Aynı oturumda eşleşen ayrı ses adresi (video için); yoksa boş. */
    var audioUrl: String = ""
}

/** Eşleşmiş ikili: ayrı görüntü adresi + ayrı ses adresi. */
class VideoPair(val videoUrl: String, val audioUrl: String) {
    /** Downloads.kt'nin tanıdığı biçim: görüntü adresi + "#fpa=" + kodlanmış ses adresi. */
    fun asDownloadUrl(): String =
        videoUrl.substringBefore("#") + "#fpa=" + java.net.URLEncoder.encode(audioUrl, "UTF-8")
}

/** Adres sınıflandırma kuralları (hem ağ hem JS tarafı kullanır). */
object VideoRules {
    private val AD = Regex(
        "(doubleclick|googlesyndication|imasdk|adservice|adnxs|taboola|outbrain|/ads?/|[?&]ad_|vast|vpaid|beacon|analytics|thumb|sprite|storyboard)",
        RegexOption.IGNORE_CASE
    )
    private val MANIFEST_WORD = Regex("(^|[/._-])(manifest|playlist|master)([._/-]|$)", RegexOption.IGNORE_CASE)

    // Uzantısız veya sunucu uzantılı manifest adreslerine izin verilir; json/js/css gibi dosyalara verilmez.
    private val MANIFEST_EXT = setOf("", "php", "aspx", "ashx", "txt")

    private val AUDIO_EXT = Regex("\\.(m4a|aac|mp3|weba|opus)$", RegexOption.IGNORE_CASE)
    private val AUDIO_MIME = Regex("[?&]mime_?type=audio", RegexOption.IGNORE_CASE)

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
            // Ayrı ses akışları (modern sitelerde görüntü ve ses ayrı sunulur): "m4a" türüyle yakalanır
            AUDIO_EXT.containsMatchIn(path) -> "m4a"
            AUDIO_MIME.containsMatchIn(url) -> "m4a"
            path.endsWith(".ts") || path.endsWith(".m4s") -> "ts"
            path.endsWith(".ism/manifest") -> "manifest"
            ext in MANIFEST_EXT && MANIFEST_WORD.containsMatchIn(file) -> "manifest"
            else -> null
        }
    }

    // ------------------------------------------------------------ ROL (ses / görüntü) TESPİTİ

    private fun hdr(h: Map<String, String>?, name: String): String {
        if (h == null) return ""
        for ((k, v) in h) if (k.equals(name, ignoreCase = true)) return v
        return ""
    }

    private fun query(url: String, name: String): String = try {
        android.net.Uri.parse(url.substringBefore("#")).getQueryParameter(name) ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** Facebook/Instagram "efg" parametresinin çözülmüş JSON'u (yoksa null). */
    fun efg(url: String): JSONObject? = try {
        val raw = query(url, "efg")
        if (raw.isEmpty()) null else {
            var b = raw.replace('-', '+').replace('_', '/')
            while (b.length % 4 != 0) b += "="
            JSONObject(String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8))
        }
    } catch (_: Throwable) {
        null
    }

    /** Aynı videonun görüntü ve ses dosyası aynı varlık kimliğini taşır. */
    fun assetId(url: String): String {
        val j = efg(url) ?: return ""
        return j.optString("xpv_asset_id").ifEmpty { j.optString("video_id") }
    }

    /** efg etiketinden çözünürlük (örn. "..._720p" -> 720); yoksa 0. */
    fun height(url: String): Int {
        val tag = efg(url)?.optString("vencode_tag") ?: return 0
        return Regex("(\\d{3,4})p").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Bu istek ses mi, görüntü mü, ikisi bir arada mı?
     * Sırayla bakılır (ilk kesin sonuç kazanır):
     *  1. Adres parametreleri: mimetype / mime_type / mime / contenttype = audio/..., video/...
     *  2. Facebook/Instagram efg etiketi: "..._audio" -> ses, "progressive" -> muxed, "dash..." -> görüntü
     *  3. İstek başlıkları: Sec-Fetch-Dest (audio|video), Accept (audio/... veya video/...)
     *  4. Dosya uzantısı (m4a, aac, opus, weba, mp3 -> ses)
     * Medya işareti olmayan istek (resim, js...) için null döner.
     */
    fun role(url: String, headers: Map<String, String>?): String? {
        // 1) parametreler
        for (p in listOf("mimetype", "mime_type", "mime", "contenttype", "content_type")) {
            val v = query(url, p).lowercase()
            if (v.startsWith("audio")) return "audio"
            if (v.startsWith("video")) return "video"
        }
        // 2) efg etiketi
        val tag = efg(url)?.optString("vencode_tag")?.lowercase() ?: ""
        if (tag.isNotEmpty()) {
            if (tag.contains("audio")) return "audio"
            if (tag.contains("progressive")) return "muxed"
            if (tag.contains("dash") || tag.contains("h264") || tag.contains("vp9") || tag.contains("av1") ||
                tag.contains("hevc") || tag.contains("video")
            ) return "video"
        }
        // 3) başlıklar
        when (hdr(headers, "Sec-Fetch-Dest").lowercase()) {
            "audio" -> return "audio"
            "video" -> return "video"
        }
        val accept = hdr(headers, "Accept").lowercase()
        val aA = accept.contains("audio/")
        val aV = accept.contains("video/")
        if (aA && !aV) return "audio"
        if (aV && !aA) return "video"
        // 4) uzantı
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        if (AUDIO_EXT.containsMatchIn(path)) return "audio"
        return null
    }

    /**
     * Bayt aralığı parametrelerini (bytestart / byteend) atar: Facebook videoyu parça parça ister,
     * parça adresi indirilirse dosyanın yalnızca bir kısmı gelir. Tam dosya adresi bu parametresiz adrestir.
     */
    fun canonical(url: String): String {
        if (!url.contains("bytestart=") && !url.contains("byteend=")) return url
        val frag = if (url.contains('#')) "#" + url.substringAfter('#') else ""
        val noFrag = url.substringBefore('#')
        val base = noFrag.substringBefore('?')
        val q = noFrag.substringAfter('?', "")
        val kept = q.split('&').filter { it.isNotEmpty() && !it.startsWith("bytestart=") && !it.startsWith("byteend=") }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + frag
    }
}

/**
 * Aynı oturumda (sayfa) yakalanan ayrı ses ve görüntü adreslerini tutar ve eşleştirir.
 * Eşleştirme sırası: 1) aynı varlık kimliği (efg), 2) yoksa aynı sayfadan ve zamanca en yakın ses.
 * Downloads.kt indirme başlarken [audioFor] ile görüntü adresinin sesini sorar.
 */
object AvPairs {
    private class E(val url: String, val role: String, val asset: String, val page: String, val t: Long)

    private val list = Collections.synchronizedList(ArrayList<E>())
    private const val WINDOW_MS = 10 * 60_000L

    fun add(url: String, role: String, page: String, time: Long = System.currentTimeMillis()) {
        if (role != "audio" && role != "video") return
        synchronized(list) {
            if (list.any { it.url == url }) return
            list.add(E(url, role, VideoRules.assetId(url), page, time))
            while (list.size > 200) list.removeAt(0)
        }
    }

    fun clear() = list.clear()

    private fun bestAudio(video: E, all: List<E>): E? {
        val audios = all.filter { it.role == "audio" }
        if (audios.isEmpty()) return null
        if (video.asset.isNotEmpty()) {
            audios.filter { it.asset == video.asset }.maxByOrNull { it.t }?.let { return it }
        }
        // varlık kimliği yoksa: aynı sayfa + zamanca en yakın
        val near = audios.filter {
            (video.page.isEmpty() || it.page.isEmpty() || it.page == video.page) &&
                Math.abs(it.t - video.t) <= WINDOW_MS &&
                (video.asset.isEmpty() || it.asset.isEmpty())
        }
        return near.minByOrNull { Math.abs(it.t - video.t) }
    }

    /** [videoUrl] için eşleşen ses adresi; yoksa null. */
    fun audioFor(videoUrl: String, @Suppress("UNUSED_PARAMETER") page: String = ""): String? {
        val key = VideoRules.canonical(videoUrl.substringBefore("#"))
        val all = synchronized(list) { ArrayList(list) }
        val v = all.firstOrNull { it.role == "video" && it.url == key }
            ?: E(key, "video", VideoRules.assetId(key), "", System.currentTimeMillis())
        return bestAudio(v, all)?.url
    }

    /** Şu ana kadar eşleşen tüm (görüntü, ses) ikilileri. */
    fun pairs(): List<VideoPair> {
        val all = synchronized(list) { ArrayList(list) }
        val out = ArrayList<VideoPair>()
        for (v in all.filter { it.role == "video" }) {
            val a = bestAudio(v, all) ?: continue
            out.add(VideoPair(v.url, a.url))
        }
        return out
    }
}

/**
 * AŞAMA 1 — Ağ seviyesi dinleyici.
 *
 * Kullanım: WebViewClient.shouldInterceptRequest içinde [inspect] çağrılır.
 * [onFound] ana (UI) iş parçacığında çağrılır; aynı adres ikinci kez bildirilmez.
 * Aşama 2 (JS kancası) da aynı nesneye [offer] ile bildirir; böylece tekrarlar tek yerde elenir.
 *
 * Ses/görüntü ayrımı: adres uzantısına bakmadan, parametrelere (mimetype, efg etiketi) ve
 * istek başlıklarına (Sec-Fetch-Dest, Accept) bakılır. Ayrı ses ve görüntü [AvPairs] içinde eşleşir;
 * yeni bir ikili oluşunca [onPair] çağrılır.
 *
 * @param includeSegments true ise .ts / .m4s parçaları da bildirilir (çok kalabalık olur, genelde false).
 */
class VideoSniffer(
    private val includeSegments: Boolean = false,
    private val onFound: (VideoHit) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())

    /** Yeni bir (görüntü, ses) ikilisi oluştuğunda UI iş parçacığında çağrılır. */
    var onPair: ((VideoPair) -> Unit)? = null

    private val pairedSeen: MutableSet<String> = Collections.synchronizedSet(HashSet())

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
            val raw = req.url.toString()
            val reqHeaders = req.requestHeaders
            FbNetLog.add(raw, reqHeaders?.get("Range")) // teşhis: Facebook CDN isteklerini kaydet

            val role = VideoRules.role(raw, reqHeaders)
            var kind = VideoRules.classify(raw)
            if (kind == null) {
                // Uzantısı olmayan ama başlık/parametre ile ses ya da görüntü olduğu anlaşılan istek
                if (role != "audio" && role != "video") return
                if (raw.length > 2500 || !raw.startsWith("http", ignoreCase = true) || VideoRules.isAd(raw)) return
                kind = if (role == "audio") "m4a" else "mp4"
            }

            val headers = HashMap<String, String>()
            reqHeaders?.forEach { (k, v) ->
                if (k.equals("Referer", true) || k.equals("Origin", true) || k.equals("User-Agent", true)) {
                    headers[k] = v
                }
            }
            CookieManager.getInstance().getCookie(raw)?.let { if (it.isNotEmpty()) headers["Cookie"] = it }

            // Parça (bytestart/byteend) adresi değil, TAM dosya adresi saklanır
            val url = VideoRules.canonical(raw)
            offer(VideoHit(url, kind, "net", headers["Referer"] ?: "", headers, role = role ?: ""))
        } catch (_: Throwable) {
            // Dinleyici asla sayfa yüklemesini bozmamalı.
        }
    }

    /** Hem ağ hem JS tarafı bulduğu adresi buraya verir. Tekrarları eler, UI iş parçacığına geçirir. */
    fun offer(hit0: VideoHit) {
        if (hit0.kind == "ts" && !includeSegments) return
        val url = VideoRules.canonical(hit0.url)
        // JS'ten gelen isteklerde rol boşsa adres/tür ipuçlarından bul
        val role = hit0.role.ifEmpty {
            VideoRules.role(url, null) ?: if (hit0.kind == "m4a") "audio" else ""
        }
        val hit = if (url == hit0.url && role == hit0.role) hit0
        else VideoHit(url, hit0.kind, hit0.source, hit0.pageUrl, hit0.headers, hit0.time, role)
        if (seen.put(hit.url, true) != null) return

        if (role == "audio" || role == "video") AvPairs.add(hit.url, role, hit.pageUrl, hit.time)
        if (role == "video") AvPairs.audioFor(hit.url)?.let { hit.audioUrl = it }
        val newPairs = if (role == "audio" || role == "video") collectNewPairs() else emptyList()

        ui.post {
            try {
                onFound(hit)
            } catch (_: Throwable) {
            }
            for (p in newPairs) {
                try {
                    onPair?.invoke(p)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun collectNewPairs(): List<VideoPair> {
        val out = ArrayList<VideoPair>()
        for (p in AvPairs.pairs()) {
            if (pairedSeen.add(p.videoUrl + "\n" + p.audioUrl)) out.add(p)
        }
        return out
    }

    /** Sayfa değişince çağırırsan aynı adresler yeniden bildirilebilir. */
    fun reset() {
        seen.clear()
        pairedSeen.clear()
        AvPairs.clear()
    }
}

/** Hazır WebViewClient: kendi client'ın yoksa bunu kullan. Varsa yalnızca sniffer.inspect(req) satırını ekle. */
open class SniffingWebViewClient(private val sniffer: VideoSniffer) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request != null) sniffer.inspect(request)
        return super.shouldInterceptRequest(view, request)
    }
}

/**
 * Facebook/Instagram CDN (fbcdn) ağ isteği kaydı. Videonun sesi neden yakalanmadı sorusunu
 * cevaplamak için: her isteğin kodlama etiketi (efg -> vencode_tag), varlık kimliği,
 * bayt aralığı, sınıflandırma sonucu (kind) ve reklam filtresine takılıp takılmadığı tutulur.
 * Son 80 istek bellekte durur; imza / token parametreleri kaydedilmez.
 */
object FbNetLog {
    private class E(
        val t: Long, val file: String, val tag: String, val asset: String,
        val range: String, val kind: String, val ad: Boolean, val mime: String
    )

    private val buf = Collections.synchronizedList(ArrayList<E>())

    private fun efg(url: String): JSONObject? = try {
        val raw = android.net.Uri.parse(url.substringBefore("#")).getQueryParameter("efg")
        if (raw == null) null else {
            var b = raw.replace('-', '+').replace('_', '/')
            while (b.length % 4 != 0) b += "="
            JSONObject(String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8))
        }
    } catch (_: Throwable) {
        null
    }

    fun add(url: String, rangeHeader: String?) {
        try {
            if (!url.contains("fbcdn.", true) && !url.contains("cdninstagram.", true)) return
            val u = android.net.Uri.parse(url.substringBefore("#"))
            val j = efg(url)
            val bs = u.getQueryParameter("bytestart")
            val be = u.getQueryParameter("byteend")
            val range = when {
                bs != null || be != null -> "q:${bs ?: ""}-${be ?: ""}"
                !rangeHeader.isNullOrEmpty() -> "h:$rangeHeader"
                else -> "-"
            }
            val e = E(
                System.currentTimeMillis(),
                (u.lastPathSegment ?: "?").take(40),
                j?.optString("vencode_tag")?.take(60) ?: "(efg yok)",
                (j?.optString("xpv_asset_id")?.ifEmpty { j.optString("video_id") } ?: "").take(24),
                range,
                VideoRules.classify(url) ?: "null",
                VideoRules.isAd(url),
                u.getQueryParameter("mime_type") ?: u.getQueryParameter("mime") ?: ""
            )
            synchronized(buf) {
                buf.add(e)
                while (buf.size > 80) buf.removeAt(0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isAudio(e: E) = e.tag.contains("audio", true) || e.mime.startsWith("audio", true)

    /** İndirme notuna sığacak kısa özet. */
    fun summary(): String {
        val l = synchronized(buf) { ArrayList(buf) }
        if (l.isEmpty()) return "fbcdn isteği hiç görülmedi"
        val audio = l.count { isAudio(it) }
        val dash = l.count { it.tag.contains("dash", true) && !isAudio(it) }
        val noEfg = l.count { it.tag == "(efg yok)" }
        val ads = l.count { it.ad }
        val nullKind = l.count { it.kind == "null" }
        val assets = l.map { it.asset }.filter { it.isNotEmpty() }.toSet().size
        return "fbcdn=${l.size} ses=$audio dashVideo=$dash efgYok=$noEfg reklamaTakılan=$ads sınıfsız=$nullKind varlık=$assets"
    }

    /** Ayrıntılı dökümü (her satır bir istek) metin olarak verir. */
    fun dump(): String {
        val l = synchronized(buf) { ArrayList(buf) }
        val sb = StringBuilder()
        for (e in l) {
            sb.append(e.file).append(" | ").append(e.tag).append(" | asset=").append(e.asset)
                .append(" | range=").append(e.range).append(" | kind=").append(e.kind)
                .append(if (e.ad) " | REKLAM-FILTRESI" else "")
                .append(if (e.mime.isNotEmpty()) " | mime=" + e.mime else "")
                .append('\n')
        }
        return sb.toString()
    }

    fun clear() = buf.clear()
}
