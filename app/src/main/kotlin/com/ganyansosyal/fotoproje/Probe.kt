package com.ganyansosyal.fotoproje

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Bulunan bir video bağlantısının incelenmiş hâli (eski probe.dart). */
class Variant(val bw: Int, val height: Int, val audio: Boolean = true)

class MediaInfo(val url: String, var kind: String) {
    var variants: List<Variant> = emptyList()
    var bw = 0
    var duration = 0.0
    var height = 0
    var size = 0L
    var usable = true
    var verified = false
    /** true: sesli, false: sessiz (yalnızca görüntü), null: bilinmiyor */
    var audio: Boolean? = null

    val isStream get() = kind == "m3u8" || kind == "mpd"
    val host get() = hostOf(url)

    val title: String
        get() {
            val p = ArrayList<String>()
            if (height > 0) p.add("${height}p")
            if (duration > 0) p.add(Media.fmtDuration(duration))
            if (size > 0) p.add(Media.fmtBytes(size))
            if (audio == false) p.add(if (L.code == "tr") "sessiz" else "no audio")
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

    /**
     * Liste getirme geçici hatalarda (ağ kısıtı, arka plandan dönüş, anlık 403/5xx) başarısız olabilir;
     * süre/boyut "bilinmiyor" kalmasın diye kısa aralıklarla birkaç kez dener.
     */
    private fun textRetry(url: String, referer: String, tries: Int = 3): String {
        var last: Throwable? = null
        for (k in 0 until tries) {
            try {
                return text(url, referer)
            } catch (e: Throwable) {
                if (e is CanceledEx) throw e
                last = e
                if (k < tries - 1) try { Thread.sleep(700L * (k + 1)) } catch (_: InterruptedException) { break }
            }
        }
        throw last ?: java.io.IOException("İstek başarısız")
    }

    /** Uzantısı/ipucu olmayan adreste ilk baytlara bakarak biçimi bulur. */
    private fun sniffKind(url: String, referer: String): String? {
        try {
            var ctype = ""
            val d = Http.get(url, Net.headers(url, referer), "bytes=0-1023", CancelFlag()) { c ->
                ctype = (c.contentType ?: "").lowercase()
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
            // TS / MPEG parçaları video değil, yalnızca parçadır
            if (ctype.contains("mp2t") || ctype.contains("mpeg2")) return null
            if (ctype.contains("mpegurl")) return "m3u8"
            if (ctype.contains("dash+xml")) return "mpd"
            if (d.size < 8) return null
            val head = String(d, 0, minOf(d.size, 600), Charsets.ISO_8859_1)
            if (head.trimStart().startsWith("#EXTM3U")) return "m3u8"
            if (head.contains("<MPD")) return "mpd"
            if (d[4] == 0x66.toByte() && d[5] == 0x74.toByte() && d[6] == 0x79.toByte() && d[7] == 0x70.toByte()) return "mp4"
            // moov / mdat / free / wide ile başlayan MP4 dosyaları
            val box = String(d, 4, 4, Charsets.ISO_8859_1)
            if (box == "moov" || box == "mdat" || box == "free" || box == "wide") return "mp4"
            if (ctype.startsWith("video/webm")) return "webm"
            if (d[0] == 0x1A.toByte() && d[1] == 0x45.toByte() && d[2] == 0xDF.toByte() && d[3] == 0xA3.toByte()) return "webm"
        } catch (_: Throwable) {
        }
        return null
    }

    /** Dosyanın görüntü / ses izi var mı (görüntü, ses). Okunamazsa null: bilinmiyor sayılır. */
    private fun tracks(url: String, referer: String, durOut: DoubleArray? = null): Pair<Boolean, Boolean>? {
        var res: Pair<Boolean, Boolean>? = null
        val t = Thread {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(url.substringBefore("#"), Net.headers(url, referer))
                var v = false
                var a = false
                var maxDur = 0.0
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        val dsec = f.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000.0
                        if (dsec > maxDur) maxDur = dsec
                    }
                    val m = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) v = true
                    if (m.startsWith("audio/")) a = true
                }
                if (durOut != null && durOut.isNotEmpty()) durOut[0] = maxDur
                res = Pair(v, a)
            } catch (_: Throwable) {
            } finally {
                try { ex.release() } catch (_: Throwable) {}
            }
        }
        t.isDaemon = true
        t.start()
        t.join(8000)
        if (t.isAlive) return null
        return res
    }

    private fun isAudioCodec(c: String) =
        listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "mp3", "vorbis").any { c.contains(it) }

    private fun probeOne(url: String, hint: String, referer: String, children: MutableSet<String>): MediaInfo {
        var kind = Media.kind(url) ?: hint
        if (kind == "sniff") {
            // Ağdan uzantısız yakalanan adres: gerçekten video değilse aday olmaz
            val k = sniffKind(url, referer)
            if (k == null) return MediaInfo(url, "mp4").also { it.usable = false }
            kind = k
        }
        if (kind.isEmpty()) kind = sniffKind(url, referer) ?: "mp4"
        val info = MediaInfo(url, kind)
        try {
            if (kind == "blob") return info
            if (kind == "m3u8") {
                var ls = textRetry(url, referer).split('\n').map { it.trim() }
                var base = url
                if (ls.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                    // Ana listedeki tüm kalite seçeneklerini topla
                    val vs = ArrayList<Variant>()
                    for (l in ls) if (l.startsWith("#EXT-X-STREAM-INF")) {
                        val b = Media.hlsAttr(l, "BANDWIDTH")?.toIntOrNull() ?: 0
                        val r = Media.hlsAttr(l, "RESOLUTION")
                        val hh = if (r != null && r.contains('x')) r.substringAfter('x').toIntOrNull() ?: 0 else 0
                        // Ses var mı: ayrı ses grubunda adres varsa ya da CODECS'te ses kodeği varsa
                        val codecs = Media.hlsAttr(l, "CODECS")?.lowercase()
                        val grp = Media.hlsAttr(l, "AUDIO")
                        val grpUri = grp != null && ls.any {
                            it.startsWith("#EXT-X-MEDIA") && Media.hlsAttr(it, "TYPE") == "AUDIO" &&
                                Media.hlsAttr(it, "GROUP-ID") == grp && !Media.hlsAttr(it, "URI").isNullOrEmpty()
                        }
                        val hasAudio = grpUri || (codecs?.let { isAudioCodec(it) } ?: true)
                        if (b > 0 || hh > 0) vs.add(Variant(b, hh, hasAudio))
                    }
                    // Çözünürlüğü olan satırlar varsa, çözünürlüksüz (ses / yan liste) satırlar kalite sayılmaz
                    // Sesli seçenek varsa sessiz (yalnızca görüntü) olanlar listeden çıkar
                    val voiced = if (vs.any { it.audio }) vs.filter { it.audio } else vs
                    info.audio = voiced.any { it.audio }
                    val withRes = voiced.filter { it.height > 0 }
                    info.variants = (if (withRes.isNotEmpty()) withRes else voiced)
                        .distinctBy { it.bw.toLong() * 100000 + it.height }
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
                    ls = textRetry(vu, referer).split('\n').map { it.trim() }
                    base = vu
                }
                val segs = ls.filter { it.isNotEmpty() && !it.startsWith("#") }
                if (segs.isEmpty()) {
                    info.usable = false
                    return info
                }
                val first = segs.first().lowercase().substringBefore('?')
                val isAudioSeg = first.endsWith(".aac") || first.endsWith(".mp3") || first.endsWith(".m4a") ||
                    first.endsWith(".ac3") || first.endsWith(".eac3")
                if (isAudioSeg) {
                    info.usable = false
                    return info
                }
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
                val bestBw = HashMap<Int, Int>() // çözünürlük -> en yüksek bant genişliği
                for (i in 0 until reps.length) {
                    val el = reps.item(i) as org.w3c.dom.Element
                    val hh = el.getAttribute("height").toIntOrNull() ?: 0
                    if (hh > h) h = hh
                    if (hh > 0) {
                        val bb = el.getAttribute("bandwidth").toIntOrNull() ?: 0
                        if (bb >= (bestBw[hh] ?: -1)) bestBw[hh] = bb
                    }
                }
                info.height = h
                var dashAudio = false
                val sets = mpd.getElementsByTagName("AdaptationSet")
                for (i in 0 until sets.length) {
                    val el = sets.item(i) as org.w3c.dom.Element
                    val rep0 = el.getElementsByTagName("Representation").item(0) as? org.w3c.dom.Element
                    val mt = (el.getAttribute("mimeType").ifEmpty { el.getAttribute("contentType") }
                        .ifEmpty { rep0?.getAttribute("mimeType") ?: "" }).lowercase()
                    val cd = (el.getAttribute("codecs").ifEmpty { rep0?.getAttribute("codecs") ?: "" }).lowercase()
                    if (mt.startsWith("audio") || (mt.isEmpty() && isAudioCodec(cd))) dashAudio = true
                }
                info.audio = dashAudio
                info.variants = bestBw.entries.map { Variant(it.value, it.key) }
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
                // Ağdan uzantısız yakalanan adres: küçük dosya parçadır, gerçek video sayılmaz
                if (hint == "sniff" && len < 8L * 1024 * 1024) info.usable = false
                Unit
            }
            if (info.usable && kind != "blob") {
                // Görüntü/ses izi kontrolü: yalnızca-ses dosyası aday olmaz, sessiz olan işaretlenir
                val dur = DoubleArray(1)
                val tr = tracks(url, referer, dur)
                // Süre bilinirse parça (kısa) dosyalar ayıklanabilir, oynayan videoyla eşleştirilebilir
                if (info.duration <= 0 && dur[0] > 0) info.duration = dur[0]
                if (tr != null) {
                    if (!tr.first && tr.second) {
                        info.usable = false
                    } else {
                        info.audio = tr.second
                    }
                }
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
        val futures = urls.distinct().take(40).map { u ->
            pool.submit(Callable { probeOne(u, hints[u] ?: "", referer, children) })
        }
        val list = futures.map { try { it.get() } catch (_: Throwable) { null } }.filterNotNull()
        // Oynayan videonun süresi biliniyorsa (kısa klipler dâhil) eşleşmeyenleri ele
        // Reklam oynarken oynayan videonun süresi kısadır (15-60 sn); gerçek video (uzun) adayı bu süreyle
        // elenmesin diye, uzun aday varken kısa süreli bir oynatmayı reklam sayıp katı eşleştirmeyi bırak.
        val longExists = list.any { it.usable && it.duration >= 180 }
        val adPlaying = expectDur in 1.0..90.0 && longExists
        val strict = expectDur >= 5 && !adPlaying
        var ok = list.filter { it.usable && !synchronized(children) { children.contains(it.url) } }
        // Hem görüntü hem ses olan seçenek varsa sessiz (yalnızca görüntü) olanları gösterme
        if (ok.any { it.audio == true }) ok = ok.filter { it.audio != false }
        // Reklam oynuyorsa kısa (reklam/önizleme) adayları listeden çıkar
        if (adPlaying) {
            val noAd = ok.filter { it.duration <= 0 || it.duration >= 120 }
            if (noAd.isNotEmpty()) ok = noAd
        }
        // Aynı klasörde çok sayıda dosya = HLS/DASH parçaları (seg1.mp4, seg2.mp4 ...)
        fun dirOf(u: String) = u.substringBefore('?').substringBeforeLast('/')
        val segDirs = ok.filter { !it.isStream && it.url != exactSrc && !it.verified }
            .groupBy { dirOf(it.url) }.filter { it.value.size >= 3 }.keys
        if (segDirs.isNotEmpty()) {
            val kept = ok.filter { it.isStream || it.url == exactSrc || dirOf(it.url) !in segDirs }
            if (kept.isNotEmpty()) ok = kept
        }
        val mismatch = HashSet<MediaInfo>()
        for (e in ok) {
            if (exactSrc.isNotEmpty() && e.url == exactSrc) {
                if (e.duration <= 0) e.duration = expectDur
                if (e.height <= 0) e.height = height
                if (strict) e.verified = true
            } else if (strict && e.duration > 0) {
                val tol = maxOf(2.5, expectDur * 0.04)
                if (Math.abs(e.duration - expectDur) <= tol) e.verified = true else mismatch.add(e)
            }
        }
        // Hepsi eşleşmediyse (süre yanlış bildirilmiş olabilir) boş bırakma
        val okMatch = ok.filter { it !in mismatch }
        if (okMatch.isNotEmpty()) ok = okMatch
        if (ok.any { it.verified }) ok = ok.filter { it.verified }
        ok = ok.sortedByDescending { it.rank }
        if (ok.size > 8) ok = ok.take(8)
        val seen = HashSet<String>()
        val out = ArrayList<MediaInfo>()
        for (e in ok) {
            val key = if (e.duration > 0) "${e.kind}|${Math.round(e.duration).toInt() / 3}|${e.height}" else e.url
            if (seen.add(key)) out.add(e)
        }
        // Aynı süreli, çözünürlüğü belli bir yayın varsa çözünürlüksüz olanı (ses/yan liste) gizle
        val filtered = out.filter { e ->
            !(e.isStream && e.height == 0 && e.duration > 0 && out.any { o ->
                o !== e && o.isStream && o.height > 0 &&
                    Math.abs(o.duration - e.duration) <= maxOf(4.0, e.duration * 0.04)
            })
        }
        // HLS ana listesi / DASH kalite seçeneklerine aç.
        // Seçim "#fpq=<bant genişliği>_<yükseklik>" ile yapılır (Streams.hls / Streams.dash çözer).
        val res = ArrayList<MediaInfo>()
        for (e in filtered) {
            val expand = (e.kind == "m3u8" && e.variants.isNotEmpty()) ||
                (e.kind == "mpd" && e.variants.isNotEmpty())
            if (expand) {
                for (v in e.variants.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bw })) {
                    val m = MediaInfo(e.url.substringBefore("#fpq=") + "#fpq=" + v.bw + "_" + v.height, e.kind)
                    m.duration = e.duration
                    m.height = v.height
                    m.bw = v.bw
                    m.size = if (e.duration > 0 && v.bw > 0) (v.bw / 8.0 * e.duration).toLong() else 0L
                    m.verified = e.verified
                    m.audio = e.audio
                    res.add(m)
                }
            } else res.add(e)
        }
        return res
    }
}
