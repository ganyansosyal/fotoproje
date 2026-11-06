package com.ganyansosyal.fotoproje

import java.net.URI

/** Video algılama ve akış (HLS/DASH) yardımcıları (eski lib/core/media.dart). */
object Media {
    val HOOK_JS = """
(function(){ if(window.__hk) return; window.__hk=1;
 // YouTube'u uygulama kendi yöntemiyle indirir; orada kanca gereksiz ve yavaştır.
 if(/(^|\.)(youtube\.com|youtube-nocookie\.com|googlevideo\.com)${'$'}/.test(location.hostname)) return;
 var ID=Math.random().toString(36).slice(2);
 var RE=/\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)(\?|#|\/|&|${'$'})/i;
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
  if(c.indexOf('video/')===0){ var tot=0, m=cr&&/\/(\d+)\s*${'$'}/.exec(cr); if(m) tot=+m[1];
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
 var act=null, wd=0, ft=0, last='', showFor=null, t0=new WeakMap();
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
   // Önce bu videonun yüklenmesine yakın (-8sn / +20sn) istekler; yoksa eski geniş pencere
   var st=t0.get(v)||(Date.now()-120000), j, pass;
   for(pass=0;pass<2&&!urls.length;pass++){
    var from=pass?st-30000:st-8000, to=pass?9e15:st+20000;
    for(j=0;j<cand.length;j++){ if(cand[j].t>=from&&cand[j].t<=to){ urls.push(cand[j].u); if(cand[j].k) hints[cand[j].u]=cand[j].k; } }
   }
   urls=urls.slice(-12);
   if(!urls.length&&/^blob:/i.test(src)) urls=[src];
  }
  var o={d:live?0:Math.round(d*10)/10, live:live, w:v.videoWidth||0, h:v.videoHeight||0,
   src:direct?src:'', ref:location.href, urls:urls, hints:hints, show:showFor===v};
  var s=JSON.stringify(o); if(s===last) return; last=s; send(o); if(showFor===v) showFor=null; }
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

 // ---- Her videonun üstünde kendi indirme düğmesi: hangi videoya basıldıysa o video bulunur ----
 var btns=new Map(), lp=0;
 function pick(v){ act=v; last=''; showFor=v;
  if(!wd) wd=setInterval(function(){ if(act&&!alive(act)) stop(); },2000);
  try{ if(v.paused&&v.readyState===0) v.play(); }catch(e){}
  sched(10);
  setTimeout(function(){ if(showFor===v) sched(10); },1500);
  setTimeout(function(){ if(showFor===v) sched(10); },4000);
  setTimeout(function(){ if(showFor===v) showFor=null; },9000); }
 function mkBtn(v){ var b=document.createElement('div');
  b.textContent='\u2193';
  b.style.cssText='position:fixed;z-index:2147483647;width:38px;height:38px;border-radius:19px;'+
   'background:rgba(229,57,53,.92);color:#fff;font:bold 22px/38px sans-serif;text-align:center;'+
   'box-shadow:0 2px 8px rgba(0,0,0,.45);touch-action:manipulation;user-select:none;-webkit-user-select:none';
  function eat(e){ e.stopPropagation(); }
  b.addEventListener('pointerdown',eat,true); b.addEventListener('touchstart',eat,true);
  b.addEventListener('touchend',eat,true); b.addEventListener('mousedown',eat,true);
  b.addEventListener('click',function(e){ e.preventDefault(); e.stopPropagation(); pick(v); },true);
  document.documentElement.appendChild(b); return b; }
 function layout(){ lp=0;
  var vs=document.getElementsByTagName('video'), seen=new Set(), i, vh=window.innerHeight, vw=window.innerWidth;
  for(i=0;i<vs.length;i++){ var v=vs[i]; if(!qual(v)) continue;
   var r=v.getBoundingClientRect();
   if(r.bottom<56||r.top>vh-56||r.right<56||r.left>vw-8) continue;
   var b=btns.get(v); if(!b){ b=mkBtn(v); btns.set(v,b); }
   var top=Math.max(r.top,0)+8; if(top>r.bottom-46) top=Math.max(r.bottom-46,0);
   b.style.top=top+'px'; b.style.left=(Math.min(r.right,vw)-46)+'px'; seen.add(v); }
  btns.forEach(function(b,v){ if(!seen.has(v)){ try{b.remove();}catch(e){} btns.delete(v); } }); }
 function lay(){ if(!lp) lp=requestAnimationFrame(layout); }
 window.addEventListener('scroll',lay,true); window.addEventListener('resize',lay);
 ['playing','loadedmetadata','durationchange','emptied'].forEach(function(n){ document.addEventListener(n,lay,true); });
 setInterval(lay,700);
})();
"""

    val NO_AUTOPLAY_JS = """
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
"""

    val VIEWPORT_JS = """
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
"""

    private val EXT = Regex("\\.(mp4|webm|mov|m4v|mkv|flv|3gp|ogv|avi|wmv)$")

    fun kind(u: String): String? {
        if (u.startsWith("blob:")) return "blob"
        val l = u.lowercase()
        if (l.contains(".m3u8")) return "m3u8"
        if (l.contains(".mpd")) return "mpd"
        val path = try { URI(u).path?.lowercase() ?: l } catch (_: Throwable) { l }
        return EXT.find(path)?.groupValues?.get(1)
    }

    fun hlsAttr(line: String, name: String): String? {
        val m = Regex("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
        return if (m.groups[2] != null) m.groupValues[2] else m.groupValues[1]
    }

    fun hexBytes(s: String): ByteArray {
        var h = s.replaceFirst(Regex("^0[xX]"), "")
        h = h.padStart(32, '0')
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun isoSeconds(s: String?): Double? {
        if (s == null) return null
        val m = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?$").find(s) ?: return null
        fun g(i: Int) = m.groupValues[i]
        return (g(1).toIntOrNull() ?: 0) * 86400 + (g(2).toIntOrNull() ?: 0) * 3600 +
            (g(3).toIntOrNull() ?: 0) * 60 + (g(4).toDoubleOrNull() ?: 0.0)
    }

    /** Göreli adresi [base]'e göre çözer. */
    fun resolve(base: String, ref: String): String = try {
        java.net.URL(java.net.URL(base), ref).toString()
    } catch (_: Throwable) {
        ref
    }

    fun fmtDuration(s: Double): String {
        val t = Math.round(s).toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    fun fmtBytes(b: Long): String = when {
        b <= 0 -> ""
        b < 1048576 -> String.format("%.0f KB", b / 1024.0)
        b < 1073741824 -> String.format("%.1f MB", b / 1048576.0)
        else -> String.format("%.2f GB", b / 1073741824.0)
    }
}
