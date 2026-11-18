/* Local movies: full timeline, embedded track menu, and source-side compatibility. */
(function(){
 'use strict';if(window.__darwinLocalFile)return;

 function tr(key,values){if(window.DarwinI18n)return window.DarwinI18n.t(key,values);return String(key).replace(/\{(\w+)\}/g,function(_,name){return values&&values[name]!==undefined?values[name]:'{'+name+'}';});}
 let source=null,serial=0,busy=false,menu=null,selected=0,notice='',caption=null,nativeText='',nativeUntil=0,captionWindow=-1,cues=[],fetching=false;
 const p=window.__darwinPlayer,play=p.playURL.bind(p),stop=p.stop.bind(p),status=p.status.bind(p),seek=p.seekTo.bind(p),action=p.action.bind(p);
 let oldMode='',captionGeneration=0,seekQueued=null,seekTimer=null;const captionCache=new Map(),captionRequests=new Set(),pendingRequests=new Set();
 function privateURL(raw){const u=new URL(raw);if(u.protocol!=='http:'||u.username||u.password||!(/^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.test(u.hostname)))throw Error('Local video source must be on your Wi-Fi');return u.href;}
 function valid(d){if(!d||!d.local||!Array.isArray(d.local.tracks)||d.local.tracks.length>200)throw Error('Invalid local movie');privateURL(d.local.base);privateURL(d.url);if(!Number.isFinite(d.local.duration)||d.local.duration<=0)throw Error('Unknown movie duration');return d;}
 function resetCaptions(){captionGeneration++;captionCache.clear();captionRequests.clear();nativeText='';nativeUntil=0;captionWindow=-1;cues=[];fetching=false;if(caption)caption.style.display='none';}
 function cancelRequests(){pendingRequests.forEach(a=>a.abort());pendingRequests.clear();}
 function check(mine){if(mine!==serial)throw Error('Movie changed');}
 function clear(){cancelRequests();clearTimeout(seekTimer);seekQueued=null;serial++;source=null;busy=false;resetCaptions();if(menu)menu.style.display='none';}
 function closeSource(s){if(s)fetch(s.local.base+'/configure',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({op:'close'})}).catch(()=>{});}
 p.stop=function(){const old=source;clear();stop();closeSource(old);};
 p.playURL=function(url,opts){const old=source;clear();closeSource(old);return play(url,opts);};
 p.status=function(){const s=status();if(source){s.local=source.local;s.title=source.title;s.duration=source.local.duration;s.time=(Number(s.time)||0)+(source.local.mode==='original'?0:source.local.start);s.live=false;s.seekStart=0;s.seekEnd=s.duration;s.seekable=['playing','paused','buffering'].includes(s.phase);s.seeking=s.seeking||busy||seekQueued!==null;if(seekQueued!==null)s.seekTarget=seekQueued;s.localNotice=notice;}return s;};
 async function configure(values){
  if(!source)throw Error('No local movie');const mine=serial,s=source,abort=typeof AbortController==='function'?new AbortController():null;if(abort)pendingRequests.add(abort);
  try{const response=await fetch(s.local.base+'/configure',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values),...(abort?{signal:abort.signal}:{})});
   check(mine);if(!response.ok)throw Error((await response.text()).slice(0,200));const next=valid(await response.json());check(mine);next.castId=s.castId||'';return next;
  }finally{if(abort)pendingRequests.delete(abort);}
 }
 async function measureConnection(d){
  let cached;try{cached=JSON.parse(localStorage.getItem('darwin.localBandwidth.'+new URL(d.local.base).hostname)||'null');}catch(e){}
  if(cached&&Date.now()-cached.at<180000&&cached.mbps>0)return cached.mbps;
  const began=performance.now(),abort=new AbortController(),timer=setTimeout(()=>abort.abort(),5000);pendingRequests.add(abort);let bytes=0;
  try{const response=await fetch(d.local.base+'/probe.bin',{signal:abort.signal});if(!response.ok)throw Error('Connection check unavailable');
   if(response.body&&response.body.getReader){const reader=response.body.getReader();for(;;){const part=await reader.read();if(part.done)break;bytes+=part.value.byteLength;if(bytes>=524288&&performance.now()-began>=350){await reader.cancel();break;}}}
   else bytes=(await response.arrayBuffer()).byteLength;
   const mbps=Math.max(.1,Math.min(10000,bytes*8/Math.max(1,performance.now()-began)/1000));try{localStorage.setItem('darwin.localBandwidth.'+new URL(d.local.base).hostname,JSON.stringify({mbps:mbps,at:Date.now()}));}catch(e){}return mbps;
  }finally{clearTimeout(timer);pendingRequests.delete(abort);}
 }
 async function open(d){
  valid(d);cancelRequests();const continuing=!!source&&source.local.base===d.local.base,mine=++serial;source=d;busy=true;notice='';resetCaptions();if(window.__darwinLoading)window.__darwinLoading.show('source',continuing?'Seeking…':'Connecting…');
  try{
   if(d.local.automatic&&!d.local.networkMbps){notice='Checking the TV connection…';try{const mbps=await measureConnection(d);check(mine);d=await configure({networkMbps:mbps,automatic:true,time:d.local.start});check(mine);source=d;notice='';}catch(e){if(mine!==serial)return;notice='Connection check unavailable; choose 720p if playback buffers.';}}
   if(mine!==serial)return;
   await play(d.url,{local:true,preserveSubtitles:continuing,title:d.title,castId:d.castId||'',time:d.local.mode==='original'?d.local.start:0});
   if(mine!==serial)return;
   const selectedAudio=await nativeSelection('audio',d.local.audio,false);if(mine!==serial)return;
   if(!selectedAudio&&d.local.mode==='original'){try{const current=(webapis.avplay.getCurrentStreamInfo()||[]).find(t=>t.type==='AUDIO'),match=current&&audioMap().find(t=>t.native.index===current.index);if(match){const next=await configure({audio:match.source.id,metadataOnly:true});check(mine);source=next;}}catch(e){if(mine!==serial)return;notice='Playing with the TV-selected audio track.';}}
  }catch(e){
   if(mine!==serial)return;
   if(d.local.mode==='original')return await open(await configure({mode:'compatible',automatic:false,time:d.local.start,audio:d.local.audio,subtitle:d.local.subtitle}));
   if(d.local.mode==='compatible')return await open(await configure({mode:'encode',automatic:false,time:d.local.start}));
   notice='The file could not play in compatibility mode. Try a lower-bitrate file or another source device.';p.stop();throw Error(notice);
  }finally{if(mine===serial){busy=false;if(window.__darwinLoading)window.__darwinLoading.hide('source');}}
  return mine;
 }
 function flushSeek(){clearTimeout(seekTimer);if(!source||seekQueued===null)return;if(busy){seekTimer=setTimeout(flushSeek,100);return;}const target=seekQueued;seekQueued=null;restart({time:target}).catch(()=>{}).finally(()=>{if(seekQueued!==null)seekTimer=setTimeout(flushSeek,180);});}
 p.seekTo=function(time){if(!source||source.local.mode==='original')return seek(time);if(!Number.isFinite(time))return 'Invalid position';seekQueued=Math.max(0,Math.min(source.local.duration-.1,time));clearTimeout(seekTimer);seekTimer=setTimeout(flushSeek,180);return 'Seeking…';};
 p.action=function(a){if(source&&source.local.mode!=='original'&&(a==='back10'||a==='fwd10'||a.startsWith('seek:'))){return p.seekTo(a.startsWith('seek:')?Number(a.slice(5)):(seekQueued!==null?seekQueued:p.status().time)+(a==='back10'?-10:10));}return action(a);};
 async function restart(values){
  if(busy)throw Error('Playback is still changing. Try again in a moment.');let mine=serial;const wasPaused=p.status().phase==='paused';busy=true;
  if(window.__darwinLoading)window.__darwinLoading.show('source',Object.prototype.hasOwnProperty.call(values,'time')?'Seeking…':tr('Loading video…'));
  try{const next=await configure(Object.assign({time:p.status().time},values));check(mine);const opening=open(next);mine=serial;const finished=await opening;if(finished!==undefined)mine=finished;check(mine);if(wasPaused&&source)action('pause');}
  catch(e){if(mine===serial)notice=e.message;throw e;}finally{if(mine===serial){busy=false;if(window.__darwinLoading)window.__darwinLoading.hide('source');draw();}}
 }
 function audioMap(){
  const sourceTracks=source.local.tracks.filter(t=>t.type==='audio'),nativeTracks=webapis.avplay.getTotalTrackInfo().filter(t=>t.type==='AUDIO'),used=new Set();
  function codec(value){const v=String(value||'').toLowerCase();if(/e-?ac-?3|ec-3/.test(v))return 'eac3';if(/ac-?3/.test(v))return 'ac3';if(/truehd|mlp/.test(v))return 'truehd';if(/aac/.test(v))return 'aac';if(/dts/.test(v))return 'dts';if(/mp3|mpeg/.test(v))return 'mp3';if(/flac/.test(v))return 'flac';return v;}
  return nativeTracks.flatMap((n,i)=>{let meta={};try{meta=JSON.parse(n.extra_info||'{}');}catch(e){}const c=codec(meta.fourCC),lang=meta.language;
   let candidates=sourceTracks.filter(t=>!used.has(t.id)&&(!c||codec(t.codec)===c)&&(!lang||!t.language||t.language===lang||t.language==='und')&&(!meta.channels||!t.channels||Number(meta.channels)===t.channels));
   let selected=candidates.find(t=>meta.bit_rate&&t.bitrate===Number(meta.bit_rate))||candidates[0];
   if(!c&&nativeTracks.length!==sourceTracks.length)selected=null;
   if(!selected)return [];used.add(selected.id);return [{native:n,source:selected}];
  });
 }
 // Re-selecting an already active audio track can unnecessarily restart the
 // TV decoder. Unknown readback still takes the normal selection path.
 function audioAlreadySelected(index){
  try{return (webapis.avplay.getCurrentStreamInfo()||[]).some(t=>t.type==='AUDIO'&&t.index===index);}catch(e){return false;}
 }
 // Samsung permits AUDIO changes only in PLAYING. Keep a paused movie paused
 // after the synchronous selection, including when selection throws.
 function selectAudioTrack(index){
  if(audioAlreadySelected(index))return;
  const paused=p.status().phase==='paused';
  if(paused)action('play');
  try{webapis.avplay.setSelectTrack('AUDIO',index);}finally{if(paused)action('pause');}
 }
 async function nativeSelection(kind,id,fallback){
  if(!source)return false;
  if(id===-1&&kind==='subtitle'){try{webapis.avplay.setSilentSubtitle(true);}catch(e){}source.local.subtitle=-1;resetCaptions();return true;}
  // File indices differ from Samsung's filtered track list. Text captions are
  // read by their original file index; unsupported audio is converted on source.
  if(kind==='subtitle')return false;
  try{
   if(source.local.mode!=='original'){const ids=source.local.streamAudio||[],tracks=webapis.avplay.getTotalTrackInfo().filter(t=>t.type==='AUDIO'),i=ids.indexOf(id);if(i<0||!tracks[i]||ids.length!==tracks.length)return false;selectAudioTrack(tracks[i].index);if(p.refreshTracks)p.refreshTracks();source.local.audio=id;return true;}
   const match=audioMap().find(t=>t.source.id===id);if(!match)return false;
   selectAudioTrack(match.native.index);if(p.refreshTracks)p.refreshTracks();source.local.audio=id;return true;
  }catch(e){}
  return false;
 }
 async function choose(kind,id){
  if(!source)throw Error('No local movie');const mine=serial,previousPhase=p.status().phase;if(!['audio','subtitle'].includes(kind)||!Number.isInteger(id))throw Error('Invalid track');
  if(kind==='subtitle'&&id>=0&&window.__darwinSubtitles&&window.__darwinSubtitles.clear)window.__darwinSubtitles.clear();
  const selectedNative=await nativeSelection(kind,id,true);check(mine);
  if(selectedNative){if(kind==='audio'&&['paused','playing'].includes(previousPhase))action(previousPhase==='paused'?'pause':'play');const native=source.local.nativeSubtitle,next=await configure({[kind]:id,metadataOnly:true});check(mine);source=next;source.local.nativeSubtitle=native;draw();return;}
  const track=source.local.tracks.find(t=>t.id===id&&t.type===kind);
  if(kind==='subtitle'&&(id===-1||track&&(track.text||track.ocr))){
   try{webapis.avplay.setSilentSubtitle(true);}catch(e){}
   const next=await configure({time:p.status().time,subtitle:id,mode:source.local.mode,metadataOnly:true});check(mine);source=next;resetCaptions();source.local.nativeSubtitle=false;draw();return;
  }
  await restart(Object.assign({mode:'compatible',automatic:false},{[kind]:id}));
 }
 function label(t){let lang=t.language||'und';try{if(lang!=='und')lang=new Intl.DisplayNames([window.DarwinI18n?window.DarwinI18n.language():'en'],{type:'language'}).of(lang);}catch(e){}return (t.title||lang==='und'?(t.title||tr('Track {id}',{id:t.id})):lang)+' · '+t.codec;}
 function quality(q){if(!Number.isInteger(q)||q< -1||q>4320)throw Error('Invalid quality');return restart({quality:q,automatic:q===0&&!source.local.remote,mode:q===-1&&!source.local.remote?'original':'compatible'});}
 function rows(){if(!source)return [];return [{label:tr('Audio'),heading:true},...source.local.tracks.filter(t=>t.type==='audio').map(t=>({label:label(t)+(source.local.audio===t.id?' ✓':''),run:()=>choose('audio',t.id)})),{label:tr('Embedded subtitles'),heading:true},{label:tr('Off')+(source.local.subtitle===-1?' ✓':''),run:()=>choose('subtitle',-1)},...source.local.tracks.filter(t=>t.type==='subtitle').map(t=>({label:label(t)+(source.local.subtitle===t.id?' ✓':''),run:()=>choose('subtitle',t.id)})),{label:tr('Quality'),heading:true},...((source.local.remote?[0].concat(source.local.qualities||[]):[0,-1,2160,1080,720,480].filter(q=>q<=0||q<=source.local.video.height)).map(q=>({label:(q===0?(source.local.remote?tr('Auto · up to 1080p'):tr('Auto · fit this connection')):q===-1?tr('Original quality'):q+'p')+((source.local.automatic?0:source.local.quality||0)===q?' ✓':''),run:()=>quality(q)}))),{label:tr('Playback compatibility'),heading:true},...['original','compatible','encode'].map((m,i)=>({label:[tr('Original quality'),tr('Repackage / compatible audio'),tr('Convert video · up to 1080p')][i]+(source.local.mode===m?' ✓':''),run:()=>restart({mode:m})})),{label:tr('Close'),run:()=>{menu.style.display='none';}}];}
 function draw(){if(!menu||menu.style.display==='none')return;const all=rows();menu.textContent='';const title=document.createElement('h2');title.textContent=tr('Audio, subtitles & quality');title.style.margin='0 0 12px';menu.appendChild(title);all.forEach((r,i)=>{const b=document.createElement(r.heading?'h3':'button');b.textContent=r.label;b.style.cssText=r.heading?'font-size:19px;color:#a9b9c0;margin:16px 0 6px':'display:block;width:100%;text-align:left;background:'+(selected===i?'#304b3e':'transparent')+';color:#fff;border:2px solid '+(selected===i?'#bbf2cd':'transparent')+';border-radius:10px;font:24px Arial;padding:10px;margin:3px 0';if(!r.heading)b.onclick=()=>{selected=i;Promise.resolve(r.run()).catch(e=>{notice=e.message;draw();});};menu.appendChild(b);if(selected===i&&!r.heading)b.scrollIntoView({block:'nearest'});});if(notice){const n=document.createElement('p');n.textContent=tr(notice);n.style.cssText='font-size:18px;color:#f3c98b';menu.appendChild(n);}}
 if(window.DarwinI18n)window.DarwinI18n.subscribe(draw);
 function showMenu(){if(!source)return false;if(!menu){menu=document.createElement('section');menu.style.cssText='position:fixed;right:50px;top:45px;bottom:45px;width:660px;overflow-y:auto;z-index:2147483647;background:rgba(16,26,32,.97);border:1px solid #587468;border-radius:22px;padding:28px;color:#fff;font:24px Arial';document.body.appendChild(menu);}menu.style.display='block';selected=1;draw();return true;}
 document.addEventListener('keydown',e=>{if(menu&&menu.style.display!=='none'){const all=rows();if(e.keyCode===10009||e.key==='Escape')menu.style.display='none';else if(e.keyCode===38||e.keyCode===40){const step=e.keyCode===38?-1:1;do{selected=(selected+step+all.length)%all.length;}while(all[selected].heading);draw();}else if(e.keyCode===13)Promise.resolve(all[selected].run()).catch(er=>{notice=er.message;draw();});e.preventDefault();e.stopImmediatePropagation();return;}if(source&&(e.keyCode===38||e.keyCode===403)){showMenu();e.preventDefault();e.stopImmediatePropagation();}},true);
 try{tizen.tvinputdevice.registerKey('ColorF0Red');}catch(e){}
 function seconds(s){const p=s.replace(',','.').split(':').map(Number);return p.reduce((a,b)=>a*60+b,0);}
 function parse(text,start){return text.replace(/\r/g,'').split(/\n\s*\n/).flatMap(b=>{const lines=b.split('\n'),i=lines.findIndex(l=>l.includes('-->'));if(i<0)return [];const t=lines[i].split('-->');return [{start:start+seconds(t[0].trim()),end:start+seconds(t[1].trim().split(' ')[0]),text:lines.slice(i+1).join('\n').replace(/<[^>]*>/g,'')}];});}
 setInterval(async function(){
  if(!source)return;const tick=serial,s=p.status();if(s.phase==='ended'&&!busy){p.stop();return;}
  if(s.phase==='error'&&!busy){if(source.local.mode!=='encode')restart({mode:source.local.mode==='original'?'compatible':'encode'}).catch(()=>{});else{notice='Playback failed. Returning to Darwin.';p.stop();}return;}
  const subtitleSettings=window.__darwinSubtitles.state(),captionTime=(s.time-(subtitleSettings.offset||0))*(subtitleSettings.rate||1);
  if(source.local.nativeSubtitle&&(subtitleSettings.offset||subtitleSettings.rate!==1))source.local.nativeSubtitle=false;
  const id=source.local.subtitle,track=source.local.tracks.find(t=>t.type==='subtitle'&&t.id===id);
  let text='';
  if(track){
   if(source.local.nativeSubtitle){if(Date.now()<nativeUntil)text=nativeText;}
   else if(track.text||track.ocr){
    const bucket=Math.floor(Math.max(0,captionTime)/30)*30,generation=captionGeneration;
    function requestWindow(at){if(captionCache.has(at)||captionRequests.has(at))return;captionRequests.add(at);if(!captionCache.size)notice=track.ocr?'Recognizing picture subtitles locally…':'Loading subtitles…';
     fetch(source.local.base+'/captions/'+id+'/'+at+'.json').then(async r=>{if(!r.ok)throw Error('Could not read embedded subtitles');const data=await r.json();if(tick!==serial||generation!==captionGeneration)return;captionCache.set(at,parse(data.vtt,data.start));while(captionCache.size>6)captionCache.delete(captionCache.keys().next().value);notice=data.recognized&&data.confidence<.65?tr('Picture subtitles recognized; some words may need correction.'):'';}).catch(e=>{if(generation===captionGeneration)notice=e.message;}).finally(()=>{if(generation===captionGeneration)captionRequests.delete(at);});
    }
    requestWindow(bucket);if(captionTime-bucket>20)requestWindow(bucket+30);
    if(tick!==serial||!source)return;
    // Extraction windows overlap. Use one window, otherwise OCR punctuation
    // differences can display the same caption twice at their boundary.
    const activeWindow=captionCache.has(bucket)?bucket:Array.from(captionCache.keys()).filter(at=>at<bucket).sort((a,b)=>b-a)[0];
    cues=captionCache.get(activeWindow)||[];
    text=cues.filter(c=>c.start<=captionTime&&c.end>captionTime).map(c=>c.text).filter((text,i,all)=>all.indexOf(text)===i).join('\n');
   }
  }
  if(!caption){caption=document.createElement('div');caption.id='darwin-local-subtitles';caption.style.cssText='position:fixed;left:8%;right:8%;bottom:48px;z-index:2147483630;text-align:center;white-space:pre-line;pointer-events:none;color:#fff;text-shadow:0 2px 4px #000';document.body.appendChild(caption);}
  const st=window.__darwinSubtitles.state(),style=st.settings;caption.textContent=text;caption.style.display=text&&st.enabled?'block':'none';caption.style.fontFamily={sans:'Arial, sans-serif',serif:'Georgia, serif',mono:'monospace'}[style.font]||'Arial';caption.style.fontSize=style.size+'px';caption.style.background=style.background?'rgba(0,0,0,.5)':'transparent';
 },500);
 window.__darwinLocalFile={open:open,showMenu:showMenu,menuOpen:()=>!!menu&&menu.style.display!=='none',select:choose,quality:quality,nativeCaption:function(text,duration){nativeText=String(text||'').replace(/<[^>]*>/g,'');nativeUntil=Date.now()+Number(duration||2000);},state:()=>source&&source.local};
})();
