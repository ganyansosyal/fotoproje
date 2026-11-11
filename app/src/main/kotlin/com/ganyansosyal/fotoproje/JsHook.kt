package com.ganyansosyal.fotoproje

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * AŞAMA 2 — JavaScript köprüsü.
 * Sayfadaki betik fetch / XHR ile bulduğu adresi buraya bildirir (arka plan iş parçacığında gelir).
 */
class VideoBridge(private val sniffer: VideoSniffer) {
    private val kinds = setOf("m3u8", "mpd", "mp4", "webm", "ts", "manifest", "m4a")

    @JavascriptInterface
    fun report(url: String?, kind: String?, source: String?, page: String?) {
        // Sayfadan gelen her veri güvenilmez kabul edilir: doğrula.
        if (url.isNullOrEmpty() || url.length > 2500) return
        if (!url.startsWith("http", ignoreCase = true)) return
        if (VideoRules.isAd(url)) return

        val k = kind?.takeIf { it in kinds } ?: VideoRules.classify(url) ?: "manifest"
        val pageUrl = (page ?: "").take(2500)

        val headers = HashMap<String, String>()
        if (pageUrl.startsWith("http", ignoreCase = true)) {
            headers["Referer"] = pageUrl
            try {
                val u = java.net.URI(pageUrl)
                headers["Origin"] = u.scheme + "://" + u.authority
            } catch (_: Throwable) {
            }
        }
        try {
            android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                if (it.isNotEmpty()) headers["Cookie"] = it
            }
        } catch (_: Throwable) {
        }

        sniffer.offer(VideoHit(url, k, (source ?: "js").take(32), pageUrl, headers))
    }
}

object JsHook {
    /** JS tarafında window.VideoBridge olarak görünür. */
    const val NAME = "VideoBridge"

    /**
     * WebView oluştururken bir kez çağır (loadUrl'den önce).
     * @return true: betik her sayfada ve iframe'lerde sayfa başlamadan çalışacak.
     *         false: cihaz desteklemiyor; onPageStarted içinde [injectFallback] çağır.
     */
    @SuppressLint("JavascriptInterface", "RequiresFeature")
    fun install(web: WebView, sniffer: VideoSniffer): Boolean {
        web.addJavascriptInterface(VideoBridge(sniffer), NAME)
        return try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, setOf("*"))
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** DOCUMENT_START_SCRIPT yoksa: onPageStarted içinde çağır (yalnızca ana çerçeve). */
    fun injectFallback(web: WebView) {
        web.evaluateJavascript(SCRIPT, null)
    }

    /**
     * Sayfaya enjekte edilen kanca. Kendini iki kez kurmaz.
     * - fetch ve XMLHttpRequest isteklerinin adresine bakar (uzantı: m3u8, mpd, mp4, webm, ts, m4s)
     * - yanıtın Content-Type'ına bakar (mpegurl, dash+xml, video türleri)
     * - uzantısız manifest/playlist için yanıtın başına bakar (#EXTM3U veya <MPD)
     * - JSON yanıtların içine gömülü .m3u8 / .mpd / .mp4 adreslerini bulur (TV8 gibi siteler)
     */
    val SCRIPT: String = """
(function () {
  if (window.__vsHook) return;
  window.__vsHook = true;
  var B = window.VideoBridge;
  if (!B || typeof B.report !== 'function') return;

  var EXT = /\.(m3u8|mpd|mp4|webm|ts|m4s|m4a|aac|mp3|weba|opus)(?:[?#&\/]|$)/i;
  var BAD = /\.(?:json|js|css|html?|png|jpe?g|gif|webp|svg|ico|woff2?)(?:[?#]|$)/i;
  var SEG = /(?:seg|segment|chunk|frag|fragment)[-_.]?\d/i;
  var seen = {};
  var count = 0;

  function abs(u) {
    try { return new URL(String(u), location.href).href; } catch (e) { return ''; }
  }
  function kindOf(u) {
    var p = u.split('#')[0].split('?')[0].toLowerCase();
    if (/\.m3u8$/.test(p)) return 'm3u8';
    if (/\.mpd$/.test(p)) return 'mpd';
    if (/\.mp4$/.test(p)) return 'mp4';
    if (/\.webm$/.test(p)) return 'webm';
    if (/\.(m4a|aac|mp3|weba|opus)$/.test(p)) return 'm4a';
    if (/[?&]mime_?type=audio/i.test(u)) return 'm4a';
    if (/\.(ts|m4s)$/.test(p)) return 'ts';
    return 'manifest';
  }
  function report(u, src, kind) {
    u = abs(u);
    if (!/^https?:/i.test(u) || u.length > 2500 || seen[u] || count > 500) return;
    seen[u] = 1; count++;
    var k = kind || kindOf(u);
    var file = u.split('#')[0].split('?')[0];
    file = file.substring(file.lastIndexOf('/') + 1);
    if (k !== 'ts' && SEG.test(file)) k = 'ts';
    try { B.report(u, k, src, location.href); } catch (e) {}
  }
  function byUrl(u, src) {
    if (!u) return;
    var s = String(u);
    if (BAD.test(s)) return;
    if (EXT.test(s)) report(s, src);
  }
  // Content-Type biliniyorsa adresi hemen bildirir; bildirdiyse true döner.
  function byType(ct, u, src) {
    if (!u) return false;
    if (/mpegurl/i.test(ct)) { report(u, src + '-ct', 'm3u8'); return true; }
    if (/dash\+xml/i.test(ct)) { report(u, src + '-ct', 'mpd'); return true; }
    if (/^video\/mp2t/i.test(ct)) { report(u, src + '-ct', 'ts'); return true; }
    if (/^video\//i.test(ct)) { report(u, src + '-ct', 'mp4'); return true; }
    // Ayrı ses akışı (audio/mp4, audio/aac, audio/webm...): sesin kaçmaması için yakalanır
    if (/^audio\//i.test(ct)) { report(u, src + '-ct', 'm4a'); return true; }
    return false;
  }
  function textOK(ct) { return !ct || /json|text|xml|javascript|mpegurl|dash/i.test(ct); }
  function sniffBody(t, u, src) {
    if (!t) return;
    var head = String(t).slice(0, 2000);
    if (/^\s*#EXTM3U/.test(head)) report(u, src + '-body', 'm3u8');
    else if (/<MPD[\s>]/.test(head)) report(u, src + '-body', 'mpd');
  }
  function scanUrls(t, src) {
    try {
      t = String(t).replace(/\\u0026/g, '&').replace(/\\\//g, '/');
      var re = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mpd|mp4|m4a|aac|weba)(?:\?[^"'\s<>\\]*)?/gi, m, n = 0;
      while ((m = re.exec(t)) && n++ < 30) report(m[0], src + '-json');
    } catch (e) {}
  }

  // ---------- fetch ----------
  var oFetch = window.fetch;
  if (typeof oFetch === 'function') {
    window.fetch = function (input) {
      var u = '';
      try {
        u = (typeof input === 'string') ? input : (input && input.url) ? input.url : String(input);
        byUrl(u, 'fetch');
      } catch (e) {}
      var p = oFetch.apply(this, arguments);
      try {
        p.then(function (res) {
          try {
            var ct = res.headers.get('content-type') || '';
            var ru = res.url || u;
            if (byType(ct, ru, 'fetch')) return;
            var cl = parseInt(res.headers.get('content-length') || '0', 10) || 0;
            if (cl > 1500000 || !textOK(ct)) return;
            res.clone().text().then(function (t) {
              sniffBody(t, ru, 'fetch');
              if (/json/i.test(ct)) scanUrls(t, 'fetch');
            }).catch(function () {});
          } catch (e) {}
        }).catch(function () {});
      } catch (e) {}
      return p;
    };
  }

  // ---------- XMLHttpRequest ----------
  if (typeof XMLHttpRequest !== 'undefined') {
    var oOpen = XMLHttpRequest.prototype.open;
    var oSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__vsUrl = url; byUrl(url, 'xhr'); } catch (e) {}
      return oOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      try {
        x.addEventListener('load', function () {
          try {
            var ct = x.getResponseHeader('content-type') || '';
            var ru = x.responseURL || x.__vsUrl || '';
            if (byType(ct, ru, 'xhr')) return;
            var cl = parseInt(x.getResponseHeader('content-length') || '0', 10) || 0;
            if (cl > 1500000 || !textOK(ct)) return;
            var rt = x.responseType, t = '';
            if (rt === '' || rt === 'text') {
              t = x.responseText;
            } else if (rt === 'json' && x.response) {
              t = JSON.stringify(x.response);
            } else if (rt === 'arraybuffer' && x.response && x.response.byteLength < 1500000) {
              t = String.fromCharCode.apply(null, new Uint8Array(x.response, 0, Math.min(512, x.response.byteLength)));
            }
            if (!t) return;
            sniffBody(t, ru, 'xhr');
            if (/json/i.test(ct) || rt === 'json') scanUrls(t, 'xhr');
          } catch (e) {}
        });
      } catch (e) {}
      return oSend.apply(this, arguments);
    };
  }
})();
""".trimIndent()
}
