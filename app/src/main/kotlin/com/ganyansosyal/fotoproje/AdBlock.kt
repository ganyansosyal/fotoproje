package com.ganyansosyal.fotoproje

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Reklam / izleyici engelleme (yerel tarayıcı).
 * Reklam alan adı listesi.
 * - Ağ seviyesi: alan adı (ve alt alan adları) eşleşirse istek boş yanıtla kesilir.
 * - Sayfa seviyesi: yaygın reklam kutuları CSS ile gizlenir (hafif, yalnızca
 *   id-öneki / sınıf / iframe seçicileri; kaydırmayı yavaşlatan [class*=] yok).
 */
object AdBlock {
    private val hosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagservices.com", "adservice.google.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com",
        "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "moatads.com", "amazon-adsystem.com", "scorecardresearch.com",
        "quantserve.com", "adform.net", "smartadserver.com", "yieldmo.com",
        "mgid.com", "popads.net", "propellerads.com", "exoclick.com",
        "juicyads.com", "adsterra.com", "admob.com", "ads.yahoo.com",
        "advertising.com", "casalemedia.com", "serving-sys.com", "teads.tv",
        "zedo.com",
        // Ek reklam ağları ve izleyiciler (yalnızca reklam/izleme; siteyi bozmayacak olanlar)
        "adsafeprotected.com", "2mdn.net", "adcolony.com", "applovin.com", "inmobi.com",
        "smaato.net", "vungle.com", "chartboost.com", "mopub.com", "bidswitch.net",
        "33across.com", "indexww.com", "sharethrough.com", "triplelift.com", "lijit.com",
        "sovrn.com", "contextweb.com", "adsymptotic.com", "demdex.net", "krxd.net",
        "bluekai.com", "rlcdn.com", "everesttech.net", "tapad.com", "agkn.com",
        "mathtag.com", "yieldlab.net", "adition.com", "improvedigital.com", "gumgum.com",
        "media.net", "revcontent.com", "zemanta.com", "onetag-sys.com", "id5-sync.com",
        "liadm.com", "doubleverify.com", "hotjar.com", "ads-twitter.com",
        "analytics.twitter.com", "ads.tiktok.com", "analytics.tiktok.com",
        "admatic.com.tr", "adnxs-simple.com", "adservice.google.com.tr"
    )
    private val paths = emptyList<String>()

    // ---- İnternetten indirilen hazır liste (Peter Lowe's ad server list) ----
    // Arka planda indirilir/okunur; sayfa açılışını ve kaydırmayı etkilemez. Hazır olana kadar yukarıdaki yerel liste çalışır.
    @Volatile private var extra: Set<String> = emptySet()
    private const val LIST_URL = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val MAX_BYTES = 3_000_000
    // Bunlar listede olsa bile asla engellenmez (siteleri bozar)
    private val NEVER = hashSetOf(
        "x.com", "twitter.com", "twimg.com", "t.co", "fbcdn.net", "instagram.com",
        "cdninstagram.com", "tiktok.com", "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "cloudfront.net",
        "akamaihd.net", "amazonaws.com", "github.com", "microsoft.com", "apple.com", "localhost"
    )
    private val DOMAIN = Regex("^[a-z0-9][a-z0-9._-]*\\.[a-z]{2,}$")

    private fun parse(text: String): Set<String> {
        val out = HashSet<String>()
        for (line0 in text.lineSequence()) {
            val line = line0.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val h = line.split(Regex("\\s+")).last().lowercase()
            if (h in NEVER || !DOMAIN.matches(h)) continue
            out.add(h)
        }
        return out
    }

    /** Arka plan iş parçacığından çağrılır. Kayıtlı listeyi okur; yoksa ya da bir haftadan eskiyse yeniler. */
    fun init(ctx: android.content.Context) {
        try {
            val f = java.io.File(ctx.filesDir, "adlist.txt")
            if (f.exists()) {
                val s = parse(f.readText())
                if (s.size > 500) extra = s
            }
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return
            val c = java.net.URL(LIST_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            try {
                if (c.responseCode != 200) return
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return
                    }
                }
                val text = out.toString("UTF-8")
                val s = parse(text)
                if (s.size < 500) return // bozuk/boş yanıt: eski liste kalsın
                val tmp = java.io.File(ctx.filesDir, "adlist.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
                extra = s
            } finally {
                c.disconnect()
            }
        } catch (_: Throwable) {
        }
    }

    /** Reklam/izleyici adresi mi? Regex yok: alan adı sonekleri küme araması ile denenir. */
    fun isAd(url: String, host: String?): Boolean {
        var h = host ?: return false
        val ex = extra
        while (h.isNotEmpty()) {
            if (hosts.contains(h) || ex.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        for (p in paths) {
            if (url.contains(p)) return true
        }
        return false
    }

    fun empty(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Belge başında çalışır; reklam kutularını gizleyen stil ekler. */
    const val HIDE_JS = """(function(){if(window.__fpad)return;window.__fpad=1;var css='ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]{display:none!important}';function add(){var p=document.head||document.documentElement;if(!p)return false;var s=document.createElement('style');s.textContent=css;p.appendChild(s);return true;}if(!add()){var mo=new MutationObserver(function(){if(add())mo.disconnect();});mo.observe(document,{childList:true,subtree:true});}})();"""
}
