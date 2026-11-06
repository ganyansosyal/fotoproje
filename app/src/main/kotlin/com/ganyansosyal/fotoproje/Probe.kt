package com.ganyansosyal.fotoproje

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Bulunan bir video bağlantısının incelenmiş hâli (eski probe.dart). */
class MediaInfo(val url: String, var kind: String) {
    var duration = 0.0
    var height = 0
    var size = 0L
    var usable = true
    var verified = false

    val isStream get() = kind == "m3u8" || kind == "mpd"
    val host get() = hostOf(url)

    val title: String
        get() {
            val p = ArrayList<String>()
            if (height > 0) p.add("${height}p")
            if (duration > 0) p.add(Media.fmtDuration(duration))
            if (size > 0) p.add(Media.fmtBytes(size))
            if (p.isEmpty()) return if (kind == "blob") L.t("protected") else kind.uppercase()
            return p.joinToString(" · ")
        }

    val subtitle: String
        get() {
            val k = if (isStream) L.t("stream") else if (kind == "blob") "Blob" else L.t("file")
            return "$k · ${kind.uppercase()} · $host"
        }

    val rank: Double
        get() {
            if (!usable) return -1.0
            if (kind == "blob") return 0.0
            return (if (verified) 1e6 else 0.0) + 1000 + duration * 10 + height + size / 1e6
        }
}

object Probe {
    private val pool = Executors.newCachedThreadPool()

    private fun extinfTotal(ls: List<String>): Double {
        var total = 0.0
        for (l in ls) if (l.startsWith("#EXTINF:")) {
            total += l.substring(8).split(',')[0].toDoubleOrNull() ?: 0.0
        }
        return total
    }

    private fun text(url: String, referer: String) = Net.fetchText(url, referer).body

    /** Uzantısı/ipucu olmayan adreste ilk baytlara bakarak biçimi bulur. */
    private fun sniffKind(url: String, referer: String): String? {
        try {
            val d = Http.get(url, Net.headers(url, referer), "bytes=0-1023", CancelFlag()) { c ->
                c.inputStream.use { ins ->
                    val buf = ByteArray(1024)
                    var n = 0
                    while (n < buf.size) {
                        val r = ins.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
            }
            if (d.size < 8) return null
            val head = String(d, 0, minOf(d.size, 600), Charsets.ISO_8859_1)
            if (head.trimStart().startsWith("#EXTM3U")) return "m3u8"
            if (head.contains("<MPD")) return "mpd"
            if (d[4] == 0x66.toByte() && d[5] == 0x74.toByte() && d[6] == 0x79.toByte() && d[7] == 0x70.toByte()) return "mp4"
            if (d[0] == 0x1A.toByte() && d[1] == 0x45.toByte() && d[2] == 0xDF.toByte() && d[3] == 0xA3.toByte()) return "webm"
        } catch (_: Throwable) {
        }
        return null
    }

    private fun probeOne(url: String, hint: String, referer: String, children: MutableSet<String>): MediaInfo {
        var kind = Media.kind(url) ?: hint
        if (kind.isEmpty()) kind = sniffKind(url, referer) ?: "mp4"
        val info = MediaInfo(url, kind)
        try {
            if (kind == "blob") return info
            if (kind == "m3u8") {
                var ls = text(url, referer).split('\n').map { it.trim() }
                var base = url
                if (ls.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                    for (i in ls.indices) {
                        val l = ls[i]
                        if (l.startsWith("#EXT-X-STREAM-INF")) {
                            var j = i + 1
                            while (j < ls.size && (ls[j].isEmpty() || ls[j].startsWith("#"))) j++
                            if (j < ls.size) synchronized(children) { children.add(Media.resolve(base, ls[j])) }
                        } else if (l.startsWith("#EXT-X-MEDIA")) {
                            val u = Media.hlsAttr(l, "URI")
                            if (!u.isNullOrEmpty()) synchronized(children) { children.add(Media.resolve(base, u)) }
                        }
                    }
                    var bw = -1
                    var best: String? = null
                    for (i in ls.indices) {
                        if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
                        val b = Media.hlsAttr(ls[i], "BANDWIDTH")?.toIntOrNull() ?: 0
                        var j = i + 1
                        while (j < ls.size && (ls[j].isEmpty() || ls[j].startsWith("#"))) j++
                        if (j < ls.size && b > bw) {
                            bw = b
                            best = ls[j]
                            val res = Media.hlsAttr(ls[i], "RESOLUTION")
                            if (res != null && res.contains('x')) info.height = res.substringAfter('x').toIntOrNull() ?: 0
                        }
                    }
                    if (best == null) {
                        info.usable = false
                        return info
                    }
                    val vu = Media.resolve(base, best)
                    ls = text(vu, referer).split('\n').map { it.trim() }
                    base = vu
                }
                val segs = ls.filter { it.isNotEmpty() && !it.startsWith("#") }
                if (segs.isEmpty()) {
                    info.usable = false
                    return info
                }
                val first = segs.first().lowercase().substringBefore('?')
                val isText = first.endsWith(".vtt") || first.endsWith(".webvtt") || first.endsWith(".srt")
                val isImg = first.endsWith(".jpg") || first.endsWith(".jpeg") || first.endsWith(".png") || first.endsWith(".webp")
                if (isText || (isImg && ls.any { it.startsWith("#EXT-X-IMAGES-ONLY") })) {
                    info.usable = false
                    return info
                }
                for (l in ls) if (l.startsWith("#EXT-X-KEY")) {
                    val m = Media.hlsAttr(l, "METHOD")
                    if (m != null && m != "NONE" && m != "AES-128") info.usable = false
                }
                info.duration = extinfTotal(ls)
                return info
            }
            if (kind == "mpd") {
                val mpd = Streams.parseXml(text(url, referer))
                val period = mpd.getElementsByTagName("Period").item(0) as? org.w3c.dom.Element
                info.duration = Media.isoSeconds(mpd.getAttribute("mediaPresentationDuration").ifEmpty { null })
                    ?: Media.isoSeconds(period?.getAttribute("duration")?.ifEmpty { null }) ?: 0.0
                var h = 0
                val reps = mpd.getElementsByTagName("Representation")
                for (i in 0 until reps.length) {
                    val hh = (reps.item(i) as org.w3c.dom.Element).getAttribute("height").toIntOrNull() ?: 0
                    if (hh > h) h = hh
                }
                info.height = h
                return info
            }
            // Doğrudan dosya: 1 baytlık aralık isteğiyle başlığa bak
            val cancel = CancelFlag()
            Http.get(url, Net.headers(url, referer), "bytes=0-0", cancel) { c ->
                val ct = (c.contentType ?: "").lowercase()
                val cr = c.getHeaderField("Content-Range") ?: ""
                val total = Regex("/(\\d+)\\s*$").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                val len = total ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                c.disconnect()
                if (ct.startsWith("image/") || ct.startsWith("text/") || ct.contains("json")) info.usable = false
                info.size = len
                if (len in 1 until 150 * 1024) info.usable = false
                Unit
            }
        } catch (_: Throwable) {
            info.duration = 0.0
        }
        return info
    }

    /** Adayları paralel inceler; oynayan videonun süresiyle eşleşenleri doğrulanmış sayar. */
    fun probeAll(
        urls: List<String>, hints: Map<String, String>, referer: String,
        expectDur: Double = 0.0, exactSrc: String = "", height: Int = 0
    ): List<MediaInfo> {
        val children = HashSet<String>()
        val futures = urls.take(14).map { u ->
            pool.submit(Callable { probeOne(u, hints[u] ?: "", referer, children) })
        }
        val list = futures.map { try { it.get() } catch (_: Throwable) { null } }.filterNotNull()
        val strict = expectDur >= 90
        var ok = list.filter { it.usable && !synchronized(children) { children.contains(it.url) } }
        for (e in ok) {
            if (exactSrc.isNotEmpty() && e.url == exactSrc) {
                if (e.duration <= 0) e.duration = expectDur
                if (e.height <= 0) e.height = height
                if (strict) e.verified = true
            } else if (strict && e.duration > 0) {
                val tol = maxOf(4.0, expectDur * 0.04)
                if (Math.abs(e.duration - expectDur) <= tol) e.verified = true else e.usable = false
            }
        }
        ok = ok.filter { it.usable }
        if (ok.any { it.verified }) ok = ok.filter { it.verified }
        ok = ok.sortedByDescending { it.rank }
        val seen = HashSet<String>()
        val out = ArrayList<MediaInfo>()
        for (e in ok) {
            val key = if (e.duration > 0) "${e.kind}|${Math.round(e.duration).toInt() / 3}|${e.height}" else e.url
            if (seen.add(key)) out.add(e)
        }
        return out
    }
}
