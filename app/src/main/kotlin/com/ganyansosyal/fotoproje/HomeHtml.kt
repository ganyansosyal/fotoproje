package com.ganyansosyal.fotoproje

import android.text.Html
import org.json.JSONObject

/** Ana sayfa (yeni sekme) HTML'i ve logo (favicon) önbelleği. */
object HomeHtml {
    private fun esc(s: String) = Html.escapeHtml(s).replace("\"", "&quot;")

    fun hosts(): List<String> = Store.homeSites().map {
        var u = it[1].trim()
        if (!u.contains("://")) u = "https://$u"
        hostOf(u)
    }.filter { it.isNotEmpty() }

    fun build(dark: Boolean): String {
        val th = Theme.of(dark)
        val bg = Theme.hex(th.bg)
        val fg = Theme.hex(th.fg)
        val card = Theme.hex(th.surface)
        val sub = Theme.hex(th.mut)
        val line = Theme.hex(th.line)
        val tiles = StringBuilder()
        for (e in Store.homeSites()) {
            val name = e[0]
            var u = e[1].trim()
            if (!u.contains("://")) u = "https://$u"
            val host = hostOf(u)
            val cached = FavIcons.get(host)
            val img = if (cached != null)
                "<img class=\"lg ok\" width=\"32\" height=\"32\" alt=\"\" src=\"$cached\">"
            else
                "<img class=\"lg\" width=\"32\" height=\"32\" alt=\"\" data-h=\"${esc(host)}\">"
            tiles.append("<a class=\"t\" href=\"${esc(u)}\"><div class=\"i\">$img</div><span>${esc(name)}</span></a>")
        }
        tiles.append(
            "<a class=\"t\" href=\"#\" id=\"addfav\"><div class=\"i\" style=\"background:$sub;color:$bg\">+</div>" +
                "<span>${esc(L.t("add"))}</span></a>"
        )
        return TEMPLATE
            .replace("@@bg@@", bg).replace("@@fg@@", fg).replace("@@card@@", card).replace("@@sub@@", sub).replace("@@line@@", line)
            .replace("@@tiles@@", tiles.toString())
            .replace("@@base@@", JSONObject.quote(Store.engineBase()))
            .replace("@@home@@", esc(L.t("home")))
            .replace("@@favHint@@", esc(L.t("favHint")))
            .replace("@@search@@", esc(L.t("search")))
    }

    private val TEMPLATE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:@@bg@@;color:@@fg@@;font-family:sans-serif;overscroll-behavior-y:auto}
body{padding:20px 16px 32px;min-height:100vh;box-sizing:border-box}
h1{text-align:center;font-weight:600;font-size:22px;margin:22px 0 6px}
p{text-align:center;margin:0 0 20px;color:@@sub@@;font-size:12px}
form{max-width:520px;margin:0 auto 30px}
input{width:100%;box-sizing:border-box;padding:12px 16px;border-radius:8px;
background:@@card@@;color:@@fg@@;font-size:14px;outline:none;border:1px solid @@line@@}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:18px 8px;max-width:520px;margin:auto}
.t{text-decoration:none;color:@@fg@@;text-align:center;font-size:11px;transform:translate3d(0,0,0);will-change:transform;transition:transform .15s ease-out}
.t:active{transform:translate3d(0,0,0) scale(.92)}
.i{width:46px;height:46px;border-radius:8px;border:1px solid @@line@@;margin:0 auto 5px;background:@@card@@;overflow:hidden;
display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:600}
.i img{width:26px;height:26px;object-fit:contain;opacity:0;transition:opacity .2s}
.i img.ok,.i img.show{opacity:1}
.i img.ok{transition:none}
.t span{display:block;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style></head><body>
<h1>@@home@@</h1><p>@@favHint@@</p>
<form id="f"><input id="q" type="text" enterkeyhint="go" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="@@search@@" autocomplete="off"></form>
<div class="g">@@tiles@@</div>
<script>
var base=@@base@@;
var globe='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9aa0a6" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/></svg>');
document.querySelectorAll('img.lg:not(.ok)').forEach(function(im){
 var h=im.getAttribute('data-h'), k=0;
 var src=['https://www.google.com/s2/favicons?domain='+h+'&sz=128','https://icons.duckduckgo.com/ip3/'+h+'.ico','https://'+h+'/favicon.ico'];
 function next(){ im.src = k<src.length ? src[k++] : globe; }
 im.onerror=function(){ if(im.src!==globe) next(); };
 im.onload=function(){ if(im.src.indexOf('s2/favicons')>0 && im.naturalWidth<=16){ next(); return; } im.classList.add('show'); };
 if(!h){ im.src=globe; } else { next(); }
});
function H(n,a,b){try{window.flutter_inappwebview.callHandler(n,a,b)}catch(e){}}
document.getElementById('addfav').addEventListener('click',function(e){e.preventDefault();H('addFav','','')});
document.querySelectorAll('.t').forEach(function(a){
 if(a.id==='addfav') return;
 var tm=0, lp=false;
 a.addEventListener('touchstart',function(){ lp=false; tm=setTimeout(function(){ lp=true;
   H('favMenu',a.getAttribute('href'),a.querySelector('span').textContent)},500)},{passive:true});
 a.addEventListener('touchend',function(){clearTimeout(tm)},{passive:true});
 a.addEventListener('touchmove',function(){clearTimeout(tm)},{passive:true});
 a.addEventListener('click',function(e){ if(lp){e.preventDefault(); lp=false;} });
 a.addEventListener('contextmenu',function(e){e.preventDefault()});
});
document.getElementById('f').addEventListener('submit',function(e){
 e.preventDefault(); var v=document.getElementById('q').value.trim(); if(!v) return;
 if(/^[^\s]+\.[^\s]+${'$'}/.test(v)){ location.href=/^https?:\/\//i.test(v)?v:'https://'+v; }
 else { location.href=base+encodeURIComponent(v); }
});
</script></body></html>"""
}
