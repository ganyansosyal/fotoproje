package com.ganyansosyal.fotoproje

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** YouTube çıkarımı: NewPipeExtractor (eski youtube_explode_dart yerine). */
object YouTube {
    private class Dl : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.requestMethod = request.httpMethod()
            c.setRequestProperty("User-Agent", Net.UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
            for ((k, v) in request.headers()) {
                if (v.isEmpty()) continue
                // gzip'i biz çözeceğiz; ayrıca çerezi ezmeyelim
                if (k.equals("Accept-Encoding", true)) continue
                c.setRequestProperty(k, v.joinToString(", "))
            }
            c.setRequestProperty("Accept-Encoding", "gzip")
            val data = request.dataToSend()
            if (data != null) {
                c.doOutput = true
                c.outputStream.use { it.write(data) }
            }
            val code = c.responseCode
            if (code == 429) {
                c.disconnect()
                throw ReCaptchaException("reCaptcha", request.url())
            }
            val raw = if (code >= 400) c.errorStream else c.inputStream
            val gz = (c.contentEncoding ?: "").contains("gzip", true)
            val stream = if (raw != null && gz) java.util.zip.GZIPInputStream(raw) else raw
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hdr = HashMap<String, List<String>>()
            for ((k, v) in c.headerFields) if (k != null) hdr[k] = v
            val last = c.url.toString()
            val msg = c.responseMessage ?: ""
            c.disconnect()
            return Response(code, msg, hdr, body, last)
        }
    }

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(Dl())
                ready = true
            }
        }
    }

    /** list=, start_radio=, pp= gibi parametreleri atıp sadece video kimliğini bırakır. */
    private fun clean(url: String): String {
        val m = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(url)
        return if (m != null) "https://www.youtube.com/watch?v=" + m.groupValues[1] else url
    }

    /** Bağlantı kutusu için: başlık, süre ve seçilebilir kaliteler. */
    class Info(val title: String, val duration: Long, val url: String, val heights: List<Int>, val hasAudio: Boolean)

    /** MediaMuxer (MP4) ile birleştirilebilen ayrı video akışı: yalnızca H.264 / mp4. */
    private fun mergeable(s: org.schabi.newpipe.extractor.stream.VideoStream): Boolean =
        s.isVideoOnly && s.content.isNotEmpty() &&
            (s.format?.suffix ?: "") == "mp4" && (s.codec ?: "").startsWith("avc")

    fun info(url: String): Info {
        init()
        val u = clean(url)
        val si = StreamInfo.getInfo(u)
        val au = si.audioStreams.any { it.content.isNotEmpty() }
        val muxed = si.videoStreams.filter { !it.isVideoOnly && it.content.isNotEmpty() }.map { it.height }
        val split = if (au) si.videoOnlyStreams.filter { mergeable(it) }.map { it.height } else emptyList()
        val hs = (muxed + split).filter { it > 0 }.distinct().sortedDescending()
        if (hs.isEmpty() && !au) throw IOException("YouTube akışı bulunamadı")
        return Info(si.name ?: "YouTube", si.duration, u, hs, au)
    }

    /** İndirme seçimi: video adresi + (ayrı akışsa) ses adresi. */
    class Sel(val video: String, val audio: String?)

    /** height>0 ise o kalite, yoksa en yüksek. Birleşik akış varsa onu, yoksa video+ses ikilisini seçer. */
    fun select(url: String, height: Int = 0): Sel {
        init()
        val si = StreamInfo.getInfo(clean(url))
        val muxed = si.videoStreams.filter { !it.isVideoOnly && it.content.isNotEmpty() }
        val audios = si.audioStreams.filter { it.content.isNotEmpty() }
        val split = if (audios.isNotEmpty()) si.videoOnlyStreams.filter { mergeable(it) } else emptyList()
        val want = if (height > 0) height else (muxed + split).maxOfOrNull { it.height } ?: 0
        val m = muxed.filter { it.height == want }.maxByOrNull { it.bitrate }
        val v = split.filter { it.height == want }.maxByOrNull { it.bitrate }
        if (m != null) return Sel(m.content, null)
        if (v != null) {
            val m4a = audios.filter { (it.format?.suffix ?: "") == "m4a" }
            val a = m4a.ifEmpty { audios }.maxByOrNull { it.averageBitrate }
            if (a != null) return Sel(v.content, a.content)
        }
        // İstenen kalite yoksa eldeki en iyi birleşik akışa düş
        val fb = muxed.maxByOrNull { it.height * 10_000_000L + it.bitrate }
            ?: throw IOException("YouTube akışı bulunamadı")
        return Sel(fb.content, null)
    }

    /** Ses+görüntü birleşik (muxed) akışın adresi. height>0 ise o kalite, yoksa en yüksek. */
    fun pick(url: String, height: Int = 0): String {
        init()
        val info = StreamInfo.getInfo(clean(url))
        val all = info.videoStreams.filter { !it.isVideoOnly && it.content.isNotEmpty() }
        val pool = if (height > 0) all.filter { it.height == height }.ifEmpty { all } else all
        val s = pool.maxByOrNull { it.height * 10_000_000L + it.bitrate }
            ?: throw IOException("YouTube akışı bulunamadı")
        return s.content
    }

    /** En iyi ses akışı: (adres, uzantı). Önce m4a (AAC) tercih edilir. */
    fun audio(url: String): Pair<String, String> {
        init()
        val info = StreamInfo.getInfo(clean(url))
        val all = info.audioStreams.filter { it.content.isNotEmpty() }
        val m4a = all.filter { (it.format?.suffix ?: "") == "m4a" }
        val s = m4a.ifEmpty { all }.maxByOrNull { it.averageBitrate }
            ?: throw IOException("YouTube ses akışı bulunamadı")
        val ext = s.format?.suffix?.ifEmpty { null } ?: "m4a"
        return Pair(s.content, ext)
    }
}
