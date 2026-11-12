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
    if(vid&&/^\d{6,}$/.test(vid))return 'https://www.facebook.com/watch/?v='+vid;
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
    var u='';try{u=postFor(v)}catch(x){}
    try{__fp.h('fbdl',JSON.stringify([u]))}catch(x){}
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
