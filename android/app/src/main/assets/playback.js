/* Browse is silent until the user explicitly presses a player Play control. */
(function(){
 'use strict';if(window.__darwinBrowsePolicy)return;window.__darwinBrowsePolicy=true;
 let permitted=false;
 function pause(){permitted=false;document.querySelectorAll('video,audio').forEach(v=>{v.autoplay=false;v.removeAttribute('autoplay');v.pause();});for(let i=0;i<window.frames.length;i++)window.frames[i].postMessage({darwinBrowsePlayback:'pause'},'*');}
 window.__darwinPauseBrowse=pause;
 window.addEventListener('message',e=>{if(e.data&&e.data.darwinBrowsePlayback==='pause')pause();});
 const play=HTMLMediaElement.prototype.play;
 HTMLMediaElement.prototype.play=function(){if(!permitted)return Promise.reject(new DOMException('Press Play to watch this video in Browse.','NotAllowedError'));return play.apply(this,arguments);};
 function userPlay(e){
  if(!e.isTrusted)return;
  const target=e.target;if(!target||!target.closest)return;
  const control=target.closest('video,audio,button,[role="button"],[class*="play-button"],.ytp-cued-thumbnail-overlay,[data-plyr="play"]');
  if(!control)return;
  const label=(control.getAttribute('aria-label')||control.getAttribute('title')||control.textContent||'').trim();
  if(control.matches('video,audio,[class*="play-button"],.ytp-cued-thumbnail-overlay,[data-plyr="play"]')||/^(play|resume|reproducir|reanudar)\b/i.test(label))permitted=true;
 }
 document.addEventListener('pointerdown',userPlay,true);document.addEventListener('click',userPlay,true);
 document.addEventListener('keydown',e=>{if(e.isTrusted&&[' ','k','K','Enter'].includes(e.key)&&!e.target.closest('input,textarea,[contenteditable="true"]'))permitted=true;},true);
 document.addEventListener('play',e=>{if(!permitted&&e.target.pause)e.target.pause();},true);
 addEventListener('yt-navigate-start',pause);addEventListener('pagehide',pause);
 new MutationObserver(()=>document.querySelectorAll('video[autoplay],audio[autoplay]').forEach(v=>{v.autoplay=false;v.removeAttribute('autoplay');})).observe(document,{childList:true,subtree:true});
})();
