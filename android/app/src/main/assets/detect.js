/* Inspect selected WKWebView frames. Never capture cookies, passwords or media segments. */
(function(root){
 'use strict';if(root.__darwinDetect)return;root.__darwinDetect=true;
 function key(raw){const u=new URL(raw);u.hash='';for(const k of [...u.searchParams.keys()])if(/^(token|sig|signature|expires?|exp|policy|key-pair-id|auth|hdnea|hdnts|_t|timestamp)$/i.test(k))u.searchParams.delete(k);u.searchParams.sort();return u.href;}
 function youtube(raw){
  try{const u=new URL(raw),host=u.hostname.toLowerCase();if(!['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','youtu.be','www.youtu.be','www.youtube-nocookie.com','youtube-nocookie.com'].includes(host))return null;
   if(!/^https?:$/.test(u.protocol)||u.username||u.password)return null;
   const parts=u.pathname.split('/').filter(Boolean),id=host.endsWith('youtu.be')?parts[0]:u.pathname==='/watch'?u.searchParams.get('v'):['shorts','live','embed'].includes(parts[0])?parts[1]:null;
   return /^[A-Za-z0-9_-]{11}$/.test(id||'')?'https://www.youtube.com/watch?v='+id:null;
  }catch(e){return null;}
 }
 function kind(raw,type,element){
  let path;try{path=new URL(raw).pathname.toLowerCase();}catch(e){return null;}
  type=(type||'').toLowerCase();
  if(/\.(ts|m4s|m4a|aac|mp3|vtt|srt|jpg|jpeg|png|gif|key)$/.test(path)||/audio\//.test(type))return null;
  if(/(?:^|[/_-])(?:seg(?:ment)?|chunk|frag(?:ment)?|init)[-_\d.]/i.test(path))return null;
  if(/\.m3u8$/.test(path)||/mpegurl/.test(type))return 'HLS';
  if(/\.mpd$/.test(path)||/dash\+xml/.test(type))return 'DASH';
  if(/\.(mp4|webm|mov)$/.test(path)||(element&&/^https?:/.test(raw)))return 'Video';
  return null;
 }
 function variants(text,base){
  const lines=text.split(/\r?\n/),rows=[];
  for(let i=0;i<lines.length;i++)if(lines[i].startsWith('#EXT-X-STREAM-INF:')){
   const height=Number((/RESOLUTION=\d+x(\d+)/i.exec(lines[i])||[])[1])||0;
   const next=lines.slice(i+1).find(x=>x.trim()&&!x.startsWith('#'));
   if(next)try{rows.push({url:new URL(next.trim(),base).href,height});}catch(e){}
  }
  return rows.slice(0,30);
 }
 root.DarwinDetection={key,kind,variants,youtube};
 if(typeof document==='undefined')return;
 function post(value){console.info('DARWIN_MEDIA:'+JSON.stringify(value));}
 const sent=new Map(),parents=new Map();let pageURL='',pageTitle='';
 function pageChanged(){
  const changed=pageURL!==location.href,titleChanged=pageTitle!==document.title;
  if(changed){if(window.__darwinPauseBrowse)window.__darwinPauseBrowse();sent.clear();parents.clear();pageURL=location.href;}
  pageTitle=document.title;
  if((changed||titleChanged)&&window===window.top)post({event:'page',url:location.href,title:document.title});
  const canonical=youtube(location.href);
  if(canonical){const message={url:canonical,group:canonical,kind:'YouTube',referer:location.href,title:document.title};const stamp=JSON.stringify(message);if(sent.get(canonical)!==stamp){sent.set(canonical,stamp);post(message);}}
 }
 for(const name of ['pushState','replaceState']){const original=history[name];history[name]=function(){const result=original.apply(this,arguments);pageChanged();return result;};}
 addEventListener('popstate',pageChanged);addEventListener('yt-navigate-finish',pageChanged);addEventListener('DOMContentLoaded',pageChanged);

 function send(raw,type,element=false,extra={}){
  try{if(typeof raw!=='string'||!raw.trim())return;
   if(youtube(location.href)){pageChanged();return;}
   const url=new URL(raw,location.href).href;if(!/^https?:/.test(url)||url===location.href)return;
   const format=kind(url,type,element);if(!format)return;
   const group=parents.get(key(url))||key(url),message={url,group,kind:format,referer:location.href,title:document.title,...extra};
   const stamp=JSON.stringify(message);if(sent.get(group)===stamp||sent.size>=80&&!sent.has(group))return;
   sent.set(group,stamp);post(message);
  }catch(e){}
 }
 async function inspect(response){
  const type=response.headers.get('content-type')||'';
  if(!kind(response.url,type,false))return;
  send(response.url,type);
  if(!/mpegurl/.test(type)&&!(/\.m3u8(?:[?#]|$)/i.test(response.url)))return;
  if(Number(response.headers.get('content-length'))>1048576)return;
  try{
   const copy=response.clone(),reader=copy.body.getReader(),chunks=[];let size=0;
   for(;;){const part=await reader.read();if(part.done)break;size+=part.value.byteLength;if(size>1048576){await reader.cancel();return;}chunks.push(part.value);}
   const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
   const choices=variants(new TextDecoder().decode(bytes),response.url);
   if(choices.length){for(const c of choices)parents.set(key(c.url),key(response.url));send(response.url,type,false,{variants:choices});}
  }catch(e){}
 }
 const originalFetch=window.fetch;
 window.fetch=function(){return originalFetch.apply(this,arguments).then(r=>{inspect(r);return r;});};
 const open=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(){this.addEventListener('load',function(){
  send(this.responseURL,this.getResponseHeader('content-type'));
  try{if((!this.responseType||this.responseType==='text')&&this.responseText.length<=1048576){const choices=variants(this.responseText,this.responseURL);if(choices.length){for(const c of choices)parents.set(key(c.url),key(this.responseURL));send(this.responseURL,'application/vnd.apple.mpegurl',false,{variants:choices});}}}catch(e){}
 });return open.apply(this,arguments);};
 setInterval(()=>{pageChanged();document.querySelectorAll('video').forEach(v=>{send(v.currentSrc||v.src,'',true);v.querySelectorAll('source').forEach(s=>send(s.src,s.type,true));});},1000);
 pageChanged();
 try{new PerformanceObserver(l=>l.getEntries().forEach(e=>send(e.name,''))).observe({entryTypes:['resource']});}catch(e){}
})(typeof window==='undefined'?globalThis:window);
