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
            c.requestMethod = request.httpMethod()
            c.setRequestProperty("User-Agent", Net.UA)
            for ((k, v) in request.headers()) {
                if (v.isNotEmpty()) c.setRequestProperty(k, v.joinToString(", "))
            }
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
            val stream = if (code >= 400) c.errorStream else c.inputStream
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

    /** En yüksek kaliteli ses+görüntü birleşik (muxed) akışın adresi. */
    fun pick(url: String): String {
        init()
        val info = StreamInfo.getInfo(url)
        val s = info.videoStreams
            .filter { !it.isVideoOnly && it.content.isNotEmpty() }
            .maxByOrNull { it.height * 10_000_000L + it.bitrate }
            ?: throw IOException("YouTube akışı bulunamadı")
        return s.content
    }
}
