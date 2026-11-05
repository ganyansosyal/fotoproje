// Video algılama scripti ve akış (HLS/DASH) yardımcıları.

const hookJs = r'''
(function(){ if(window.__hk) return; window.__hk=1;
 // YouTube'u uygulama kendi yöntemiyle indirir; orada kanca gereksiz ve yavaştır.
 if(/(^|\.)(youtube\.com|youtube-nocookie\.com|googlevideo\.com)$/.test(location.hostname)) return;
 var ID=Math.random().toString(36).slice(2);
 var RE=/\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)(\?|#|\/|&|$)/i;
 // Reklam, izleyici, önizleme ve küçük resim adresleri aday değildir.
 var BAD=/(doubleclick|googlesyndication|googleadservices|imasdk|adservice|adsystem|adnxs|adsrvr|taboola|outbrain|[\/_.-]ads?[\/_.-]|[\/_.-]advert|adserver|preroll|midroll|[\/_.-]vast[\/_.?-]|vpaid|tracking|beacon|analytics|thumb|sprite|storyboard|preview)/i;
 // Parça (segment) adresleri tam video değildir.
 var SEGN=/((seg|segment|chunk|frag|fragment)[-_.]?\d|^init[-_.])/i;
 var SEGQ=/[?&](bytestart|byterange|range|sq|seg|segment|chunk)=/i;
 var cand=[];
 function norm(u){ try{u=new URL(String(u),location.href).href;}catch(e){return ''} return /^https?:/i.test(u)?u:''; }
 function good(u){ if(!u||BAD.test(u)||SEGQ.test(u)) return false;
  var p=u.split('#')[0].split('?')[0]; return !SEGN.test(p.substring(p.lastIndexOf('/')+1)); }
 // Adaylar sadece kaydedilir; Flutter'a oynatıcı açılınca bildirilir.
 function note(u,k){ u=norm(u); if(!u||!good(u)) return; var now=Date.now();
  for(var i=0;i<cand.length;i++){ if(cand[i].u===u){ cand[i].t=now; if(k&&!cand[i].k) cand[i].k=k; poke(); return; } }
  cand.push({u:u,k:k||'',t:now}); if(cand.length>60) cand.shift(); poke(); }
 function ctype(c,u,cr,cl){ c=(c||'').toLowerCase(); if(!u) return;
  if(c.indexOf('mpegurl')>-1){ note(u,'m3u8'); return; }
  if(c.indexOf('dash+xml')>-1){ note(u,'mpd'); return; }
  if(c.indexOf('video/')===0){ var tot=0, m=cr&&/\/(\d+)\s*$/.exec(cr); if(m) tot=+m[1];
   if(!tot) tot=parseInt(cl||'0',10)||0; if(tot>=3000000) note(u,'mp4'); } }
 // Biçime göre tanıma: adres/uzantı ne olursa olsun yanıtın başı HLS (#EXTM3U)
 // veya DASH (<MPD) ise aday sayılır. Yalnızca küçük, metin benzeri yanıtlara bakılır.
 function sniffOK(c){ c=(c||'').toLowerCase(); return !c||/text|json|xml|javascript|octet-stream|mpegurl|plain|binary|css/.test(c); }
 function sniff(t,u){ if(!t||!u) return; t=String(t).replace(/^\uFEFF/,'');
  if(/^\s*#EXTM3U/.test(t)) note(u,'m3u8'); else if(/<MPD[\s>]/.test(t)) note(u,'mpd'); }
 function head(buf){ try{ return String.fromCharCode.apply(null,new Uint8Array(buf,0,Math.min(512,buf.byteLength))); }catch(e){ return ''; } }
 var xo=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(m,u){ var x=this;
  try{ if(RE.test(String(u))) note(u);
   x.addEventListener('load',function(){ try{
    var c=x.getResponseHeader('content-type')||'', ru=x.responseURL||u;
    var cl=parseInt(x.getResponseHeader('content-length')||'0',10)||0;
    ctype(c,ru,x.getResponseHeader('content-range'),x.getResponseHeader('content-length'));
    if(!sniffOK(c)||cl>1500000) return;
    var rt=x.responseType;
    if(rt===''||rt==='text') sniff((x.responseText||'').slice(0,512),ru);
    else if(rt==='arraybuffer'&&x.response&&x.response.byteLength<1500000) sniff(head(x.response),ru);
   }catch(e){} }); }catch(e){}
  return xo.apply(this,arguments)};
 var f=window.fetch;
 if(f) window.fetch=function(i){ var u=typeof i==='string'?i:(i&&i.url);
  try{ if(u&&RE.test(String(u))) note(u); }catch(e){}
  var p=f.apply(this,arguments);
  p.then(function(r){ try{
   var c=r.headers.get('content-type')||'', ru=r.url||u;
   var cl=parseInt(r.headers.get('content-length')||'0',10)||0;
   ctype(c,ru,r.headers.get('content-range'),r.headers.get('content-length'));
   if(!sniffOK(c)||cl>1500000||!r.body) return;
   var rd=r.clone().body.getReader();
   rd.read().then(function(s){ try{rd.cancel();}catch(e){}
    if(s&&s.value) sniff(String.fromCharCode.apply(null,s.value.subarray(0,512)),ru); }).catch(function(){});
  }catch(e){} }).catch(function(){});
  return p; };

 // ---- Oynatıcı takibi: yalnızca gerçekten oynayan video raporlanır ----
 var act=null, wd=0, ft=0, last='', t0=new WeakMap();
 function alive(v){ if(!v||!v.isConnected) return false;
  var r=v.getBoundingClientRect(); if(r.width<=0||r.height<=0) return false;
  var cs=getComputedStyle(v); return cs.visibility!=='hidden'&&cs.display!=='none'; }
 function qual(v){ if(!alive(v)) return false;
  var r=v.getBoundingClientRect(); if(r.width<200||r.height<110) return false;
  var d=v.duration; if(isFinite(d)&&d<2) return false;
  if(v.loop&&(!isFinite(d)||d<180)) return false;
  return true; }
 function send(o){ o.id=ID; try{window.flutter_inappwebview.callHandler('player',JSON.stringify(o));}catch(e){} }
 function stop(){ if(!act) return; act=null; last=''; if(wd){clearInterval(wd);wd=0;} send({stop:true}); }
 function sched(ms){ if(ft) return; ft=setTimeout(report,ms||500); }
 function poke(){ if(act) sched(700); }
 function report(){ ft=0; var v=act; if(!v) return;
  if(!alive(v)){ stop(); return; }
  var d=v.duration; if(isNaN(d)){ sched(600); return; }
  if(!qual(v)) return;
  var live=!isFinite(d), src=v.currentSrc||'', urls=[], hints={}, i;
  var direct=/^https?:/i.test(src)&&good(src);
  if(direct){ urls=[src]; }
  else{
   // Bu videonun yüklenmesinden kısa süre önce/sonra istenen adresler
   var from=(t0.get(v)||(Date.now()-120000))-30000;
   for(i=0;i<cand.length;i++){ if(cand[i].t>=from){ urls.push(cand[i].u); if(cand[i].k) hints[cand[i].u]=cand[i].k; } }
   urls=urls.slice(-12);
   if(!urls.length&&/^blob:/i.test(src)) urls=[src];
  }
  var o={d:live?0:Math.round(d*10)/10, live:live, w:v.videoWidth||0, h:v.videoHeight||0,
   src:direct?src:'', ref:location.href, urls:urls, hints:hints};
  var s=JSON.stringify(o); if(s===last) return; last=s; send(o); }
 function onEv(e){ var v=e.target; if(!v||v.tagName!=='VIDEO') return; var t=e.type;
  if(t==='loadstart'){ t0.set(v,Date.now()); if(v===act) last=''; return; }
  if(t==='emptied'||t==='abort'){ if(v===act) stop(); return; }
  if(t==='durationchange'||t==='loadedmetadata'){ if(v===act){ last=''; sched(400); return; } if(v.paused) return; }
  if(!qual(v)) return;
  if(act!==v){ act=v; last=''; }
  if(!wd) wd=setInterval(function(){ if(act&&!alive(act)) stop(); },2000);
  sched(300);
  setTimeout(function(){ if(act===v) sched(10); },2500);
  setTimeout(function(){ if(act===v) sched(10); },6000); }
 ['loadstart','playing','durationchange','loadedmetadata','emptied','abort'].forEach(function(n){
  document.addEventListener(n,onEv,true); });
})();
''';


/// Videolar kendiliğinden oynamasın: yalnızca kullanıcının dokunuşundan (tıklama,
/// tuş, Shorts'ta kaydırma) sonraki 8 sn içinde başlayan oynatmaya izin verilir.
/// Sayfa bir kez dokunulunca "kullanıcı etkinleşti" sayıldığı için WebView'ın kendi
/// ayarı tek başına yetmiyor; bu script sonraki otomatik oynatmaları da keser.
const noAutoplayJs = r'''
(function(){ if(window.__noap) return; window.__noap=1;
 var last=0, OK=8000;
 function mark(e){ if(e.isTrusted) last=Date.now(); }
 function allowed(){ return Date.now()-last<OK; }
 window.addEventListener('click',mark,true);
 window.addEventListener('keydown',mark,true);
 window.addEventListener('touchend',function(e){
  if(e.isTrusted && location.pathname.indexOf('/shorts')===0) last=Date.now();
 },true);
 var P=window.HTMLMediaElement&&HTMLMediaElement.prototype;
 if(!P) return;
 var op=P.play;
 P.play=function(){
  if(this.__ap||allowed()){ this.__ap=1; return op.apply(this,arguments); }
  return Promise.reject(new DOMException('Autoplay engellendi','NotAllowedError'));
 };
 // autoplay özniteliğiyle başlayanlar play() çağırmaz; olaydan yakalayıp durdur
 document.addEventListener('play',function(e){
  var v=e.target;
  if(!v||!v.pause) return;
  if(v.__ap||allowed()){ v.__ap=1; return; }
  try{ v.pause(); }catch(x){}
 },true);
 // Video bitince / kaynak değişince izin sıfırlanır (sıradaki video kendiliğinden başlamasın)
 function reset(e){ if(e.target) e.target.__ap=0; }
 document.addEventListener('ended',reset,true);
 document.addEventListener('emptied',reset,true);
})();
''';

/// Kaydırma yönünü bildirir (üst barı gizlemek/göstermek için).
/// Yalnızca yön değişince Flutter'a haber verir; kare başına mesaj yok.
const scrollJs = r'''
(function(){ if(window.__sc) return; window.__sc=1;
 var last=new WeakMap(), dir='', mute=0, acc=0;
 function send(d){ if(d===dir) return; dir=d; mute=Date.now()+250; acc=0;
  try{window.flutter_inappwebview.callHandler('scrollDir',d);}catch(e){} }
 var pend=null;
 function handle(el){
  var now=Date.now();
  if(el===document||el===document.documentElement||el===document.body) el=document.scrollingElement||document.documentElement;
  if(!el||el.clientHeight<window.innerHeight*0.4) return;
  var y=el.scrollTop, p=last.get(el); last.set(el,y);
  // Bar gizlenip gösterilirken WebView boyutu değişir; o sırada oluşan kaymalar sayılmaz.
  if(now<mute) return;
  if(p===undefined) return;
  if(y<=8){ acc=0; send('up'); return; }
  var d=y-p; if(!d) return;
  // Ani sıçramalar (sayfa uzunluğu/yerleşim değişimi) parmak kaydırması değildir.
  if(Math.abs(d)>350){ acc=0; return; }
  if((d>0)!==(acc>0)) acc=0;
  acc+=d;
  if(acc>90){ send('down'); } else if(acc<-50){ send('up'); }
 }
 // Kaydırma olayları kare başına en fazla bir kez işlenir (ana iş parçacığı rahat kalır)
 document.addEventListener('scroll',function(e){
  if(pend){ pend=e.target; return; }
  pend=e.target;
  requestAnimationFrame(function(){ var el=pend; pend=null; handle(el); });
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

/// Performans ölçer: sayfada requestAnimationFrame ile kare hızını ölçer.
const perfStartJs = r'''
(function(){ var gen=window.__pg=(window.__pg||0)+1;
 var last=performance.now(), t0=last, frames=0, worst=0, jank=0;
 function tick(n){ if(window.__pg!==gen) return;
  var d=n-last; last=n; frames++; if(d>worst) worst=d; if(d>50) jank++;
  if(n-t0>=500){ try{ window.flutter_inappwebview.callHandler('perf',Math.round(frames*1000/(n-t0)),Math.round(worst),jank); }catch(e){}
   frames=0; worst=0; jank=0; t0=n; }
  requestAnimationFrame(tick); }
 requestAnimationFrame(tick); })();
''';

const perfStopJs = 'window.__pg=(window.__pg||0)+1;';


/// Sayfaların alt güvenli alan (safe-area) boşluğunu kaldırır: `viewport-fit=cover`
/// varsa site (ör. YouTube) kendi alt çubuğunun altına sistem çubuğu kadar boş
/// padding ekler. Meta etiketinden çıkarılınca env(safe-area-inset-*) 0 olur.
const viewportJs = r'''
(function(){ if(window.__vf) return; window.__vf=1;
 function fix(m){ var c=m.getAttribute('content');
  if(c&&/viewport-fit/i.test(c)) m.setAttribute('content',
   c.replace(/\s*,?\s*viewport-fit\s*=\s*[a-z]+/ig,'').replace(/^\s*,\s*/,'')); }
 function scan(){ var m=document.querySelectorAll('meta[name=viewport]');
  for(var i=0;i<m.length;i++) fix(m[i]); }
 scan();
 var mo=new MutationObserver(scan);
 mo.observe(document,{subtree:true,childList:true,attributes:true,attributeFilter:['content']});
 window.addEventListener('load',function(){ scan(); mo.disconnect(); },{once:true});
})();
''';
