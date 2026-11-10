package com.ganyansosyal.fotoproje

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend çözümleyici istemcisi: sayfa adresini servise gönderir, servisten net indirme adresi
 * (.mp4 / .m3u8) alır.
 *
 * İstek : POST <endpoint>  {"url": "<sayfa adresi>"}      (isteğe bağlı: Authorization: Bearer <token>)
 * Yanıt : {"url": "https://.../video.mp4", "kind": "mp4|m3u8" (ops.), "title": "..." (ops.), "referer": "..." (ops.)}
 *
 * Çerez ve oturum bilgisi backend'e GÖNDERİLMEZ; yalnızca sayfa adresi gider.
 */
object Resolver {
    class Result(val url: String, val kind: String, val title: String?, val referer: String?)

    private val prefs get() = Store.app.getSharedPreferences("fp_resolver", Context.MODE_PRIVATE)

    /** Örn. Resolver.endpoint = "https://api.ornek.com/resolve" (Ayarlar ekranından ya da kodla). */
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(v) { prefs.edit().putString("endpoint", v.trim()).apply() }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v.trim()).apply() }

    /** Yalnızca https adresleri kabul edilir. */
    fun enabled(): Boolean = endpoint.startsWith("https://")

    private fun readCapped(ins: InputStream, max: Int = 1_000_000): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun extOf(u: String): String =
        u.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    /** Ağ isteği yapar: ana iş parçacığında çağırma. Hata olursa IOException atar. */
    fun resolve(pageUrl: String, cancel: CancelFlag = CancelFlag()): Result {
        if (!enabled()) throw IOException("Çözümleyici adresi ayarlı değil")
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        cancel.add(c)
        try {
            c.outputStream.use {
                it.write(JSONObject().put("url", pageUrl).toString().toByteArray(Charsets.UTF_8))
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Çözümleyici HTTP $code")
            val body = c.inputStream.use { readCapped(it) }
            val o = JSONObject(body)
            val url = o.optString("url").trim()
            if (!url.startsWith("http")) throw IOException("Çözümleyici geçerli adres döndürmedi")
            val kind = when (val e = extOf(url)) {
                in Net.VIDEO_EXT -> e
                else -> o.optString("kind").lowercase().takeIf { it in Net.VIDEO_EXT }
                    ?: throw IOException("Desteklenmeyen tür")
            }
            return Result(
                url, kind,
                o.optString("title").takeIf { it.isNotBlank() },
                o.optString("referer").takeIf { it.startsWith("http") }
            )
        } catch (e: IOException) {
            if (cancel.on) throw CanceledEx()
            throw e
        } catch (e: org.json.JSONException) {
            throw IOException("Çözümleyici yanıtı okunamadı")
        } finally {
            cancel.remove(c)
            try { c.disconnect() } catch (_: Exception) {}
        }
    }
}
