package com.ganyansosyal.fotoproje

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/** Ses dosyasını (m4a / webm) çözüp MP3'e çevirir (MediaCodec + LAME). */
object Mp3 {
    fun encode(src: String, dest: String, cancel: CancelFlag, progress: (Double) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var lame: AndroidLame? = null
        var out: BufferedOutputStream? = null
        try {
            ex.setDataSource(src)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IOException("ses yok")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IOException("ses yok")
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            out = BufferedOutputStream(FileOutputStream(dest), 262144)

            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var mp3 = ByteArray(0)
            var lastUs = 0L
            while (!outDone) {
                if (cancel.on) throw CanceledEx()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i) ?: throw IOException("tampon yok")
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            lastUs = ex.sampleTime
                            c.queueInputBuffer(i, 0, n, lastUs, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        if (ch > 2) throw IOException("kanal")
                        val b = c.getOutputBuffer(o) ?: throw IOException("tampon yok")
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val pcm = ShortArray(sb.remaining())
                        sb.get(pcm)
                        if (lame == null) {
                            lame = LameBuilder()
                                .setInSampleRate(rate)
                                .setOutSampleRate(rate)
                                .setOutChannels(ch)
                                .setOutBitrate(192)
                                .setQuality(5)
                                .setMode(if (ch == 1) LameBuilder.Mode.MONO else LameBuilder.Mode.JSTEREO)
                                .build()
                        }
                        val frames = pcm.size / ch
                        val need = frames * 5 / 4 + 7200
                        if (mp3.size < need) mp3 = ByteArray(need)
                        val n = if (ch == 1) lame!!.encode(pcm, pcm, frames, mp3)
                        else lame!!.encodeBufferInterLeaved(pcm, frames, mp3)
                        if (n < 0) throw IOException("mp3 hata $n")
                        if (n > 0) out.write(mp3, 0, n)
                        if (durUs > 0) progress((lastUs.toDouble() / durUs).coerceIn(0.0, 1.0))
                    }
                    c.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            val l = lame ?: throw IOException("ses çözülemedi")
            if (mp3.size < 7200) mp3 = ByteArray(7200)
            val n = l.flush(mp3)
            if (n > 0) out.write(mp3, 0, n)
            out.flush()
        } finally {
            try { out?.close() } catch (_: Throwable) {}
            try { lame?.close() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }
}
