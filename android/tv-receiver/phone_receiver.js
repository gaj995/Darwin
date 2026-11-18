/* Darwin local Android receiver. No hosted service or Mac controller required. */
(function () {
 'use strict';
 function tr(text,vars){return window.DarwinI18n?window.DarwinI18n.t(text,vars):String(text).replace(/\{(\w+)\}/g,function(all,key){return vars&&vars[key]!==undefined?String(vars[key]):all;});}
 if (window.__darwinPhone) return;
 const CHANNEL='io.github.gajdarwin.cast.v1', P=window.DarwinProtocol;
 if (!P || !crypto.subtle) return;
 let secret=localStorage.getItem('darwin.android.key'), socket, retry, title='', active=false, errorTimer=null;
 if(!/^[a-f0-9]{64}$/.test(secret||'')){secret=P.secret();localStorage.setItem('darwin.android.key',secret);}
 let operationGeneration=0,configQueue=Promise.resolve();
 function invalidateOperations(){operationGeneration++;configQueue=Promise.resolve();}
 const clients=new Map();let endedCastId='',endedAt=0,diagnosticId=0,lastPhase='';const diagnostics=[];
 function diagnostic(level,message){message=String(message).replace(/(?:https?|wss?):\/\/[^\s<>"']+/g,'[URL]').replace(/(token|password|authorization|cookie|secret|key)["']?\s*[=:]\s*("[^"]*"|'[^']*'|[^\s,;}]+)/gi,'$1=[redacted]').replace(/\b[a-f0-9]{64}\b/gi,'[redacted]').slice(0,600);diagnostics.push({id:++diagnosticId,level:level,message:message});if(diagnostics.length>200)diagnostics.shift();}
 for(const level of ['info','warn','error']){const old=console[level];console[level]=function(){try{diagnostic(level,Array.from(arguments).map(x=>{try{return typeof x==='string'?x:JSON.stringify(x);}catch(e){return '[object]';}}).join(' '));}catch(e){}return old.apply(console,arguments);};}
 window.addEventListener('error',e=>diagnostic('error',e.message||'TV script error'));

 const stopPlayer=window.__darwinPlayer.stop;window.__darwinPlayer.stop=function(){invalidateOperations();const s=window.__darwinPlayer.status();if(active&&s.phase==='ended'){endedCastId=s.castId||'';endedAt=Date.now();active=false;}return stopPlayer.apply(this,arguments);};
 function volumeState(){
  try{const audio=window.tizen&&tizen.tvaudiocontrol;if(!audio)throw Error('Unavailable');const volume=audio.getVolume(),muted=audio.isMute();if(!Number.isInteger(volume)||volume<0||volume>100||typeof muted!=='boolean')throw Error('Unavailable');return {volumeAvailable:true,volume:volume,muted:muted};}catch(e){return {volumeAvailable:false,volume:null,muted:null};}
 }
 function setVolume(d){
  const fields=['level','muted','delta','mute'].filter(k=>Object.prototype.hasOwnProperty.call(d,k));
  if(fields.length!==1)throw Error('Choose one volume operation');
  const field=fields[0];
  if(field==='level'&&(!Number.isInteger(d.level)||d.level<0||d.level>100)||field==='muted'&&typeof d.muted!=='boolean'||field==='delta'&&d.delta!==1&&d.delta!==-1||field==='mute'&&d.mute!==true)throw Error('Invalid volume operation');
  if(!volumeState().volumeAvailable)throw Error('TV volume control unavailable');
  const audio=tizen.tvaudiocontrol;
  if(field==='level')audio.setVolume(d.level);
  else if(field==='muted')audio.setMute(d.muted);
  else if(field==='mute')audio.setMute(!audio.isMute());
  else audio.setVolume(Math.max(0,Math.min(100,audio.getVolume()+d.delta)));
 }
 function state(){const p=window.__darwinPlayer,s=p?p.status():{phase:'idle'};return Object.assign(s,{title:s.title||title,endedCastId:endedCastId,endedAt:endedAt,receiverVersion:'0.3.2',controllerURL:localStorage.getItem('darwin.local.remote')||'',diagnostics:diagnostics.slice(-12),subtitles:window.__darwinSubtitles&&__darwinSubtitles.state()},volumeState());}
 async function send(to,body){if(socket&&socket.readyState===1)socket.send(JSON.stringify({method:'ms.channel.emit',params:{event:'darwin.tv',data:await P.encrypt(secret,body,CHANNEL),to:to}}));}
 function snapshot(client,error){return send(client.id,{op:'state',challenge:client.challenge,session:client.session,seq:++client.out,state:state(),error:error||''});}
 function home(reason){if(reason==='ended'){endedCastId=window.__darwinPlayer.status().castId||'';endedAt=Date.now();}else{endedCastId='';endedAt=0;}active=false;if(window.__darwinQuality)window.__darwinQuality.setSource('');clearTimeout(errorTimer);errorTimer=null;window.__darwinPlayer.stop();title='';const panel=document.getElementById('darwin-android-panel');if(panel)panel.style.display='none';const cat=document.getElementById('cat');if(cat){cat.volume=.15;cat.play().catch(function(){});}}
 async function command(d,mine){
  const p=window.__darwinPlayer, sub=window.__darwinSubtitles;
  switch(d.op){
   case 'status':break;
   case 'quality_set':await window.__darwinQuality.select(d.quality);break;
   case 'local_quality':await window.__darwinLocalFile.quality(d.quality);break;
   case 'remote_key':{const codes={up:38,down:40,left:37,right:39,ok:13,back:10009};if(!codes[d.key])throw Error('Unknown remote key');document.dispatchEvent(new KeyboardEvent('keydown',{keyCode:codes[d.key],which:codes[d.key],bubbles:true}));break;}
   case 'volume':setVolume(d);break;

   case 'local_play':if(window.__darwinQuality)window.__darwinQuality.setSource('');clearTimeout(errorTimer);errorTimer=null;endedCastId='';d.movie.castId=/^[a-f0-9]{32}$/.test(d.castId||'')?d.castId:'';active=true;window.__darwinPlaybackOwner='standalone';title=String(d.movie.title||'Local movie');await window.__darwinLocalFile.open(d.movie);break;
   case 'local_track':await window.__darwinLocalFile.select(d.kind,d.track);break;
   case 'play':{
    const url=P.mediaURL(d.url);endedCastId='';if(window.__darwinQuality)window.__darwinQuality.setSource(d.qualityUrl||'');clearTimeout(errorTimer);errorTimer=null;active=true;window.__darwinPlaybackOwner='standalone';title=String(d.title||'Video').slice(0,300);
    try{await p.playURL(url,{title:title,castId:d.castId,time:Number.isFinite(d.time)&&d.time>=0?d.time:0});}catch(e){if(mine===operationGeneration){diagnostic('error',e.message||e);home();}throw e;}break;
   }
   case 'stop':home();break;
   case 'seek':if(!Number.isFinite(d.time))throw Error('Invalid seek');p.seekTo(d.time);break;
   case 'action':if(!['toggle','play','pause','back10','fwd10'].includes(d.action))throw Error('Unknown action');p.action(d.action);break;
   case 'subtitle_load':
    if(!Array.isArray(d.cues)||d.cues.length>12000||d.cues.some(c=>!Number.isFinite(c.start)||!Number.isFinite(c.end)||c.start<0||c.end<=c.start||typeof c.text!=='string'||c.text.length>2000))throw Error('Invalid subtitle file');
    if(window.__darwinLocalFile&&window.__darwinLocalFile.state())await window.__darwinLocalFile.select('subtitle',-1);if(mine!==operationGeneration)return;d.cues.sort((a,b)=>a.start-b.start);sub.load({name:String(d.name||'Subtitles').slice(0,200),cues:d.cues});break;
   case 'subtitle_style':sub.style(Object.assign({},sub.state().settings,d.settings));break;
   case 'subtitle_config':sub.configure(d);break;
   default:throw Error('Unsupported command');
  }
 }
 // Serialize source configuration only. Stop/new Play supersede old work;
 // status, volume and navigation must remain responsive during network requests.
 function dispatch(d){
  const startsPlayback=d.op==='play'||d.op==='local_play';
  if(startsPlayback)invalidateOperations();
  const mine=operationGeneration;
  const run=async()=>{if(mine!==operationGeneration)return;try{await command(d,mine);}catch(e){if(mine===operationGeneration)throw e;}};
  if(startsPlayback){const started=run();configQueue=started.catch(()=>{});return started;}
  if(['local_track','local_quality','quality_set','subtitle_load','subtitle_style','subtitle_config'].includes(d.op)){
   const next=configQueue.then(run);configQueue=next.catch(()=>{});return next;
  }
  return run();
 }
 async function message(raw){
  if(typeof raw!=='string'||raw.length>1500000)return;
  let m,d;try{m=JSON.parse(raw);if(m.event!=='darwin.phone')return;d=await P.decrypt(secret,m.data,CHANNEL);}catch(e){return;}
  if(typeof m.from!=='string')return;
  if(d.op==='hello'){
   if(!/^[a-f0-9]{32}$/.test(d.challenge||''))return;
   let c=clients.get(m.from);
   if(!c||c.challenge!==d.challenge){if(clients.size>=8)clients.delete(clients.keys().next().value);c={id:m.from,challenge:d.challenge,session:P.secret(),seq:0,out:0};clients.set(m.from,c);}
   await send(m.from,{op:'welcome',challenge:c.challenge,session:c.session,state:state()});return;
  }
  const c=clients.get(m.from);if(!c||d.session!==c.session||!Number.isSafeInteger(d.seq)||d.seq<=c.seq)return;c.seq=d.seq;
  dispatch(d).then(()=>snapshot(c)).catch(e=>{diagnostic('error',String(e.message||e));return snapshot(c,String(e.message||e).slice(0,300));}); 
 }
 function connect(){
  clearTimeout(retry);clients.clear();
  socket=new WebSocket('ws://127.0.0.1:8001/api/v2/channels/'+CHANNEL+'?name=DarwinTV');
  // Queue decryption to preserve sequence ordering across asynchronous crypto operations.
  let queue=Promise.resolve();socket.onmessage=e=>{queue=queue.then(()=>message(e.data)).catch(()=>{});};
  socket.onclose=()=>{clients.clear();retry=setTimeout(connect,3000);};socket.onerror=()=>{};
 }
 function pairingLink(){let ip='';try{ip=webapis.network.getIp();}catch(e){}if(!/^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.test(ip))throw Error('TV Wi-Fi address unavailable');return 'darwincast://pair?ip='+encodeURIComponent(ip)+'&key='+secret;}
 let qrMode='pair';
 function showQR(mode){qrMode=mode;
  let panel=document.getElementById('darwin-android-panel');if(!panel){panel=document.createElement('section');panel.id='darwin-android-panel';panel.style.cssText='position:fixed;z-index:2147483645;inset:10% 20%;background:#132129;border:2px solid #bbf2cd;border-radius:28px;color:#f0f5f3;padding:48px;text-align:center;font:28px Arial';document.body.appendChild(panel);}
  const download=window.DARWIN_ANDROID_DOWNLOAD_URL||'';
  if(mode==='download'&&!download){panel.innerHTML='<h2>'+tr('Darwin for Android')+'</h2><p>'+tr('The preview APK is ready on your Mac.')+'</p><p>'+tr('Install Darwin on your phone, then press Right to pair it.')+'</p><p style="font-size:22px;color:#a9b9c0">'+tr('Public downloads will appear here after the first release.')+'</p>';panel.style.display='block';return;}
  try{const url=mode==='download'?download:pairingLink(),qr=qrcode(0,'M');qr.addData(url);qr.make();panel.innerHTML='<h2>'+ tr(mode==='download'?'Get Darwin for Android':'Pair your Android phone')+'</h2><div style="width:320px;height:320px;background:white;padding:18px;margin:24px auto;border-radius:16px">'+qr.createSvgTag({cellSize:5,margin:4,scalable:true})+'</div><p>'+tr(mode==='download'?'Scan with your phone camera to download the app.':'In the Darwin Android app, tap Connect TV → Scan TV QR.')+'</p><p style="font-size:22px;color:#a9b9c0">'+tr('Left: download app · Right: pair phone · Back: close')+'</p>';panel.querySelector('svg').style.cssText='width:100%;height:100%';panel.style.display='block';}catch(e){panel.textContent=tr(e.message);}
 }
 if(window.DarwinI18n)window.DarwinI18n.subscribe(function(){const b=document.getElementById('darwin-android-button');if(b)b.textContent=tr('Android app · press Yellow');const panel=document.getElementById('darwin-android-panel');if(panel&&panel.style.display!=='none')showQR(qrMode);});
 function addButton(){if(document.getElementById('darwin-android-button'))return;const b=document.createElement('button');b.id='darwin-android-button';b.textContent=tr('Android app · press Yellow');b.style.cssText='position:fixed;right:50px;top:35px;background:#263d35;color:#bbf2cd;border:1px solid #bbf2cd;padding:15px 25px;border-radius:16px;font:22px Arial;z-index:10';b.onclick=()=>showQR('pair');document.body.appendChild(b);}
 document.addEventListener('keydown',e=>{const panel=document.getElementById('darwin-android-panel');if(panel&&panel.style.display!=='none'){if([10009,27].includes(e.keyCode))panel.style.display='none';else if(e.keyCode===37)showQR('download');else if(e.keyCode===39)showQR('pair');e.preventDefault();e.stopImmediatePropagation();return;}if(e.keyCode===405){showQR('pair');e.preventDefault();e.stopImmediatePropagation();}},true);
 try{tizen.tvinputdevice.registerKey('ColorF2Yellow');}catch(e){}
 // TV remote subtitle appearance/timing stays local while Android owns playback.
 const oldCommand=window.__darwinLocal&&window.__darwinLocal.command;
 if(window.__darwinLocal)window.__darwinLocal.command=async function(d){
  if(!active&&oldCommand)return oldCommand(d);
  const s=window.__darwinSubtitles;
  if(d.op==='subtitle_state')return {ok:true,result:Object.assign(s.state(),{videoTitle:title})};
  if(d.op==='subtitle_style')s.style(Object.assign({},s.state().settings,d.settings));
  else if(d.op==='subtitle_timing')s.configure(d);
  else if(d.op==='subtitle_clear')s.clear();
  else throw Error('Choose an SRT or VTT file in the Android app. Online search still needs the Mac controller.');
  return {ok:true,result:s.state()};
 };
 setInterval(()=>{const s=state();if(s.phase!==lastPhase){diagnostic(s.phase==='error'?'error':'info','Playback '+s.phase+(s.error?': '+s.error:''));lastPhase=s.phase;}if(!active)return;if(s.phase==='error'&&!errorTimer)errorTimer=setTimeout(home,3000);if(s.phase==='ended')home('ended');},1000);
 window.__darwinPhone={version:'0.3.2',menuOpen:function(){const panel=document.getElementById('darwin-android-panel');return !!panel&&panel.style.display!=='none';},state:state,showQR:showQR,pairingLink:pairingLink,resetPairing:function(){secret=P.secret();localStorage.setItem('darwin.android.key',secret);clients.clear();}};
 addButton();connect();
})();
