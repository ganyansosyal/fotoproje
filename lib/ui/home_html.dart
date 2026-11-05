import 'dart:convert';

import '../core/settings.dart';
import '../core/strings.dart';

/// Ana sayfa (yeni sekme) HTML'i. WebView içinde, arka plan titremesi olmaması
/// için tema rengiyle birebir aynı zeminle çizilir.
String buildHomeHtml(AppSettings s, bool dark) {
  const esc = HtmlEscape(HtmlEscapeMode.attribute);
  final bg = dark ? '#202124' : '#ffffff';
  final fg = dark ? '#e8eaed' : '#202124';
  final card = dark ? '#303134' : '#f1f3f4';
  final sub = dark ? '#9aa0a6' : '#5f6368';
  final tiles = StringBuffer();
  for (final e in s.homeSites()) {
    final name = e[0];
    var u = e[1].trim();
    if (!u.contains('://')) u = 'https://$u';
    final host = Uri.tryParse(u)?.host ?? '';
    tiles.write('<a class="t" href="${esc.convert(u)}">'
        '<div class="i"><img class="lg" alt="" data-h="${esc.convert(host)}"></div>'
        '<span>${esc.convert(name)}</span></a>');
  }
  tiles.write('<a class="t" href="#" id="addfav">'
      '<div class="i" style="background:#5f6368;color:#fff">+</div>'
      '<span>${esc.convert(S.t('add'))}</span></a>');
  final base = jsonEncode(searchEngines[s.engine] ?? searchEngines['Google']);
  return '''<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:$bg;color:$fg;font-family:sans-serif}
body{padding:28px 16px 40px;min-height:100vh;box-sizing:border-box}
h1{text-align:center;font-weight:700;font-size:28px;margin:26px 0 8px}
p{text-align:center;margin:0 0 22px;color:$sub;font-size:13px}
form{max-width:520px;margin:0 auto 30px}
input{width:100%;box-sizing:border-box;padding:14px 20px;border-radius:28px;border:0;
background:$card;color:$fg;font-size:16px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:20px 8px;max-width:520px;margin:auto}
.t{text-decoration:none;color:$fg;text-align:center;font-size:12px;transition:transform .12s}
.t:active{transform:scale(.92)}
.i{width:56px;height:56px;border-radius:50%;margin:0 auto 6px;background:$card;overflow:hidden;
display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:600}
.i img{width:32px;height:32px;object-fit:contain}
.t span{display:block;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style></head><body>
<h1>${esc.convert(S.t('home'))}</h1><p>${esc.convert(S.t('favHint'))}</p>
<form id="f"><input id="q" readonly inputmode="none" placeholder="${esc.convert(S.t('search'))}" autocomplete="off"></form>
<div class="g">$tiles</div>
<script>
var base=$base;
var globe='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9aa0a6" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/></svg>');
document.querySelectorAll('img.lg').forEach(function(im){
 var h=im.getAttribute('data-h'), k=0;
 var src=['https://www.google.com/s2/favicons?domain='+h+'&sz=128','https://icons.duckduckgo.com/ip3/'+h+'.ico','https://'+h+'/favicon.ico'];
 function next(){ im.src = k<src.length ? src[k++] : globe; }
 im.onerror=function(){ if(im.src!==globe) next(); };
 im.onload=function(){ if(im.src.indexOf('s2/favicons')>0 && im.naturalWidth<=16) next(); };
 if(!h){ im.src=globe; } else { next(); }
});
function H(n,a,b){try{window.flutter_inappwebview.callHandler(n,a,b)}catch(e){}}
document.getElementById('q').addEventListener('click',function(e){e.preventDefault();this.blur();H('focusBar','','')});
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
 if(/^[^\\s]+\\.[^\\s]+\$/.test(v)){ location.href=/^https?:\\/\\//i.test(v)?v:'https://'+v; }
 else { location.href=base+encodeURIComponent(v); }
});
</script></body></html>''';
}

extension _FirstChar on String {
  String get characters0 {
    final r = runes.isEmpty ? '?' : String.fromCharCode(runes.first);
    return r.toUpperCase();
  }
}
