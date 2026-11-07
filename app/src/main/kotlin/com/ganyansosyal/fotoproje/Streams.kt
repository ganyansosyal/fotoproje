package com.ganyansosyal.fotoproje

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

typealias Report = (Double, String) -> Unit

/** HLS / DASH / doğrudan dosya indirme (eski engine.dart'ın Kotlin karşılığı). */
object Streams {
    private fun lines(body: String) = body.split('\n').map { it.trim() }

    private fun saveSegments(
        init: String?, urls: List<String>, path: String, report: Report, label: String,
        headers: Map<String, String>, cancel: CancelFlag, start: Double, span: Double,
        keys: List<String?>? = null, ivs: List<ByteArray?>? = null, seq: Long = 0
    ) {
        if (urls.isEmpty()) throw IOException("Video parçaları bulunamadı")
        Native.segments(urls, init, path, headers, keys, ivs, seq, 12, cancel) { a, b ->
            if (b > 0) report(start + span * a / b, "$label: $a/$b")
        }
    }

    fun direct(
        url: String, path: String, headers: Map<String, String>, cancel: CancelFlag,
        report: Report, conns: Int = 12, strict: Boolean = false
    ) {
        Native.direct(url, path, headers, conns, strict, cancel) { a, b ->
            if (b > 0) report(a.toDouble() / b, "%" + (a * 100 / b))
            else report(-1.0, String.format("%.1f MB", a / 1048576.0))
        }
    }

    /** Videoyu ve sesi aynı anda indirir (ses küçük olduğu için ilerleme videodan gösterilir). */
    private fun parallel(video: () -> Unit, audio: () -> Unit) {
        var err: Throwable? = null
        val t = Thread {
            try { audio() } catch (e: Throwable) { err = e }
        }
        t.start()
        try {
            video()
        } catch (e: Throwable) {
            t.interrupt()
            throw e
        }
        t.join()
        err?.let { throw it }
    }

    private val SILENT: Report = { _, _ -> }

    /** Dönüş: ayrı ses dosyası yazıldı mı */
    fun hls(
        url: String, path: String, audioPath: String, referer: String,
        report: Report, cancel: CancelFlag
    ): Boolean {
        val got = Net.fetchText(url, referer, cancel)
        val h = got.headers
        var pl = url
        val ls = lines(got.body)
        var audioUrl: String? = null
        if (ls.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            var best: String? = null
            var group: String? = null
            var bw = -1
            for (i in ls.indices) {
                if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
                val b = Media.hlsAttr(ls[i], "BANDWIDTH")?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < ls.size && (ls[j].isEmpty() || ls[j].startsWith("#"))) j++
                if (j < ls.size && b > bw) {
                    bw = b
                    best = ls[j]
                    group = Media.hlsAttr(ls[i], "AUDIO")
                }
            }
            if (best == null) throw IOException("Video listesi bulunamadı")
            val master = pl
            pl = Media.resolve(master, best)
            if (group != null) {
                var any: String? = null
                var def: String? = null
                for (l in ls) {
                    if (!l.startsWith("#EXT-X-MEDIA") || Media.hlsAttr(l, "TYPE") != "AUDIO" ||
                        Media.hlsAttr(l, "GROUP-ID") != group
                    ) continue
                    val u = Media.hlsAttr(l, "URI") ?: continue
                    if (any == null) any = u
                    if (Media.hlsAttr(l, "DEFAULT") == "YES" && def == null) def = u
                }
                val a = def ?: any
                if (a != null) audioUrl = Media.resolve(master, a)
            }
        }
        val hasAudio = audioUrl != null
        if (hasAudio) {
            parallel(
                { hlsMedia(pl, path, report, "Video", h, cancel, 0.0, 1.0) },
                { hlsMedia(audioUrl!!, audioPath, SILENT, "Ses", h, cancel, 0.0, 1.0) }
            )
            return true
        }
        hlsMedia(pl, path, report, "Video", h, cancel, 0.0, 1.0)
        return false
    }

    private fun hlsMedia(
        pl: String, path: String, report: Report, label: String,
        h0: Map<String, String>, cancel: CancelFlag, start: Double, span: Double
    ) {
        val got = Net.fetchText(pl, h0["Referer"] ?: "", cancel)
        val h = got.headers
        var seq = 0L
        var init: String? = null
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        val segs = ArrayList<String>()
        val segKey = ArrayList<String?>()
        val segIv = ArrayList<ByteArray?>()
        for (l in lines(got.body)) {
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
            } else if (l.startsWith("#EXT-X-KEY")) {
                val method = Media.hlsAttr(l, "METHOD")
                if (method == null || method == "NONE") {
                    keyUri = null
                    keyIv = null
                } else if (method == "AES-128") {
                    val k = Media.hlsAttr(l, "URI") ?: throw IOException("Şifre anahtarı bulunamadı")
                    keyUri = Media.resolve(pl, k)
                    keyIv = Media.hlsAttr(l, "IV")?.let { Media.hexBytes(it) }
                } else throw IOException("Şifreli yayın ($method) desteklenmiyor")
            } else if (l.startsWith("#EXT-X-MAP")) {
                Media.hlsAttr(l, "URI")?.let { init = Media.resolve(pl, it) }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                segs.add(Media.resolve(pl, l))
                segKey.add(keyUri)
                segIv.add(keyIv)
            }
        }
        saveSegments(init, segs, path, report, label, h, cancel, start, span, segKey, segIv, seq)
    }

    // ------------------------------------------------------------------ DASH
    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val n = childNodes
        for (i in 0 until n.length) {
            val e = n.item(i) as? Element ?: continue
            if (e.tagName.substringAfter(':') == name) out.add(e)
        }
        return out
    }

    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()
    private fun Element.attr(n: String): String? = if (hasAttribute(n)) getAttribute(n) else null

    fun parseXml(txt: String): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(txt.toByteArray(Charsets.UTF_8))).documentElement
    }

    private val TPL = Regex("""\$(RepresentationID|Number|Bandwidth|Time)(?:%0(\d+)d)?\$""")

    fun dash(
        url: String, path: String, audioPath: String, referer: String,
        report: Report, cancel: CancelFlag
    ): Boolean {
        val got = Net.fetchText(url, referer, cancel)
        val h = got.headers
        val mpd = parseXml(got.body)
        val period = mpd.kid("Period") ?: throw IOException("Geçersiz DASH")
        val pdur = Media.isoSeconds(period.attr("duration"))
            ?: Media.isoSeconds(mpd.attr("mediaPresentationDuration")) ?: 0.0

        fun baseOf(b: String, e: Element): String {
            val bu = e.kid("BaseURL")?.textContent?.trim()
            return if (bu.isNullOrEmpty()) b else Media.resolve(b, bu)
        }

        val b0 = baseOf(baseOf(url, mpd), period)
        var bestSet: Element? = null
        var bestRep: Element? = null
        var bestASet: Element? = null
        var bestARep: Element? = null
        var vb = -1
        var ab = -1
        for (s in period.kids("AdaptationSet")) {
            for (r in s.kids("Representation")) {
                val mime = r.attr("mimeType") ?: s.attr("mimeType") ?: s.attr("contentType") ?: ""
                val b = r.attr("bandwidth")?.toIntOrNull() ?: 0
                if (mime.startsWith("video") && b > vb) {
                    vb = b; bestSet = s; bestRep = r
                } else if (mime.startsWith("audio") && b > ab) {
                    ab = b; bestASet = s; bestARep = r
                }
            }
        }
        if (bestRep == null && bestARep != null) {
            bestSet = bestASet; bestRep = bestARep; bestASet = null; bestARep = null
        }
        if (bestSet == null || bestRep == null) throw IOException("Video bulunamadı")
        val hasAudio = bestASet != null && bestARep != null

        fun grab(s: Element, r: Element, out: String, label: String, start: Double, span: Double, rep: Report = report) {
            val id = r.attr("id") ?: ""
            val bw = r.attr("bandwidth") ?: "0"
            val b = baseOf(baseOf(b0, s), r)
            fun fill(t: String, number: Long = 0, time: Long = 0): String =
                TPL.replace(t) { m ->
                    val w = m.groupValues[2].toIntOrNull() ?: 0
                    when (m.groupValues[1]) {
                        "RepresentationID" -> id
                        "Bandwidth" -> bw
                        "Number" -> number.toString().padStart(w, '0')
                        else -> time.toString().padStart(w, '0')
                    }
                }.replace("$$", "$")

            val st = r.kid("SegmentTemplate") ?: s.kid("SegmentTemplate")
            val sl = r.kid("SegmentList") ?: s.kid("SegmentList")
            var init: String? = null
            val urls = ArrayList<String>()
            if (st != null) {
                st.attr("initialization")?.let { init = Media.resolve(b, fill(it)) }
                val media = st.attr("media") ?: throw IOException("Geçersiz DASH")
                val ts = st.attr("timescale")?.toLongOrNull() ?: 1L
                val startN = st.attr("startNumber")?.toLongOrNull() ?: 1L
                val tl = st.kid("SegmentTimeline")
                if (tl != null) {
                    var time = 0L
                    var n = startN
                    for (e in tl.kids("S")) {
                        time = e.attr("t")?.toLongOrNull() ?: time
                        val d = e.attr("d")!!.toLong()
                        var rep = e.attr("r")?.toIntOrNull() ?: 0
                        if (rep < 0) rep = Math.ceil((pdur * ts - time) / d).toInt() - 1
                        for (k in 0..rep) {
                            urls.add(Media.resolve(b, fill(media, n, time)))
                            time += d
                            n++
                        }
                    }
                } else {
                    val dur = st.attr("duration")?.toLongOrNull()
                    if (dur == null || dur == 0L || pdur == 0.0) throw IOException("DASH süresi bulunamadı")
                    val count = Math.ceil(pdur * ts / dur).toInt()
                    for (k in 0 until count) urls.add(Media.resolve(b, fill(media, startN + k)))
                }
            } else if (sl != null) {
                sl.kid("Initialization")?.attr("sourceURL")?.let { init = Media.resolve(b, it) }
                for (e in sl.kids("SegmentURL")) e.attr("media")?.let { urls.add(Media.resolve(b, it)) }
            } else {
                urls.add(b)
            }
            saveSegments(init, urls, out, rep, label, h, cancel, start, span)
        }

        if (hasAudio) {
            parallel(
                { grab(bestSet, bestRep, path, "Video", 0.0, 1.0) },
                { grab(bestASet!!, bestARep!!, audioPath, "Ses", 0.0, 1.0, SILENT) }
            )
            return true
        }
        grab(bestSet, bestRep, path, "Video", 0.0, 1.0)
        return false
    }
}
