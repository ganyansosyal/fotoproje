package com.ganyansosyal.fotoproje

/**
 * Facebook: her videonun köşesine küçük indirme düğmesi.
 * Düğmeye basılınca adres, o videonun KENDİ öğesinden (üst öğelerdeki gönderi bağlantısı / video kimliği) bulunur;
 * "en çok ekranda görünen video" tahmini yapılmaz. Bulunamazsa kullanıcıya haber verilir (başka videonun adresi denenmez).
 * Sayfa -> Kotlin: __fp.h('fbdl', '[postUrl]')  (bkz. BrowserActivity.onJs)
 */
object FbDl {
    val JS: String = """
(function(){try{
if(window.__fpFbDl)return;
if(window.top!==window)return;
if(!/(^|[.])(facebook[.]com|fb[.]watch)/i.test(location.hostname))return;
window.__fpFbDl=1;
var POST=/(\/reel\/\d+|\/videos\/\d+|\/watch\/?\?(?:[^#]*&)?v=\d+|\/video\.php\?(?:[^#]*&)?v=\d+|\/posts\/[\w.-]+|\/permalink\.php\?|\/permalink\/|\/story\.php\?|\/share\/[rvp]\/[\w-]+|fb\.watch\/[\w-]+)/i;
var abs=function(h){try{return new URL(h,location.href).href}catch(e){return ''}};
var good=function(h){h=abs(h);return (h&&/^https?:\/\/[^\/]*(facebook\.com|fb\.watch)/i.test(h)&&POST.test(h))?h.split('#')[0]:''};

// Bu videonun kendi gönderi adresi (bulunamazsa boş)
function postFor(v){
  var el=v;
  for(var i=0;el&&i<16;i++,el=el.parentElement){
    var ds=el.getAttribute&&el.getAttribute('data-store');
    if(ds){var m=/"videoID":"?(\d{6,})/.exec(ds);if(m)return 'https://www.facebook.com/watch/?v='+m[1];}
    var vid=el.getAttribute&&(el.getAttribute('data-video-id')||el.getAttribute('data-videoid'));
    if(vid&&/^\d{6,}${'$'}/.test(vid))return 'https://www.facebook.com/watch/?v='+vid;
    var as=el.querySelectorAll?el.querySelectorAll('a[href]'):[];
    for(var j=0;j<as.length;j++){var g=good(as[j].getAttribute('href'));if(g)return g;}
    if(el.tagName==='ARTICLE'||(el.getAttribute&&el.getAttribute('role')==='article'))break;
  }
  // Reel / video sayfasındaysak adres çubuğundaki adres bu videonundur
  var self=good(location.href);
  if(self&&/\/(reel|videos|watch)/i.test(self)){
    var vs=document.querySelectorAll('video'),best=null,bs=-1;
    for(var k=0;k<vs.length;k++){var r=vs[k].getBoundingClientRect();
      var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
      var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
      if(w*h>bs){bs=w*h;best=vs[k];}}
    if(best===v)return self;
  }
  return '';
}


// ---- Sesli (görüntü+ses birleşik) mp4 adresi: sayfayı TARAYICININ KENDİSİ çeker (yt-dlp / uygulama HTTP'si Facebook'ta 500 alıyor)
var busy=false;
function unesc(t){return String(t).replace(/\\u0025/g,'%').replace(/\\u0026/g,'&').replace(/\\u003C/gi,'<').replace(/\\\//g,'/').replace(/\\\\/g,'\\');}
var PRI={browser_native_hd_url:4,playable_url_quality_hd:3,browser_native_sd_url:2,playable_url:1};
function nearest(c,text,id){
  if(!c.length)return null;
  var base=c[0];
  if(id){
    var ids=[],at=-1;
    while(ids.length<60&&(at=text.indexOf(id,at+1))>=0)ids.push(at);
    if(ids.length){
      var bd=1e12;
      c.forEach(function(x){ids.forEach(function(q){var d=Math.abs(x.p-q);if(d<bd){bd=d;base=x;}});});
      if(bd>6000)return null;
    }
  }
  return base;
}
// Sesli+görüntülü (muxed) mp4
function pickMuxed(text,id){
  var re=/"(browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url)"\s*:\s*"(https?:[^"]+)"/g,m,c=[];
  while((m=re.exec(text))&&c.length<80)c.push({k:m[1],u:m[2],p:m.index});
  var base=nearest(c,text,id);if(!base)return '';
  var best=null;
  c.forEach(function(x){if(Math.abs(x.p-base.p)<3000&&(!best||PRI[x.k]>PRI[best.k]))best=x;});
  var url=unesc(best.u);
  return /^https?:\/\//i.test(url)?url:'';
}
// Ayrı görüntü + ayrı ses (DASH manifest): {v,a}
function pickDash(text,id){
  var re=/"(?:dash_manifest|dash_manifest_xml_string|manifest_xml)"\s*:\s*"((?:[^"\\]|\\.)*)"/g,m,c=[];
  while((m=re.exec(text))&&c.length<20)c.push({s:m[1],p:m.index});
  var base=nearest(c,text,id);if(!base)return null;
  var xml;
  try{xml=JSON.parse('"'+base.s+'"');}catch(e){return null;}
  var d;
  try{d=new DOMParser().parseFromString(xml,'text/xml');}catch(e){return null;}
  var reps=d.getElementsByTagName('Representation'),bv=null,ba=null;
  for(var i=0;i<reps.length;i++){
    var r=reps[i],set=r.parentNode,
        mt=(r.getAttribute('mimeType')||(set&&set.getAttribute&&set.getAttribute('mimeType'))||(set&&set.getAttribute&&set.getAttribute('contentType'))||'').toLowerCase(),
        bu=r.getElementsByTagName('BaseURL')[0],u=bu?String(bu.textContent||'').trim():'';
    if(!/^https?:\/\//i.test(u))continue;
    var bw=+r.getAttribute('bandwidth')||0,h=+r.getAttribute('height')||0;
    if(/audio/.test(mt)){if(!ba||bw>ba.bw)ba={u:u,bw:bw};}
    else if(/video/.test(mt)){
      var avc=/avc/i.test(r.getAttribute('codecs')||'')?1:0;
      if(!bv||h>bv.h||(h===bv.h&&(avc>bv.avc||(avc===bv.avc&&bw>bv.bw))))bv={u:u,h:h,bw:bw,avc:avc};
    }
  }
  return (bv&&ba)?{v:bv.u,a:ba.u}:null;
}
function pickAll(text,id){
  if(!text)return {m:'',v:'',a:''};
  var m=pickMuxed(text,id),d=pickDash(text,id);
  return {m:m,v:d?d.v:'',a:d?d.a:''};
}
function videoId(post){
  var m=/[?&]v=(\d{6,})/.exec(post)||/\/(?:reels?|videos)\/(\d{6,})/.exec(post);
  return m?m[1]:'';
}
function directFor(post){
  return new Promise(function(res){
    var id=videoId(post),done=false;
    function fin(x){if(!done){done=true;res(x||{m:'',v:'',a:''});}}
    setTimeout(function(){fin(null)},15000);
    function ok(x){return x&&(x.m||(x.v&&x.a));}
    function fromDoc(){
      try{var ss=document.querySelectorAll('script[type="application/json"]'),t=[];
        for(var i=0;i<ss.length&&i<400;i++)t.push(ss[i].textContent);
        return pickAll(t.join('\n'),id);}catch(e){return null;}
    }
    if(!post||/fb\.watch/i.test(post)){fin(fromDoc());return;}
    var pu;
    try{pu=new URL(post);pu.protocol=location.protocol;pu.host=location.host;}catch(e){fin(fromDoc());return;}
    fetch(pu.href,{credentials:'include'}).then(function(r){return r.text()}).then(function(t){
      var x=pickAll(t,id);
      fin(ok(x)?x:fromDoc());
    }).catch(function(){fin(fromDoc());});
  });
}

var layer=null,btns=new Map();
function ensureLayer(){
  if(layer&&layer.isConnected)return layer;
  if(!document.body)return null;
  layer=document.createElement('div');
  layer.style.cssText='position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none';
  document.body.appendChild(layer);
  btns.forEach(function(b){layer.appendChild(b)});
  return layer;
}
function stop(e){e.stopPropagation();}
function mk(v){
  var b=document.createElement('div');
  b.setAttribute('role','button');
  b.setAttribute('aria-label','Download');
  b.style.cssText='position:fixed;width:38px;height:38px;border-radius:19px;background:rgba(0,0,0,.62);display:none;align-items:center;justify-content:center;pointer-events:auto;box-shadow:0 1px 4px rgba(0,0,0,.5);touch-action:manipulation';
  b.innerHTML='<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11"/><path d="M7 11l5 5 5-5"/><path d="M5 20h14"/></svg>';
  ['touchstart','touchend','pointerdown','pointerup','mousedown','mouseup'].forEach(function(n){b.addEventListener(n,stop,{passive:true})});
  b.addEventListener('click',function(e){
    e.preventDefault();e.stopPropagation();
    if(busy)return; busy=true;
    setTimeout(function(){busy=false},20000);
    var u='';try{u=postFor(v)}catch(x){}
    try{__fp.h('fbwait','[]')}catch(x){}
    directFor(u).then(function(d){
      busy=false;
      try{__fp.h('fbdl',JSON.stringify([u,d.m||'',d.v||'',d.a||'']))}catch(x){}
    },function(){
      busy=false;
      try{__fp.h('fbdl',JSON.stringify([u,'','','']))}catch(x){}
    });
  },true);
  return b;
}
function tick(){
  var L=ensureLayer();if(!L)return;
  var vs=document.querySelectorAll('video'),vw=innerWidth,vh=innerHeight,live=new Set();
  for(var i=0;i<vs.length;i++){
    var v=vs[i],r=v.getBoundingClientRect();
    if(r.width<120||r.height<80)continue;
    var visH=Math.min(r.bottom,vh)-Math.max(r.top,0),visW=Math.min(r.right,vw)-Math.max(r.left,0);
    if(visH<60||visW<100)continue;
    live.add(v);
    var b=btns.get(v);
    if(!b){b=mk(v);btns.set(v,b);L.appendChild(b);}
    b.style.display='flex';
    b.style.left=(Math.min(r.right,vw)-46)+'px';
    b.style.top=(Math.max(r.top,0)+8)+'px';
  }
  btns.forEach(function(b,v){
    if(live.has(v))return;
    if(!v.isConnected){b.remove();btns.delete(v);}else b.style.display='none';
  });
}
var pend=false;
function soon(){if(pend)return;pend=true;requestAnimationFrame(function(){pend=false;try{tick()}catch(e){}});}
addEventListener('scroll',soon,true);
addEventListener('resize',soon);
setInterval(function(){try{tick()}catch(e){}},400);
}catch(e){}})();
""".trimIndent()
}
