/* Source-provided stream qualities; available from TV ↑ / Red and app controls. */
(function(){
 'use strict';if(window.__darwinQuality)return;

 function tr(key,values){if(window.DarwinI18n)return window.DarwinI18n.t(key,values);return String(key).replace(/\{(\w+)\}/g,function(_,name){return values&&values[name]!==undefined?values[name]:'{'+name+'}';});}
 let provider='',info={qualities:[],quality:0},menu=null,index=0,error='',pending=false,generation=0,infoRevision=0;const requests=new Set();
 const p=window.__darwinPlayer,oldStatus=p.status.bind(p),oldStop=p.stop.bind(p);
 p.stop=function(){invalidate();if(menu)menu.style.display='none';return oldStop();};
 p.status=function(){return Object.assign(oldStatus(),{qualities:info.qualities||[],qualityMode:info.quality||0});};
 function valid(url){const u=new URL(url);if(u.protocol!=='http:'||!(/^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.test(u.hostname)))throw Error('Invalid phone quality endpoint');return u.href;}
 function invalidate(){generation++;requests.forEach(a=>a.abort());requests.clear();pending=false;if(window.__darwinLoading)window.__darwinLoading.hide('source');}
 function check(mine){if(mine!==generation)throw Error('Video changed');}
 async function request(url,options){const abort=typeof AbortController==='function'?new AbortController():null;if(abort)requests.add(abort);try{const response=await fetch(url,Object.assign({},options,abort?{signal:abort.signal}:{}));if(!response.ok)throw Error('Phone stream unavailable');return await response.json();}finally{if(abort)requests.delete(abort);}}
 async function state(){const mine=generation,revision=infoRevision,endpoint=provider;const next=endpoint?await request(endpoint): (await window.__darwinLocal.command({op:'quality_state'})).result;check(mine);if(revision===infoRevision)info=next;return info;}
 async function select(q){
  if(!Number.isInteger(q)||q<0||q>4320)throw Error('Invalid quality');
  if(window.__darwinLocalFile&&window.__darwinLocalFile.state())return window.__darwinLocalFile.quality(q);
  if(pending)throw Error('Quality is still changing. Try again in a moment.');
  const mine=generation,endpoint=provider;infoRevision++;
  pending=true;if(window.__darwinLoading)window.__darwinLoading.show('source',tr('Loading video…'));draw();
  try{
   if(endpoint){const s=p.status(),next=await request(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({quality:q})});check(mine);info=next;await p.playURL(info.url,{title:s.title||tr('Video'),castId:s.castId||'',time:s.live?0:s.time});check(mine);if(s.phase==='paused')p.action('pause');}
   else{await window.__darwinLocal.command({op:'quality_set',quality:q});check(mine);}
   await state();check(mine);error='';
  }catch(e){if(mine===generation)error=e.message;throw e;}finally{if(mine===generation){pending=false;if(window.__darwinLoading)window.__darwinLoading.hide('source');draw();}}
 }
 function draw(){if(!menu||menu.style.display==='none')return;menu.textContent='';const title=document.createElement('h2');title.textContent=tr('Video quality');menu.appendChild(title);const qualities=[0].concat(info.qualities||[]);qualities.forEach((q,i)=>{const b=document.createElement('button');b.textContent=(q?q+'p':tr('Auto'))+(q===(info.quality||0)?' ✓':'');b.style.cssText='display:block;width:100%;text-align:left;font:26px Arial;color:white;background:'+(index===i?'#304b3e':'transparent')+';border:2px solid '+(index===i?'#bbf2cd':'transparent')+';border-radius:12px;padding:14px;margin:6px 0';b.onclick=()=>select(q).catch(()=>{});menu.appendChild(b);});const help=document.createElement('p');help.textContent=pending?tr('Changing quality…'):tr(error)||((info.qualities||[]).length?tr('↑ / ↓ choose · OK apply · Back close'):tr('This source provides one quality.'));help.style.cssText='font-size:21px;color:#a9b9c0';menu.appendChild(help);}
 if(window.DarwinI18n)window.DarwinI18n.subscribe(draw);
 async function show(){if(window.__darwinLocalFile&&window.__darwinLocalFile.state())return window.__darwinLocalFile.showMenu();if(!menu){menu=document.createElement('section');menu.style.cssText='position:fixed;top:80px;right:60px;max-height:840px;overflow:auto;width:560px;background:#101a20f5;color:white;border:1px solid #bbf2cd;border-radius:24px;padding:30px;z-index:2147483647;font:28px Arial';document.body.appendChild(menu);}index=0;menu.style.display='block';draw();try{await state();}catch(e){error=e.message;}draw();}
 document.addEventListener('keydown',e=>{if(menu&&menu.style.display!=='none'){if(e.keyCode===10009||e.key==='Escape')menu.style.display='none';else if(e.keyCode===38||e.keyCode===40){const n=1+(info.qualities||[]).length;index=(index+(e.keyCode===38?-1:1)+n)%n;draw();}else if(e.keyCode===13&&!pending)select([0].concat(info.qualities||[])[index]).catch(()=>{});e.preventDefault();e.stopImmediatePropagation();return;}if((e.keyCode===38||e.keyCode===403)&&p.status().mode==='direct'){show();e.preventDefault();e.stopImmediatePropagation();}},true);
 window.__darwinQuality={show:show,menuOpen:()=>!!menu&&menu.style.display!=='none',select:select,state:state,setSource:function(url){const next=url?valid(url):'';invalidate();provider=next;info={qualities:[],quality:0};if(provider)state().catch(()=>{});}};
})();
