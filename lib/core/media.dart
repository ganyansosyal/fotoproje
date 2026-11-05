// Video algılama scripti ve akış (HLS/DASH) yardımcıları.

const hookJs = r'''
(function(){ if(window.__hk) return; window.__hk=1; window.__media=[];
 var EXT='m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv';
 var re=new RegExp('\\.('+EXT+')(\\?|#|\\/|&|$)','i');
 var quick=new RegExp('\\.('+EXT+')','i');
 var rx=new RegExp('https?:(?:\\\\?\\/){2}[^"\'\\s<>()\\\\]+?\\.(?:'+EXT+')(?:\\?[^"\'\\s<>()\\\\]*)?','gi');
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
 function scan(t,max){ if(!t||t.length>(max||600000)) return;
  if(!quick.test(t)) return;
  t=t.replace(/\\\//g,'/').replace(/\\u0026/g,'&').replace(/&amp;/g,'&');
  var m=t.match(rx); if(m) m.forEach(function(u){add(u)}); }
 var busy=false, again=false;
 function dom(){ if(busy){again=true;return} busy=true;
  var run=function(){ try{
   document.querySelectorAll('video,audio,source,embed').forEach(function(e){
    var u=e.currentSrc||e.src; if(u) send(u); });
   document.querySelectorAll('meta[property^="og:video"],meta[name^="twitter:player:stream"]').forEach(function(m){
    var c=m.getAttribute('content'); if(c) add(c); });
   var h=document.documentElement.innerHTML; scan(h,1500000);
  }catch(e){} busy=false; if(again){again=false; setTimeout(dom,1500)} };
  if(window.requestIdleCallback) requestIdleCallback(run,{timeout:2500}); else setTimeout(run,50); }
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
   var len=parseInt(res.headers.get('content-length')||'0',10);
   if(len>600000) return;
   if(/json|text|javascript|xml/i.test(ct)) res.clone().text().then(function(t){scan(t)}).catch(function(){});
  }catch(e){} }).catch(function(){});
  return p; };
 var pt=0;
 document.addEventListener('play',function(){ var n=Date.now(); if(n-pt>1500){pt=n; dom();} },true);
 window.addEventListener('load',function(){ setTimeout(dom,800); });
 setTimeout(dom,3000); setTimeout(dom,8000); setTimeout(dom,16000);
})();
''';

/// Kaydırma yönünü bildirir (üst barı gizlemek/göstermek için).
/// Yalnızca yön değişince Flutter'a haber verir; kare başına mesaj yok.
const scrollJs = r'''
(function(){ if(window.__sc) return; window.__sc=1;
 var last=new WeakMap(), dir='', mute=0;
 function send(d){ if(d===dir) return; dir=d; mute=Date.now()+450;
  try{window.flutter_inappwebview.callHandler('scrollDir',d);}catch(e){} }
 document.addEventListener('scroll',function(e){
  if(Date.now()<mute) return;
  var el=e.target;
  if(el===document||el===document.documentElement||el===document.body) el=document.scrollingElement||document.documentElement;
  if(!el||el.clientHeight<window.innerHeight*0.5) return;
  var y=el.scrollTop, p=last.get(el); last.set(el,y);
  if(p===undefined) return;
  if(y<=8){ send('up'); return; }
  var d=y-p; if(d>12) send('down'); else if(d<-12) send('up');
 },{capture:true,passive:true});
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
