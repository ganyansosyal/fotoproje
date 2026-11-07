package com.ganyansosyal.fotoproje

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * YouTube: NewPipeExtractor. Kendi içinde JavaScript motoru (Rhino) vardır; imza/"n" şifresini kendisi çözer,
 * dışarıdan Deno/Node gerekmez. Yalnızca doğrudan akış adreslerini bulur; indirmeyi Streams.direct yapar.
 */
object NewPipeYt {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    val headers: Map<String, String> = mapOf("User-Agent" to UA)

    private object Dl : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = request.httpMethod()
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", UA)
                for ((k, vs) in request.headers()) {
                    if (vs.isNotEmpty()) conn.setRequestProperty(k, vs.joinToString(","))
                }
                val data = request.dataToSend()
                if (data != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(data) }
                }
                val code = conn.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha gerekli", request.url())
                val raw = if (code >= 400) conn.errorStream else conn.inputStream
                val stream = if (raw != null && "gzip".equals(conn.contentEncoding, true)) GZIPInputStream(raw) else raw
                val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                val h = HashMap<String, List<String>>()
                for ((k, v) in conn.headerFields) if (k != null) h[k] = v
                return Response(code, conn.responseMessage ?: "", h, body, conn.url.toString())
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    @Volatile private var inited = false

    @Synchronized
    private fun init() {
        if (inited) return
        NewPipe.init(Dl)
        inited = true
    }

    class Pick(val title: String, val videoUrl: String, val audioUrl: String?, val desc: String)

    /** Video sayfasından en iyi H.264 görüntü + AAC ses akışını seçer (yoksa sesli+görüntülü tek dosya). */
    fun pick(pageUrl: String): Pick {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
        val http = DeliveryMethod.PROGRESSIVE_HTTP
        val audio = info.getAudioStreams()
            .filter { it.getDeliveryMethod() == http && it.getContent().startsWith("http") && it.getFormat()?.getSuffix() == "m4a" }
            .maxByOrNull { it.getAverageBitrate() }
        val video = info.getVideoOnlyStreams()
            .filter {
                it.getDeliveryMethod() == http && it.getContent().startsWith("http") &&
                    it.getFormat()?.getSuffix() == "mp4" && (it.getCodec() ?: "").startsWith("avc")
            }
            .maxByOrNull { it.getHeight() }
        if (video != null && audio != null) {
            return Pick(info.getName() ?: "", video.getContent(), audio.getContent(), "${video.getHeight()}p H.264 + AAC")
        }
        val both = info.getVideoStreams()
            .filter { it.getDeliveryMethod() == http && it.getContent().startsWith("http") && it.getFormat()?.getSuffix() == "mp4" }
            .maxByOrNull { it.getHeight() }
        if (both != null) return Pick(info.getName() ?: "", both.getContent(), null, "${both.getHeight()}p (tek dosya)")
        throw IOException("uygun akış bulunamadı")
    }
}
