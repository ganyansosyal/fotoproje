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
        "zedo.com"
    )
    private val paths = listOf("facebook.net/en_US/fbevents.js")

    /** Reklam/izleyici adresi mi? Regex yok: alan adı sonekleri küme araması ile denenir. */
    fun isAd(url: String, host: String?): Boolean {
        var h = host ?: return false
        while (h.isNotEmpty()) {
            if (hosts.contains(h)) return true
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
