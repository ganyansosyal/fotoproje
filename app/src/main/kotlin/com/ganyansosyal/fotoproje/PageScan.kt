package com.ganyansosyal.fotoproje

import java.net.URI
import java.util.regex.Pattern

/**
 * Sayfa tarayıcı: WebView'e bağlı kalmadan sayfanın HTML'ini, iframe'lerini ve oynatıcı script'lerini
 * indirip içindeki video adreslerini (m3u8 / mpd / mp4 ...) bulur. Adresleri bulundukları sayfanın
 * Referer'i ile birlikte verir (iframe içindeki CDN adresleri çoğu zaman bunu ister).
 */
object PageScan {
    class Hit(val url: String, val ref: String)

    /**
     * Otomatik link bulucu ana şalteri. false iken sayfa hiç taranmaz (ağ isteği yok);
     * yalnızca WebView'in oynatma sırasında gördüğü adresler kullanılır. Tekrar açmak için true yap.
     */
    const val ENABLED = false

    // Yalnızca gerçek video uzantıları: URL yolu bilinen video uzantısıyla bitmeli (sorgu dizisine bakılmaz)
    private val MEDIA = Pattern.compile("\\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|3g2|ogv|avi|wmv|mpg|mpeg|f4v|asf|m2ts|mts)$", Pattern.CASE_INSENSITIVE)
    private val AD = Pattern.compile(
        "(doubleclick|googlesyndication|imasdk|adservice|adsystem|adnxs|taboola|outbrain|[/_.-]ads?[/_.-]|" +
            "advert|preroll|midroll|vast|vpaid|tracking|beacon|analytics|thumb|sprite|storyboard|preview)",
        Pattern.CASE_INSENSITIVE
    )
    private val SEG = Pattern.compile(
        "((seg|segment|chunk|frag|fragment)[-_.]?\\d|^init[-_.]|\\.(ts|m4s)$)", Pattern.CASE_INSENSITIVE
    )
    private val ABS = Pattern.compile("https?://[^\\s\"'<>\\\\)\\]}]+")
    private val REL = Pattern.compile(
        "[\"'(]((?:/|\\.\\./|\\./)?[^\"'\\s<>()]+?\\.(?:m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|3g2|ogv|avi|wmv|mpg|mpeg|f4v|asf|m2ts|mts)(?:\\?[^\"'\\s<>()]*)?)[\"')]",
        Pattern.CASE_INSENSITIVE
    )
    private val PROTO = Pattern.compile("[\"'](//[^\"'\\s<>]+?\\.(?:m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|3g2|ogv|avi|wmv|mpg|mpeg|f4v|asf|m2ts|mts)[^\"'\\s<>]*)[\"']", Pattern.CASE_INSENSITIVE)
    private val IFRAME = Pattern.compile("<iframe[^>]+?(?:src|data-src)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val SCRIPT = Pattern.compile("<script[^>]+?src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val SKIP_JS = Pattern.compile("(jquery|bootstrap|gtag|fbevents|analytics|adsbygoogle)", Pattern.CASE_INSENSITIVE)

    private fun clean(s: String): String = s
        .replace("&amp;", "&")
        .replace("\\u002F", "/").replace("\\u002f", "/")
        .replace("\\u0026", "&").replace("\\u003d", "=")
        .replace("\\/", "/")

    private fun join(base: String, ref: String): String = try {
        java.net.URL(java.net.URL(base), ref).toString()
    } catch (_: Throwable) {
        ""
    }

    private fun get(url: String, ref: String): String? = try {
        Http.get(url, Net.headers(url, ref), null, CancelFlag()) { c ->
            c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (out.size() < 2_000_000) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun origin(u: String): String = try {
        val p = URI(u)
        p.scheme + "://" + p.host + (if (p.port > 0) ":${p.port}" else "")
    } catch (_: Throwable) {
        ""
    }

    private fun isMediaUrl(u: String): Boolean {
        if (u.length > 2500 || AD.matcher(u).find()) return false
        val path = u.substringBefore('?').substringBefore('#')
        if (!MEDIA.matcher(path).find()) return false
        return !SEG.matcher(path.substringAfterLast('/')).find()
    }

    /** [cancelled] true dönerse tarama bırakılır. En çok 12 sayfa/script indirir. */
    fun scan(pageUrl: String, force: Boolean = false, cancelled: () -> Boolean): List<Hit> {
        if (!ENABLED && !force) return emptyList()
        val hits = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue.add(Triple(pageUrl, "", 0))
        var n = 0
        while (queue.isNotEmpty() && n < 12 && !cancelled()) {
            val (url, ref, depth) = queue.removeFirst()
            if (!seen.add(url)) continue
            val body = get(url, ref) ?: continue
            n++
            val head = body.take(300).trimStart()
            if (head.startsWith("#EXTM3U")) {
                hits.putIfAbsent(url, ref)
                continue
            }
            val t = clean(body)
            val m = ABS.matcher(t)
            while (m.find()) {
                val u = m.group().trimEnd(',', ';')
                if (isMediaUrl(u)) hits.putIfAbsent(u, url)
            }
            for (p in listOf(REL, PROTO)) {
                val r = p.matcher(t)
                while (r.find()) {
                    val u = join(url, r.group(1) ?: continue)
                    if (u.startsWith("http") && isMediaUrl(u)) hits.putIfAbsent(u, url)
                }
            }
            if (depth < 3) {
                val f = IFRAME.matcher(t)
                var fc = 0
                while (f.find() && fc++ < 6) {
                    val u = join(url, f.group(1) ?: continue)
                    if (u.startsWith("http") && !AD.matcher(u).find()) queue.add(Triple(u, url, depth + 1))
                }
                val s = SCRIPT.matcher(t)
                var sc = 0
                while (s.find() && sc < 6) {
                    val u = join(url, s.group(1) ?: continue)
                    if (u.startsWith("http") && !AD.matcher(u).find() && !SKIP_JS.matcher(u).find()) {
                        queue.add(Triple(u, url, depth + 1)); sc++
                    }
                }
            }
        }
        // Her adresin bulunduğu sayfayı Referer olarak hatırlat (Net.headers bunu uygular)
        for ((u, r) in hits) {
            if (r.isNotEmpty()) {
                Net.rememberRequestHeaders(u, mapOf("Referer" to r, "Origin" to origin(r)))
            }
        }
        return hits.map { Hit(it.key, it.value) }
    }
}
