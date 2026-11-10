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
    /** Yalnızca ses içeren dosya (Instagram/Facebook'ta görüntü ve ses ayrı gelir). */
    var audioOnly = false
    var mp4Read = false
    /** Sessiz görüntü dosyasıyla eşleşen ayrı ses dosyası adresi. */
    var audioUrl = ""
    /** true: sesli, false: sessiz (yalnızca görüntü), null: bilinmiyor */
    var audio: Boolean? = null

    val isStream get() = kind == "m3u8" || kind == "mpd"
    val host get() = hostOf(url)

    val title: String
        get() {
            val p = ArrayList<String>()
            if (height > 0) p.add("${height}p")
            if (duration > 0) p.add("0:00–" + Media.fmtDuration(duration))
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
            // Ana liste (kalite seçenekleri olan master playlist) tek parça listelerden önce gelir
            val master = if (variants.isNotEmpty()) 500.0 else 0.0
            return (if (verified) 1e6 else 0.0) + 1000 + master + duration * 10 + height + size / 1e6
        }
}

object Probe {
    private val pool = Executors.newFixedThreadPool(6) // aynı anda en çok 6 istek

    /** Teşhis günlüğü: <uygulama dosyaları>/probe_log.txt (yalnızca host+yol yazılır, sorgu/token yazılmaz). */
    private fun short(u: String): String =
        u.substringBefore('?').substringBefore('#').let { if (it.length > 140) it.take(140) + "…" else it }

    private fun dbg(lines: List<String>) {
        try {
            val dir = Store.app.getExternalFilesDir(null) ?: return
            java.io.File(dir, "probe_log.txt").writeText(lines.joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    /** Yalnızca parça/ses/altyazı olan uzantılar: asla video adayı olmaz. */
    private val FRAGMENT_EXT = Regex("\\.(ts|m4s|aac|m4a|mp3|cmfv|cmfa|vtt|srt)$", RegexOption.IGNORE_CASE)

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

    /** Uzantısı/ipucu olmayan adreste Content-Type / ilk baytlara bakarak biçimi bulur. */
    private fun sniffKind(url: String, referer: String): String? =
        try { Net.sniffKind(url, referer) } catch (_: Throwable) { null }

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

    /** MediaExtractor süreyi veremezse MediaMetadataRetriever ile dener. */
    private fun retrieverDuration(url: String, referer: String): Double {
        var out = 0.0
        val t = Thread {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(url.substringBefore("#"), Net.headers(url, referer))
                out = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) / 1000.0
            } catch (_: Throwable) {
            } finally {
                try { r.release() } catch (_: Throwable) {}
            }
        }
        t.isDaemon = true
        t.start()
        t.join(8000)
        return if (t.isAlive) 0.0 else out
    }

    /** MP4 kutularından okunan bilgi: süre, görüntü/ses izi, çözünürlük. */
    private class Mp4Info {
        var duration = 0.0
        var video = false
        var audio = false
        var height = 0
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    private fun fourcc(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    /**
     * MP4 başlığını (moov) kendimiz okur: süre, görüntü/ses izi ve çözünürlük.
     * MediaExtractor'a göre hızlı ve uzak adreslerde güvenilirdir (tek küçük aralık isteği).
     * moov başta değilse dosyanın sonundan da dener; bulunamazsa null.
     */
    private fun mp4Info(url: String, referer: String): Mp4Info? {
        for (range in listOf("bytes=0-262143", "bytes=-262144")) {
            val buf = try {
                Http.get(url.substringBefore("#"), Net.headers(url, referer), range, CancelFlag()) { c ->
                    c.inputStream.use { ins ->
                        val out = java.io.ByteArrayOutputStream()
                        val tmp = ByteArray(16384)
                        while (out.size() < 262144) {
                            val n = ins.read(tmp)
                            if (n < 0) break
                            out.write(tmp, 0, n)
                        }
                        out.toByteArray()
                    }
                }
            } catch (e: CanceledEx) {
                throw e
            } catch (_: Throwable) {
                null
            } ?: continue
            val info = Mp4Info()
            var timescale = 0L
            var mvhdDur = 0L
            var mehdDur = 0L
            var foundMoov = false
            fun walk(start: Int, end: Int, inTrak: Boolean, st: Array<Any?>) {
                var p = start
                while (p + 8 <= end) {
                    var size = u32(buf, p)
                    val type = fourcc(buf, p + 4)
                    var hdr = 8
                    if (size == 1L && p + 16 <= end) { size = u64(buf, p + 8); hdr = 16 }
                    else if (size == 0L) size = (end - p).toLong()
                    if (size < hdr) return
                    val bodyStart = p + hdr
                    val boxEnd = minOf(end.toLong(), p + size).toInt()
                    when (type) {
                        "moov" -> { foundMoov = true; walk(bodyStart, boxEnd, false, st) }
                        "mvex", "mdia" -> walk(bodyStart, boxEnd, inTrak, st)
                        "trak" -> {
                            val s2 = arrayOf<Any?>("", 0)
                            walk(bodyStart, boxEnd, true, s2)
                            if (s2[0] == "vide") { info.video = true; info.height = maxOf(info.height, s2[1] as Int) }
                            if (s2[0] == "soun") info.audio = true
                        }
                        "mvhd" -> if (boxEnd - bodyStart >= 20) {
                            val v = buf[bodyStart].toInt()
                            if (v == 1 && boxEnd - bodyStart >= 32) { timescale = u32(buf, bodyStart + 20); mvhdDur = u64(buf, bodyStart + 24) }
                            else { timescale = u32(buf, bodyStart + 12); mvhdDur = u32(buf, bodyStart + 16) }
                        }
                        "mehd" -> if (boxEnd - bodyStart >= 8) {
                            mehdDur = if (buf[bodyStart].toInt() == 1 && boxEnd - bodyStart >= 12) u64(buf, bodyStart + 4)
                            else u32(buf, bodyStart + 4)
                        }
                        "hdlr" -> if (inTrak && boxEnd - bodyStart >= 12) st[0] = fourcc(buf, bodyStart + 8)
                        "tkhd" -> if (inTrak) {
                            val v = buf[bodyStart].toInt()
                            val wo = bodyStart + if (v == 1) 88 else 76
                            if (wo + 8 <= boxEnd) st[1] = (u32(buf, wo + 4) shr 16).toInt()
                        }
                    }
                    if (boxEnd <= p) return
                    p = boxEnd
                }
            }
            walk(0, buf.size, false, arrayOf<Any?>("", 0))
            if (!foundMoov) continue
            val d = if (mvhdDur > 0 && mvhdDur != 0xFFFFFFFFL) mvhdDur else mehdDur
            info.duration = if (timescale > 0 && d > 0) d.toDouble() / timescale else 0.0
            if (!info.video && !info.audio) continue
            return info
        }
        return null
    }

    private fun isAudioCodec(c: String) =
        listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "mp3", "vorbis").any { c.contains(it) }

    /** Instagram/Facebook adreslerindeki "efg" parametresinden kodlama etiketi (örn. "..._audio"). */
    private fun efgTag(url: String): String {
        return try {
            val raw = android.net.Uri.parse(url.substringBefore("#")).getQueryParameter("efg") ?: return ""
            var b = raw.replace('-', '+').replace('_', '/')
            while (b.length % 4 != 0) b += "="
            val json = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
            org.json.JSONObject(json).optString("vencode_tag").lowercase()
        } catch (_: Throwable) {
            ""
        }
    }

    /** Adresteki dosyada (görüntü, ses) izleri var mı; okunamazsa null. */
    fun avTracks(url: String, referer: String): Pair<Boolean, Boolean>? = tracks(url, referer, null)

    private val AUDIO_EXT = Regex("\\.(m4a|aac|mp3)$", RegexOption.IGNORE_CASE)

    /** Son incelemede bulunan yalnızca-ses dosyaları: indirme sırasında sessiz videoya eş aranır. */
    @Volatile var audioPool: List<MediaInfo> = emptyList()

    /** [dur] saniyeye en uygun ses dosyası (süre bilinmiyorsa en büyük). Yoksa null. */
    fun bestAudio(dur: Double): MediaInfo? {
        val l = audioPool
        if (l.isEmpty()) return null
        if (dur > 0) {
            val tol = maxOf(1.5, dur * 0.03)
            val m = l.filter { it.duration > 0 && Math.abs(it.duration - dur) <= tol }
            if (m.isNotEmpty()) return m.maxByOrNull { it.size }
            if (l.any { it.duration > 0 }) return null
        }
        return l.maxByOrNull { it.size }
    }

    private fun probeAudio(url: String, referer: String): MediaInfo {
        val a = MediaInfo(url, "m4a")
        a.usable = false
        a.audioOnly = true
        try {
            Http.get(url.substringBefore("#"), Net.headers(url, referer), "bytes=0-0", CancelFlag()) { c ->
                val cr = c.getHeaderField("Content-Range") ?: ""
                val total = Regex("/(\\d+)\\s*$").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                val code = try { c.responseCode } catch (_: Throwable) { 200 }
                val cl = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                a.size = total ?: (if (code == 206 || cl <= 1L) 0L else cl)
                c.disconnect()
                Unit
            }
            val dur = DoubleArray(1)
            tracks(url, referer, dur)
            a.duration = if (dur[0] > 0) dur[0] else retrieverDuration(url, referer)
        } catch (_: Throwable) {
        }
        return a
    }

    private fun probeOne(url: String, hint: String, referer: String, children: MutableSet<String>): MediaInfo {
        if (hint == "m4a" || hint == "aac" || hint == "mp3" ||
            AUDIO_EXT.containsMatchIn(url.substringBefore('?').substringBefore('#'))) {
            return probeAudio(url, referer)
        }
        if (FRAGMENT_EXT.containsMatchIn(url.substringBefore('?').substringBefore('#'))) {
            return MediaInfo(url, "mp4").also { it.usable = false }
        }
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
                val code = try { c.responseCode } catch (_: Throwable) { 200 }
                val cl = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                val len = total ?: (if (code == 206 || cl <= 1L) 0L else cl)
                c.disconnect()
                if (ct.startsWith("image/") || ct.startsWith("text/") || ct.contains("json")) info.usable = false
                info.size = len
                // Boyut / süre eşiği yok: video biçimindeki her dosya aday olur
                Unit
            }
            if (info.usable && kind in setOf("mp4", "m4v", "mov", "3gp", "3g2", "f4v")) {
                // Önce MP4 başlığından oku (hızlı, güvenilir): süre, çözünürlük, görüntü/ses izi
                val mi = mp4Info(url, referer)
                if (mi != null) {
                    if (mi.duration > 0) info.duration = mi.duration
                    if (mi.height > 0) info.height = mi.height
                    if (!mi.video) {
                        info.usable = false
                        info.audioOnly = mi.audio
                    } else {
                        info.audio = mi.audio
                        info.mp4Read = true
                    }
                }
            }
            if (info.usable && kind != "blob" && !info.mp4Read) {
                // Görüntü/ses izi kontrolü: yalnızca-ses dosyası aday olmaz, sessiz olan işaretlenir
                val dur = DoubleArray(1)
                val tr = tracks(url, referer, dur)
                // Süre bilinirse parça (kısa) dosyalar ayıklanabilir, oynayan videoyla eşleştirilebilir
                if (info.duration <= 0 && dur[0] > 0) info.duration = dur[0]
                if (tr != null) {
                    if (!tr.first) {
                        // Görüntü izi yok: oynamaz. Yalnızca ses varsa, sessiz görüntüyle eşleştirmek için sakla
                        info.usable = false
                        info.audioOnly = tr.second
                    } else {
                        info.audio = tr.second
                    }
                }
                if (info.usable && info.duration <= 0) info.duration = retrieverDuration(url, referer)
            }
            // Instagram/Facebook: etiket kesin söyler (sessiz DASH görüntüsü / ayrı ses dosyası)
            val tag = efgTag(url)
            if (tag.isNotEmpty() && kind != "m3u8" && kind != "mpd") {
                if (tag.contains("audio")) {
                    info.usable = false
                    info.audioOnly = true
                } else if (tag.contains("dash")) {
                    info.audio = false
                }
            }
        } catch (_: Throwable) {
            info.duration = 0.0
        }
        return info
    }

    /**
     * Listeyi sadeleştirir: sessiz / boyutu bilinmeyen / parça boyutundaki adaylar atılır,
     * aynı videonun kopyalarından yalnızca en iyisi kalır. Hiç aday kalmazsa eldeki liste korunur.
     */
    private fun pick(res: List<MediaInfo>): List<MediaInfo> {
        if (res.size <= 1) return res
        var l: List<MediaInfo> = res
        fun narrow(f: (MediaInfo) -> Boolean) {
            val n = l.filter(f)
            if (n.isNotEmpty()) l = n
        }
        // Sesli olanlar (ayrı ses dosyasıyla eşleşen de sesli sayılır)
        narrow { it.audio != false }
        // Boyutu bilinen (KB/MB yazan) olanlar; akışlar ve korumalı olanlar kalır
        narrow { it.isStream || it.kind == "blob" || it.size > 0 }
        // Parça boyutundaki (<100 KB) dosyalar gerçek video değildir
        narrow { it.isStream || it.kind == "blob" || it.size <= 0 || it.size >= 100 * 1024 }
        // Aynı videonun kopyaları: en yüksek çözünürlük, sonra en büyük dosya
        val out = ArrayList<MediaInfo>()
        val direct = l.filter { !it.isStream && it.kind != "blob" }
        val others = l.filter { it.isStream || it.kind == "blob" }
        val used = ArrayList<MediaInfo>()
        for (e in direct.sortedWith(compareByDescending<MediaInfo> { it.height }.thenByDescending { it.size })) {
            val dup = used.any {
                it.duration <= 0 || e.duration <= 0 ||
                    Math.abs(it.duration - e.duration) <= maxOf(1.5, e.duration * 0.03)
            }
            if (!dup) used.add(e)
        }
        out.addAll(used)
        out.addAll(others)
        return if (out.isEmpty()) l else out
    }

    /** Adayları paralel inceler; oynayan videonun süresiyle eşleşenleri doğrulanmış sayar. */
    fun probeAll(
        urls: List<String>, hints: Map<String, String>, referer: String,
        expectDur: Double = 0.0, exactSrc: String = "", height: Int = 0
    ): List<MediaInfo> {
        val children = HashSet<String>()
        val futures = urls.distinct().take(30).map { u ->
            pool.submit(Callable { probeOne(u, hints[u] ?: "", referer, children) })
        }
        val list = futures.map { try { it.get() } catch (_: Throwable) { null } }.filterNotNull()
        val log = ArrayList<String>()
        log.add("referer=" + short(referer) + " expectDur=" + expectDur + " exactSrc=" + short(exactSrc))
        log.add("aday=" + urls.distinct().take(100).size + " incelenen=" + list.size)
        for (e in list) {
            log.add(
                "ADAY " + e.kind + " ipucu=" + (hints[e.url] ?: "-") + " kullanilabilir=" + e.usable +
                    " boyut=" + e.size + " sure=" + e.duration + " yukseklik=" + e.height +
                    " ses=" + e.audio + " " + short(e.url)
            )
        }
        // Süzgeç yok: kullanılabilir (video biçiminde, şifresiz) her aday listelenir.
        // Yalnızca HLS/DASH ana listesinin kendi alt listeleri (aynı videonun kalite parçaları) ayıklanır.
        val ok = list.filter { it.usable && !synchronized(children) { children.contains(it.url) } }
            .sortedByDescending { it.rank }
        val filtered = ok.distinctBy { it.url }
        // Süzgeç değil, EŞLEŞTİRME: kullanıcının bastığı videonun süresi biliniyorsa yalnızca o videonun
        // dosyaları gösterilir (Instagram akışında önceden yüklenen başka videolar karışmasın).
        // Eşleşen yoksa liste boş kalır (başka video karışmaz).
        var cands = filtered
        if (expectDur > 0.5) {
            val tol = maxOf(1.5, expectDur * 0.03)
            val matches = filtered.filter { it.duration > 0 && Math.abs(it.duration - expectDur) <= tol || it.url == exactSrc }
            if (matches.isNotEmpty()) {
                matches.forEach { it.verified = true }
                cands = matches
            } else {
                // KESİN EŞLEŞME: süresi uyan dosya yoksa başka videoların dosyaları ASLA gösterilmez.
                // Yalnızca oynatıcının kendi blob'u kalır; doğru dosya gelince (yeni adaylar) liste yeniden kurulur.
                cands = filtered.filter { it.kind == "blob" }
            }
        }
        // Reklam akışları kısa olur: uzun (>= 5 dk) bir video varsa kısa (< 2 dk) adaylar listeden çıkar
        if (cands.any { it.duration >= 300 }) {
            val keep = cands.filter { it.duration <= 0 || it.duration >= 120 }
            if (keep.isNotEmpty()) cands = keep
        }
        // Oynayan videonun süresi biliniyorsa, süresi okunamayan adayın süresi olarak göster
        for (e in cands) {
            if (e.duration <= 0 && expectDur > 0 && (e.url == exactSrc || cands.size == 1)) e.duration = expectDur
            if (e.height <= 0 && height > 0 && (e.url == exactSrc || cands.size == 1)) e.height = height
        }
        // Sessiz görüntü dosyası + ayrı ses dosyası (Instagram/Facebook): eşleştir, indirirken birleştirilir
        val audios = list.filter { it.audioOnly }
        if (audios.isNotEmpty()) audioPool = audios
        if (audios.isNotEmpty()) {
            for (e in cands) {
                if (e.isStream || e.kind == "blob" || e.audio == true) continue
                val tol = maxOf(1.5, e.duration * 0.03)
                val pool2 = if (e.duration > 0) {
                    audios.filter { it.duration <= 0 || Math.abs(it.duration - e.duration) <= tol }
                } else audios
                val best = pool2.sortedWith(
                    compareBy<MediaInfo> { if (e.duration > 0 && it.duration > 0) Math.abs(it.duration - e.duration) else 0.0 }
                        .thenByDescending { it.size }
                ).firstOrNull() ?: continue
                e.audioUrl = best.url
                e.audio = true
            }
        }
        // HLS ana listesi / DASH kalite seçeneklerine aç.
        // Seçim "#fpq=<bant genişliği>_<yükseklik>" ile yapılır (Streams.hls / Streams.dash çözer).
        val res = ArrayList<MediaInfo>()
        for (e in cands.sortedByDescending { it.rank }) {
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
            } else if (e.audioUrl.isNotEmpty()) {
                val m = MediaInfo(e.url.substringBefore("#") + "#fpa=" + java.net.URLEncoder.encode(e.audioUrl, "UTF-8"), e.kind)
                m.duration = e.duration
                m.height = e.height
                m.size = e.size
                m.audio = true
                m.verified = e.verified
                res.add(m)
            } else res.add(e)
        }
        val picked = pick(res)
        res.clear()
        res.addAll(picked)
        for (r in res) log.add("SONUC " + r.kind + " boyut=" + r.size + " sure=" + r.duration + " " + short(r.url))
        dbg(log)
        return res
    }
}
