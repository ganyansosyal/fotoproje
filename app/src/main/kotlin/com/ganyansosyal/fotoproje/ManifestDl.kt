package com.ganyansosyal.fotoproje

import android.content.Context
import org.w3c.dom.Element
import java.io.File
import java.io.IOException

/**
 * Master m3u8 / DASH (.mpd) ayrıştırıcı + FFmpeg ile doğrudan indirme.
 *
 * HLS: master listedeki seçilen #EXT-X-STREAM-INF varyantının AUDIO="grup" bilgisi ile
 *      #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="grup" satırı eşleştirilir (DEFAULT > AUTOSELECT > ilk).
 * DASH: en iyi video Representation + en iyi ses Representation seçilir (id'leri kayda geçer).
 *       Yalnızca tek dosyalı (BaseURL) temsiller doğrudan FFmpeg'e verilir; SegmentTemplate
 *       gibi parçalı yayınlarda null döner ve mevcut Streams.dash indiricisi kullanılır.
 *
 * Çalışan komut:
 *   ffmpeg -i video_url -i audio_url -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 160k out.mp4
 * (başlıklar, yeniden bağlanma, protokol izni ve ilerleme dahil). Hata olursa false döner,
 * çağıran mevcut indirici ile devam eder. Canlı yayına dokunmaz (sonsuz kayıt olmasın).
 */
object ManifestDl {
    class Pick(
        val videoUrl: String,
        val audioUrl: String?,      // null: ses görüntünün içinde (muxed) ya da yok
        val videoId: String,        // örn. "BW=2500000 1280x720" / "Representation id=v1"
        val audioId: String?,       // örn. "aud-main/Turkce (tr)" / "Representation id=a1"
        val duration: Double,
        val live: Boolean,
        val headers: Map<String, String>,
        val hls: Boolean
    )

    private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "vorbis", "mp3")

    // ------------------------------------------------------------------ AYRIŞTIRMA
    fun resolve(url0: String, referer: String, cancel: CancelFlag): Pick? {
        val frag = url0.substringAfter("#fpq=", "")
        val wantBw = frag.substringBefore('_').toIntOrNull()
        val wantH = frag.substringAfter('_', "").toIntOrNull()
        val url = url0.substringBefore("#fpq=")
        val got = Net.fetchText(url, referer, cancel)
        val body = got.body
        return when {
            body.trimStart().startsWith("#EXTM3U") -> hls(url, got, referer, cancel, wantBw, wantH)
            body.contains("<MPD") -> dash(url, got, wantBw, wantH)
            else -> null
        }
    }

    private fun mediaInfo(url: String, referer: String, cancel: CancelFlag): Pair<Double, Boolean> = try {
        val ls = Net.fetchText(url, referer, cancel).body.split('\n').map { it.trim() }
        var dur = 0.0
        for (l in ls) if (l.startsWith("#EXTINF:")) dur += l.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
        val vod = ls.any { it.startsWith("#EXT-X-ENDLIST") } || ls.any { it.equals("#EXT-X-PLAYLIST-TYPE:VOD", true) }
        dur to !vod
    } catch (e: CanceledEx) {
        throw e
    } catch (_: Throwable) {
        0.0 to false
    }

    private fun hls(
        url: String, got: Net.TextGot, referer: String, cancel: CancelFlag, wantBw: Int?, wantH: Int?
    ): Pick? {
        val ls = got.body.split('\n').map { it.trim() }
        if (ls.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            // Zaten medya listesi: tek akış (ses içinde olabilir)
            val (d, live) = mediaInfo(url, referer, cancel)
            return Pick(url, null, "media-playlist", null, d, live, got.headers, true)
        }
        class V(val bw: Int, val h: Int, val inf: String, val uri: String)
        val vs = ArrayList<V>()
        for (i in ls.indices) {
            if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
            var j = i + 1
            while (j < ls.size && (ls[j].isEmpty() || ls[j].startsWith("#"))) j++
            if (j >= ls.size) continue
            val res = Media.hlsAttr(ls[i], "RESOLUTION")
            val h = if (res != null && res.contains('x')) res.substringAfter('x').toIntOrNull() ?: 0 else 0
            vs.add(V(Media.hlsAttr(ls[i], "BANDWIDTH")?.toIntOrNull() ?: 0, h, ls[i], ls[j]))
        }
        if (vs.isEmpty()) return null
        val v = if (wantBw != null) {
            vs.firstOrNull { it.bw == wantBw && (wantH == null || it.h == wantH) }
                ?: vs.firstOrNull { it.bw == wantBw } ?: return null
        } else vs.maxByOrNull { it.bw }!!

        // Video Stream (varyant) <-> Audio Stream (EXT-X-MEDIA) eşleştirme
        val group = Media.hlsAttr(v.inf, "AUDIO")
        val media = ls.filter { it.startsWith("#EXT-X-MEDIA") && Media.hlsAttr(it, "TYPE").equals("AUDIO", true) }
        var cands = media.filter { group != null && Media.hlsAttr(it, "GROUP-ID") == group }
        var muxedAudio = false
        if (group != null && cands.isNotEmpty() && cands.all { Media.hlsAttr(it, "URI").isNullOrEmpty() }) {
            muxedAudio = true // grup var ama adres yok: ses varyantın içinde
        }
        cands = cands.filter { !Media.hlsAttr(it, "URI").isNullOrEmpty() }
        if (cands.isEmpty() && !muxedAudio) {
            // Varyantta ses grubu yok / grupta adres yok: kodek yalnızca görüntü ise tüm ses temsillerine bak
            val codecs = Media.hlsAttr(v.inf, "CODECS")?.lowercase()
            val videoOnly = codecs != null && AUDIO_CODECS.none { codecs.contains(it) }
            if (group != null || videoOnly) cands = media.filter { !Media.hlsAttr(it, "URI").isNullOrEmpty() }
        }
        val a = cands.firstOrNull { Media.hlsAttr(it, "DEFAULT").equals("YES", true) }
            ?: cands.firstOrNull { Media.hlsAttr(it, "AUTOSELECT").equals("YES", true) }
            ?: cands.firstOrNull()

        val videoUrl = Media.resolve(url, v.uri)
        val audioUrl = a?.let { Media.resolve(url, Media.hlsAttr(it, "URI")!!) }
        val (d, live) = mediaInfo(videoUrl, referer, cancel)
        val vid = "BW=${v.bw}" + (Media.hlsAttr(v.inf, "RESOLUTION")?.let { " $it" } ?: "")
        val aid = a?.let {
            (Media.hlsAttr(it, "GROUP-ID") ?: "?") + "/" + (Media.hlsAttr(it, "NAME") ?: "?") +
                (Media.hlsAttr(it, "LANGUAGE")?.let { l -> " ($l)" } ?: "")
        }
        return Pick(videoUrl, audioUrl, vid, aid, d, live, got.headers, true)
    }

    // ---- DASH
    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val n = childNodes
        for (i in 0 until n.length) {
            val c = n.item(i)
            if (c is Element && c.nodeName.substringAfter(':') == name) out.add(c)
        }
        return out
    }

    private fun Element.baseUrl(base: String): String {
        val b = kids("BaseURL").firstOrNull()?.textContent?.trim()
        return if (b.isNullOrEmpty()) base else Media.resolve(base, b)
    }

    private fun dash(url: String, got: Net.TextGot, wantBw: Int?, wantH: Int?): Pick? {
        val mpd = Streams.parseXml(got.body)
        val all = mpd.getElementsByTagName("*")
        for (i in 0 until all.length) {
            if (all.item(i).nodeName.substringAfter(':') == "ContentProtection") return null // DRM: mevcut indirici hata verir
        }
        if (mpd.getAttribute("type").equals("dynamic", true)) return null // canlı
        val period = mpd.kids("Period").firstOrNull() ?: return null
        val dur = Media.isoSeconds(period.getAttribute("duration").ifEmpty { null })
            ?: Media.isoSeconds(mpd.getAttribute("mediaPresentationDuration").ifEmpty { null }) ?: 0.0
        val pbase = period.baseUrl(mpd.baseUrl(url))

        class R(val id: String, val bw: Int, val h: Int, val url: String?, val lang: String)
        val vids = ArrayList<R>()
        val auds = ArrayList<R>()
        for (set in period.kids("AdaptationSet")) {
            val sbase = set.baseUrl(pbase)
            for (rep in set.kids("Representation")) {
                val mime = (rep.getAttribute("mimeType").ifEmpty { set.getAttribute("mimeType") }).lowercase()
                val ctype = set.getAttribute("contentType").lowercase()
                val isV = mime.startsWith("video") || ctype == "video"
                val isA = mime.startsWith("audio") || ctype == "audio"
                if (!isV && !isA) continue
                val segmented = rep.kids("SegmentTemplate").isNotEmpty() || rep.kids("SegmentList").isNotEmpty() ||
                    set.kids("SegmentTemplate").isNotEmpty() || set.kids("SegmentList").isNotEmpty()
                val hasBase = rep.kids("BaseURL").isNotEmpty()
                val direct = if (!segmented && hasBase) rep.baseUrl(sbase) else null
                val r = R(
                    rep.getAttribute("id"),
                    rep.getAttribute("bandwidth").toIntOrNull() ?: 0,
                    rep.getAttribute("height").toIntOrNull() ?: 0,
                    direct,
                    set.getAttribute("lang")
                )
                if (isV) vids.add(r) else auds.add(r)
            }
        }
        if (vids.isEmpty()) return null
        val v = if (wantBw != null) {
            vids.firstOrNull { it.bw == wantBw && (wantH == null || it.h == wantH) }
                ?: vids.firstOrNull { it.bw == wantBw } ?: return null
        } else vids.maxByOrNull { it.bw }!!
        val a = auds.maxByOrNull { it.bw }
        if (v.url == null || (a != null && a.url == null)) return null // parçalı yayın: mevcut indirici
        val vid = "Representation id=${v.id} BW=${v.bw}"
        val aid = a?.let { "Representation id=${it.id} BW=${it.bw}" + (if (it.lang.isNotEmpty()) " (${it.lang})" else "") }
        return Pick(v.url, a?.url, vid, aid, dur, false, got.headers, false)
    }

    // ------------------------------------------------------------------ FFMPEG
    private fun libPath(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return File(base, "ffmpeg/usr/lib").absolutePath + ":" + File(base, "python/usr/lib").absolutePath
    }

    /** Seçilen video (+ ses) adreslerini FFmpeg'e verir. Başarılıysa [Pick], olmazsa null döner. */
    fun download(
        ctx: Context, url: String, outPath: String, referer: String, task: DownloadTask, report: Report
    ): Pick? {
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!exe.exists()) return null
        val p = try {
            resolve(url, referer, task.cancel)
        } catch (e: CanceledEx) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return null
        if (p.live) return null // canlı yayın: mevcut yola bırak

        val hdr = p.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        fun input(u: String): List<String> {
            val l = ArrayList<String>()
            l += listOf("-protocol_whitelist", "file,http,https,tcp,tls,crypto,data")
            if (p.hls) l += listOf("-allowed_extensions", "ALL")
            l += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
            if (hdr.isNotEmpty()) l += listOf("-headers", hdr)
            l += listOf("-analyzeduration", "20M", "-probesize", "20M", "-i", u)
            return l
        }

        val prog = File(ctx.cacheDir, "ffmpeg_prog_${task.createdAt}.txt")
        val log = File(ctx.cacheDir, "ffmpeg_manifest.log")
        val out = File(outPath)
        val args = ArrayList<String>()
        args += listOf(exe.absolutePath, "-hide_banner", "-loglevel", "error", "-nostats", "-y", "-progress", prog.absolutePath)
        args += input(p.videoUrl)
        if (p.audioUrl != null) {
            args += input(p.audioUrl)
            args += listOf("-map", "0:v:0", "-map", "1:a:0")
        } else {
            args += listOf("-map", "0:v:0", "-map", "0:a:0?")
        }
        args += listOf(
            "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-strict", "experimental",
            "-max_muxing_queue_size", "4096", "-movflags", "+faststart", out.absolutePath
        )

        var good = false
        try {
            val pb = ProcessBuilder(args)
            pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            pb.redirectErrorStream(true)
            pb.redirectOutput(log)
            val proc = pb.start()
            var lastUs = -1L
            var lastMove = System.currentTimeMillis()
            while (true) {
                try {
                    proc.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    if (task.cancel.on) {
                        proc.destroy()
                        throw CanceledEx()
                    }
                    val us = try {
                        Regex("out_time_(?:us|ms)=(\\d+)").findAll(prog.readText()).lastOrNull()
                            ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    } catch (_: Throwable) { -1L }
                    if (us != lastUs) {
                        lastUs = us
                        lastMove = System.currentTimeMillis()
                        if (us >= 0 && p.duration > 0) {
                            report((us / 1e6 / p.duration).coerceIn(0.0, 0.99), "İndiriliyor %" + (us / 1e6 / p.duration * 100).toInt().coerceIn(0, 99))
                        }
                    } else if (System.currentTimeMillis() - lastMove > 90_000) {
                        proc.destroy() // 90 sn ilerleme yok: takıldı, mevcut indiriciye geç
                        break
                    }
                    Thread.sleep(300)
                }
            }
            good = try { proc.exitValue() == 0 } catch (_: Throwable) { false } && out.exists() && out.length() > 1024
        } catch (ce: CanceledEx) {
            try { out.delete() } catch (_: Throwable) {}
            throw ce
        } catch (_: Throwable) {
            good = false
        } finally {
            try { prog.delete() } catch (_: Throwable) {}
        }
        // Ayrı ses adresi verildiyse çıktıda gerçekten ses olmalı; yoksa bu yol geçersiz sayılır
        if (good && p.audioUrl != null && !FfmpegFix.hasAudioStream(ctx, outPath, task.cancel)) good = false
        if (!good) {
            try { out.delete() } catch (_: Throwable) {}
            return null
        }
        return p
    }
}
