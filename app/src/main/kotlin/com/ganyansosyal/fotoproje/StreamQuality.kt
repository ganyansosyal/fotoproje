package com.ganyansosyal.fotoproje

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class StreamType { HLS_VARIANT, HLS_MASTER, PROGRESSIVE }

/** Kullanıcıya gösterilecek tek bir kalite seçeneği. */
data class QualityOption(
    val label: String,              // "720p" | "Ses" | "Otomatik"
    val height: Int,                // 0 = bilinmiyor / otomatik
    val width: Int?,
    val peakBps: Long,              // BANDWIDTH (tepe, bit/sn)
    val avgBps: Long,               // AVERAGE-BANDWIDTH yoksa peakBps
    val codecs: String?,
    val frameRate: Double?,
    val url: String,                // mutlak adres
    val audioUrl: String?,          // ayrı ses grubu varsa
    val type: StreamType,
    val estimatedBytes: Long? = null
)

class ParsedStreams(val options: List<QualityOption>, val durationSec: Double?)

object StreamQuality {

    // ───────────────────────── Genel giriş ─────────────────────────

    /** Gövdenin türünü (m3u8 / JSON) otomatik anlar. Ağ isteği yapmaz. */
    fun parse(body: String, baseUrl: String): ParsedStreams {
        val t = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("#EXTM3U") -> ParsedStreams(parseHlsMaster(t, baseUrl), null)
            t.startsWith("{") || t.startsWith("[") -> parseJson(t, baseUrl)
            else -> ParsedStreams(emptyList(), null)
        }
    }

    /** Süre biliniyorsa tahmini boyutları doldurur: boyut ≈ ortalama bit hızı × süre / 8. */
    fun withEstimates(list: List<QualityOption>, durationSec: Double?): List<QualityOption> {
        if (durationSec == null || durationSec <= 0) return list
        return list.map {
            if (it.type == StreamType.HLS_VARIANT && it.avgBps > 0 && it.estimatedBytes == null)
                it.copy(estimatedBytes = (it.avgBps * durationSec / 8.0).toLong())
            else it
        }
    }

    /**
     * Aynı yükseklikten birden çok kodek varsa tek satıra indirir (avc1 öncelikli),
     * çözünürlüğe göre büyükten küçüğe sıralar.
     */
    fun dedupe(list: List<QualityOption>): List<QualityOption> =
        list.groupBy { it.type to it.height }
            .map { (_, g) ->
                g.sortedWith(
                    compareByDescending<QualityOption> { it.codecs?.contains("avc1") == true }
                        .thenByDescending { it.avgBps }
                ).first()
            }
            .sortedByDescending { it.height }

    /**
     * İstenen çözünürlüklere (ör. 720, 480, 380) en yakın seçeneği seçer.
     * Sitelerde 380 yerine genelde 360 bulunur; [tolerance] bu farkı kapatır.
     */
    fun pick(
        list: List<QualityOption>,
        targets: List<Int> = listOf(720, 480, 380),
        tolerance: Int = 40
    ): List<QualityOption> {
        val video = list.filter { it.height > 0 && it.type != StreamType.HLS_MASTER }
        return targets.mapNotNull { t ->
            video.minByOrNull { abs(it.height - t) }?.takeIf { abs(it.height - t) <= tolerance }
        }.distinct()
    }

    fun formatSize(bytes: Long?): String = when {
        bytes == null -> "?"
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        else -> String.format("%.1f MB", bytes / 1048576.0)
    }

    /** Medya (alt) listesinin toplam süresi. Canlı yayında (ENDLIST yok) null döner. */
    fun durationOf(mediaPlaylist: String): Double? {
        if (!mediaPlaylist.contains("#EXT-X-ENDLIST")) return null
        var sum = 0.0
        for (l in mediaPlaylist.lineSequence()) {
            if (l.startsWith("#EXTINF:"))
                sum += l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
        }
        return sum.takeIf { it > 0 }
    }

    // ───────────────────────── HLS master ─────────────────────────

    private fun parseHlsMaster(text: String, base: String): List<QualityOption> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        // Ayrı ses grupları: GROUP-ID -> URI (DEFAULT=YES varsa o tercih edilir)
        val audio = HashMap<String, String>()
        for (l in lines) {
            if (!l.startsWith("#EXT-X-MEDIA:")) continue
            val a = parseAttrs(l.substringAfter(':'))
            if (a["TYPE"] != "AUDIO") continue
            val uri = a["URI"] ?: continue
            val g = a["GROUP-ID"] ?: continue
            if (!audio.containsKey(g) || a["DEFAULT"] == "YES") audio[g] = resolve(base, uri)
        }

        val out = ArrayList<QualityOption>()
        var i = 0
        while (i < lines.size) {
            // "#EXT-X-I-FRAME-STREAM-INF" bu önekle eşleşmez; yani önizleme listeleri otomatik elenir.
            if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                val a = parseAttrs(lines[i].substringAfter(':'))
                var j = i + 1
                while (j < lines.size && lines[j].startsWith("#")) j++
                if (j >= lines.size) break

                val res = a["RESOLUTION"]?.split('x', 'X')
                val w = res?.getOrNull(0)?.toIntOrNull()
                val h = res?.getOrNull(1)?.toIntOrNull() ?: 0
                val peak = a["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val avg = a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: peak

                out += QualityOption(
                    label = if (h > 0) "${h}p" else "Ses",
                    height = h, width = w,
                    peakBps = peak, avgBps = avg,
                    codecs = a["CODECS"],
                    frameRate = a["FRAME-RATE"]?.toDoubleOrNull(),
                    url = resolve(base, lines[j]),
                    audioUrl = a["AUDIO"]?.let { audio[it] },
                    type = StreamType.HLS_VARIANT
                )
                i = j
            }
            i++
        }

        // Dosya aslında alt liste ise (STREAM-INF yok) tek seçenek olarak döndür.
        if (out.isEmpty() && lines.any { it.startsWith("#EXTINF") }) {
            out += QualityOption("Tek kalite", 0, null, 0, 0, null, null, base, null, StreamType.HLS_VARIANT)
        }
        return out
    }

    /** `KEY=değer,KEY2="tırnaklı,değer"` biçimini doğru ayrıştırır (CODECS içindeki virgüller bozmaz). */
    internal fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        val n = s.length
        var i = 0
        while (i < n) {
            while (i < n && (s[i] == ',' || s[i] == ' ')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().uppercase()
            i = eq + 1
            if (i < n && s[i] == '"') {
                val end = s.indexOf('"', i + 1).let { if (it < 0) n else it }
                out[key] = s.substring(i + 1, end)
                i = end + 1
            } else {
                val end = s.indexOf(',', i).let { if (it < 0) n else it }
                out[key] = s.substring(i, end).trim()
                i = end
            }
        }
        return out
    }

    // ───────────────────────── JSON kalite listesi ─────────────────────────

    /**
     * Genel amaçlı JSON tarayıcı. Şu biçimleri yakalar:
     *   {"qualities":{"auto":[{"type":"application/x-mpegURL","url":"…"}],"720":[{"type":"video/mp4","url":"…"}]}}
     *   [{"height":720,"url":"…"}, {"quality":"480p","src":"…"}]
     * Şema siteye göre değişir; gerekirse [heightOf] ve anahtar adlarını genişlet.
     */
    private fun parseJson(text: String, base: String): ParsedStreams {
        val root: Any = try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (_: Exception) {
            return ParsedStreams(emptyList(), null)
        }
        val out = ArrayList<QualityOption>()
        walk(root, null, base, out)
        val dur = (root as? JSONObject)?.optDouble("duration", 0.0)?.takeIf { it > 0 }
        return ParsedStreams(out, dur)
    }

    private fun walk(node: Any?, keyHint: String?, base: String, out: MutableList<QualityOption>) {
        when (node) {
            is JSONObject -> {
                val url = listOf("url", "src", "file", "href")
                    .map { node.optString(it) }
                    .firstOrNull { it.startsWith("http") || it.startsWith("/") }
                if (url != null) {
                    val abs = resolve(base, url)
                    val isM3u8 = node.optString("type").contains("mpegurl", true) ||
                        abs.substringBefore('?').endsWith(".m3u8")
                    val h = heightOf(node, keyHint)
                    val bps = node.optLong("bitrate", node.optLong("bandwidth", 0L))
                    out += QualityOption(
                        label = if (h > 0) "${h}p" else "Otomatik",
                        height = h, width = node.optInt("width").takeIf { it > 0 },
                        peakBps = bps, avgBps = bps, codecs = null, frameRate = null,
                        url = abs, audioUrl = null,
                        type = if (isM3u8) StreamType.HLS_MASTER else StreamType.PROGRESSIVE
                    )
                } else {
                    val it = node.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        walk(node.opt(k), k, base, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), keyHint, base, out)
        }
    }

    private val DIGITS = Regex("(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE)

    private fun heightOf(o: JSONObject, keyHint: String?): Int {
        o.optInt("height", 0).takeIf { it > 0 }?.let { return it }
        for (k in listOf("quality", "label", "name", "resolution")) {
            DIGITS.find(o.optString(k))?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return keyHint?.let { DIGITS.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    }

    private fun resolve(base: String, ref: String): String = try {
        java.net.URL(java.net.URL(base), ref).toString()
    } catch (_: Exception) {
        ref
    }
}

/**
 * Ağdan veri çeken katman: yakalanan adresten tam kalite listesini üretir.
 * [fetch] gerekli başlıkları (Referer/Cookie/UA) ekleyerek gövdeyi döndürmelidir.
 * Bloklayıcıdır; arka plan iş parçacığından çağır.
 */
class QualityCatalog(
    private val fetch: (url: String) -> String,
    private val headLength: ((url: String) -> Long?)? = null   // isteğe bağlı: mp4 için Content-Length
) {
    fun load(url: String): List<QualityOption> {
        val first = StreamQuality.parse(fetch(url), url)
        var duration = first.durationSec

        // JSON içinde gömülü ana liste (ör. "auto") varsa onu da aç.
        val expanded = ArrayList<QualityOption>()
        for (o in first.options) {
            if (o.type == StreamType.HLS_MASTER && o.url != url) {
                val sub = runCatching { StreamQuality.parse(fetch(o.url), o.url) }.getOrNull()
                if (sub != null && sub.options.isNotEmpty()) { expanded += sub.options; continue }
            }
            expanded += o
        }

        // Süre JSON'dan gelmediyse tek bir alt listeden hesapla.
        if (duration == null) {
            duration = expanded.firstOrNull { it.type == StreamType.HLS_VARIANT && it.height > 0 }
                ?.let { runCatching { StreamQuality.durationOf(fetch(it.url)) }.getOrNull() }
        }

        val sized = StreamQuality.withEstimates(expanded, duration).map {
            if (it.type == StreamType.PROGRESSIVE && it.estimatedBytes == null)
                it.copy(estimatedBytes = runCatching { headLength?.invoke(it.url) }.getOrNull())
            else it
        }
        return StreamQuality.dedupe(sized)
    }
}
