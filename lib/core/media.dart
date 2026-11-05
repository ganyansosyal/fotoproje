// Video algılama scripti ve akış (HLS/DASH) yardımcıları.

const hookJs = r'''
(function(){ if(window.__hk) return; window.__hk=1; window.__media=[];
 var re=/\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)(\?|#|\/|&|$)/i;
 var rx=/https?:(?:\\?\/){2}[^"'\s<>()\\]+?\.(?:m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)(?:\?[^"'\s<>()\\]*)?/gi;
 var seen={};
 function send(u,k){ try{u=new URL(u,location.href).href;}catch(e){return}
  if(!/^(https?|blob):/.test(u)||seen[u]) return; seen[u]=1; window.__media.push(u);
  try{window.flutter_inappwebview.callHandler('media',u,k||'');}catch(e){} }
 function add(u){ if(u && re.test(String(u))) send(u); }
 function ctype(ct,u){ ct=(ct||'').toLowerCase();
  if(ct.indexOf('mpegurl')>-1){send(u,'m3u8');return true}
  if(ct.indexOf('dash+xml')>-1){send(u,'mpd');return true}
  if(ct.indexOf('video/')===0){send(u,'mp4');return true}
  return false; }
 function scan(t){ if(!t||t.length>3000000) return;
  t=t.replace(/\\\//g,'/').replace(/\\u0026/g,'&').replace(/&amp;/g,'&');
  var m=t.match(rx); if(m) m.forEach(function(u){add(u)}); }
 function dom(){ try{
  document.querySelectorAll('video,audio,source,embed').forEach(function(e){
   var u=e.currentSrc||e.src; if(u) send(u); });
  document.querySelectorAll('a[href]').forEach(function(a){ add(a.href); });
  document.querySelectorAll('meta[property^="og:video"],meta[name^="twitter:player:stream"]').forEach(function(m){
   var c=m.getAttribute('content'); if(c) add(c); });
  scan(document.documentElement.innerHTML);
 }catch(e){} }
 var o=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(m,u){ add(u); var x=this;
  try{ x.addEventListener('load',function(){ try{
   var ct=x.getResponseHeader('content-type')||'';
   if(ctype(ct,x.responseURL||u)) return;
   var rt=x.responseType;
   if((rt===''||rt==='text')&&/json|text|javascript|xml/i.test(ct)) scan(x.responseText);
  }catch(e){} }); }catch(e){}
  return o.apply(this,arguments)};
 var f=window.fetch;
 if(f) window.fetch=function(i){ var u=typeof i==='string'?i:(i&&i.url); add(u);
  var p=f.apply(this,arguments);
  p.then(function(res){ try{
   var ct=res.headers.get('content-type')||'';
   if(ctype(ct,res.url||u)) return;
   if(/json|text|javascript|xml/i.test(ct)) res.clone().text().then(scan).catch(function(){});
  }catch(e){} }).catch(function(){});
  return p; };
 document.addEventListener('play',dom,true);
 document.addEventListener('DOMContentLoaded',dom);
 window.addEventListener('load',dom);
 var n=0; var iv=setInterval(function(){dom(); if(++n>30) clearInterval(iv)},2000);
})();
''';

String? mediaKind(String u) {
  if (u.startsWith('blob:')) return 'blob';
  final l = u.toLowerCase();
  if (l.contains('.m3u8')) return 'm3u8';
  if (l.contains('.mpd')) return 'mpd';
  final path = (Uri.tryParse(u)?.path ?? l).toLowerCase();
  return RegExp(r'\.(mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)$')
      .firstMatch(path)
      ?.group(1);
}

String? hlsAttr(String line, String name) {
  final m = RegExp('(?:^|[:,])$name=("([^"]*)"|[^,]*)').firstMatch(line);
  return m == null ? null : (m.group(2) ?? m.group(1));
}

List<int> hexBytes(String s) {
  var h = s.replaceFirst(RegExp(r'^0[xX]'), '');
  h = h.padLeft(32, '0');
  return [for (var i = 0; i < h.length; i += 2) int.parse(h.substring(i, i + 2), radix: 16)];
}

double? isoSeconds(String? s) {
  if (s == null) return null;
  final m = RegExp(r'^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$')
      .firstMatch(s);
  if (m == null) return null;
  return (int.tryParse(m[1] ?? '') ?? 0) * 86400 +
      (int.tryParse(m[2] ?? '') ?? 0) * 3600 +
      (int.tryParse(m[3] ?? '') ?? 0) * 60 +
      (double.tryParse(m[4] ?? '') ?? 0);
}
