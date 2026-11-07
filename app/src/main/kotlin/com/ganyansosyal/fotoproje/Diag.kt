package com.ganyansosyal.fotoproje

/** Teşhis raporu: aday adresler, oynatıcı bilgisi ve her başlık takımıyla sunucunun verdiği yanıt kodları. */
object Diag {
    fun report(
        page: String, ref: String, pDur: Double, pH: Int, pSrc: String,
        pUrls: List<String>, cands: List<NetCand>
    ): String {
        val sb = StringBuilder()
        sb.append("SAYFA: ").append(page).append('\n')
        sb.append("OYNATICI: süre=").append(pDur).append(" yükseklik=").append(pH)
            .append(" src=").append(pSrc.take(200)).append('\n')
        sb.append("UA: ").append(Net.ua.take(80)).append("\n\n")
        val all = LinkedHashMap<String, String>()
        for (u in pUrls) all[u] = ""
        for (c in cands) all[c.url] = c.kind
        sb.append("ADAY SAYISI: ").append(all.size).append("\n")
        var n = 0
        for ((u, k) in all) {
            if (n++ >= 8) break
            sb.append("\n#").append(n).append(" [").append(k.ifEmpty { "?" }).append("] ").append(u.take(220)).append('\n')
            if (!u.startsWith("http")) { sb.append("   (http değil)\n"); continue }
            val vars = try { Net.variants(u, ref) } catch (_: Throwable) { emptyList() }
            vars.forEachIndexed { i, h ->
                val r = try {
                    Http.get(u, h, "bytes=0-0", CancelFlag()) { c ->
                        val s = "HTTP " + c.responseCode + " tip=" + (c.contentType ?: "?") +
                            " aralık=" + (c.getHeaderField("Content-Range") ?: "-") +
                            " uzunluk=" + (c.getHeaderField("Content-Length") ?: "-")
                        c.disconnect()
                        s
                    }
                } catch (e: HttpErr) {
                    "HTTP " + e.code + " (" + e.host + ")"
                } catch (e: Throwable) {
                    "HATA " + (e.message ?: e.javaClass.simpleName).take(80)
                }
                sb.append("   takım").append(i).append(" Referer=").append(h["Referer"] ?: "yok")
                    .append(" Origin=").append(if (h.containsKey("Origin")) "var" else "yok")
                    .append(" Cookie=").append(if (h.containsKey("Cookie")) "var" else "yok")
                    .append(" -> ").append(r).append('\n')
            }
        }
        return sb.toString()
    }
}
