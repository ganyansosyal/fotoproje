import 'dart:convert';

import '../core/settings.dart';
import '../core/strings.dart';

/// Ana sayfa (yeni sekme) HTML'i. WebView içinde, arka plan titremesi olmaması
/// için tema rengiyle birebir aynı zeminle çizilir.
String buildHomeHtml(AppSettings s, bool dark) {
  const esc = HtmlEscape(HtmlEscapeMode.attribute);
  final bg = dark ? '#121212' : '#ffffff';
  final fg = dark ? '#eeeeee' : '#1b1b1f';
  final card = dark ? '#2a2c33' : '#eef0fb';
  final sub = dark ? '#9aa0a6' : '#5f6368';
  const palette = [
    '#1a73e8', '#e8453c', '#0f9d58', '#f29900', '#9334e6', '#00897b', '#d81b60',
    '#5c6bc0'
  ];
  final tiles = StringBuffer();
  var k = 0;
  for (final e in s.homeSites()) {
    final name = e[0];
    final letter = name.isEmpty ? '?' : name.characters0;
    final col = palette[k++ % palette.length];
    tiles.write('<a class="t" href="${esc.convert(e[1])}">'
        '<div class="i" style="background:$col">${esc.convert(letter)}</div>'
        '<span>${esc.convert(name)}</span></a>');
  }
  final base = jsonEncode(searchEngines[s.engine] ?? searchEngines['Google']);
  return '''<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:$bg;color:$fg;font-family:sans-serif}
body{padding:28px 16px 40px;min-height:100vh;box-sizing:border-box}
h1{text-align:center;font-weight:300;font-size:28px;margin:26px 0 8px}
p{text-align:center;margin:0 0 22px;color:$sub;font-size:13px}
form{max-width:520px;margin:0 auto 30px}
input{width:100%;box-sizing:border-box;padding:14px 20px;border-radius:28px;border:0;
background:$card;color:$fg;font-size:16px;outline:none}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:20px 8px;max-width:520px;margin:auto}
.t{text-decoration:none;color:$fg;text-align:center;font-size:12px;transition:transform .12s}
.t:active{transform:scale(.92)}
.i{width:56px;height:56px;border-radius:50%;margin:0 auto 6px;color:#fff;
display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:600}
.t span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style></head><body>
<h1>${esc.convert(S.t('home'))}</h1><p>${esc.convert(S.t('favHint'))}</p>
<form id="f"><input id="q" placeholder="${esc.convert(S.t('search'))}" autocomplete="off"></form>
<div class="g">$tiles</div>
<script>
var base=$base;
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
