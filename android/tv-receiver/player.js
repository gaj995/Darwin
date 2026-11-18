(function () {
  'use strict';
  if (location.protocol !== 'file:') throw new Error('Player must run on the Darwin home screen');
  if (window.__darwinPlayer) { window.__darwinPlayer.stop(); return; }
  var pc = null, video = null, root = null, label = null, av = null, mode = '', error = '', phase = 'idle';
  var cancelPlay = null, preparing = false, cachedTracks = [], nativeTime = 0, nativeDuration = 0, nativeState = "NONE";
  var activeCastId='',playTitle='';
  var generation = 0, timer = null, playTimer = null, seekTarget = null, seeking = false;
  function tr(text,vars){return window.DarwinI18n?window.DarwinI18n.t(text,vars):String(text).replace(/\{(\w+)\}/g,function(all,key){return vars&&vars[key]!==undefined?String(vars[key]):all;});}
  var lastMessage=null;
  function renderMessage(){if(label&&lastMessage)label.textContent=lastMessage.literal?lastMessage.text:tr(lastMessage.text,lastMessage.vars);}
  function message(text,vars,literal) {lastMessage={text:text,vars:vars,literal:!!literal};if(label){renderMessage();label.style.opacity='1';}}
  if(window.DarwinI18n)window.DarwinI18n.subscribe(renderMessage);
  function fade() { clearTimeout(timer); timer = setTimeout(function () { if (label) label.style.opacity = '0'; }, 3500); }
  function stop(keepLoading) {
    activeCastId='';playTitle='';
    if(!keepLoading&&window.__darwinLoading)window.__darwinLoading.stop();
    if(cancelPlay){var cancel=cancelPlay;cancelPlay=null;cancel('Playback canceled');}
    preparing=false;cachedTracks=[];nativeTime=0;nativeDuration=0;nativeState="NONE";generation++; seekTarget=null; seeking=false; clearTimeout(timer); clearTimeout(playTimer);
    if (pc) { pc.close(); pc = null; }
    if (video) { video.onended=video.onplaying=video.onwaiting=video.onstalled=video.onseeked=video.onerror=video.onloadedmetadata=null; video.pause(); video.srcObject = null; video.removeAttribute('src'); video.load(); video = null; }
    if (av) { try { av.stop(); } catch (e) {} try { av.close(); } catch (e) {} av = null; }
    if (root) root.remove(); root = label = null; mode = ''; phase = 'idle';
  }
  function stage(name) {
    stop(true);if(window.__darwinLoading){window.__darwinLoading.show('connecting','Connecting…');['source','seeking','buffering'].forEach(function(k){window.__darwinLoading.hide(k);});} if(window.__darwinHideCameo)window.__darwinHideCameo(); mode = name; error = ''; phase = 'connecting';
    Array.prototype.forEach.call(document.querySelectorAll('video'), function (v) { v.pause(); });
    root = document.createElement('div');
    root.style.cssText = 'position:fixed;inset:0;z-index:2147483600;background:#000;color:white;';
    video = document.createElement('video'); video.autoplay = true; video.playsInline = true;
    video.style.cssText = 'position:absolute;width:100%;height:100%;object-fit:contain;background:#000';
    video.onplaying = function () { phase = 'playing';if(window.__darwinLoading){window.__darwinLoading.hide('connecting');window.__darwinLoading.hide('buffering');} message(name === 'cast' ? 'Casting from Brave' : 'Playing'); fade(); };
    video.onwaiting=function(){if(window.__darwinLoading)window.__darwinLoading.show('buffering','Buffering…');};
    video.onstalled=video.onwaiting;
    video.onseeked=function(){if(video.readyState>=3&&window.__darwinLoading)window.__darwinLoading.hide('buffering');};
    video.onerror = function () { if(window.__darwinLoading)window.__darwinLoading.reset();phase = 'error'; error = 'TV could not decode this stream (media error ' + (video.error && video.error.code) + ').'; message('TV could not decode this stream (media error {code}).',{code:video.error&&video.error.code}); };
    label = document.createElement('div');
    label.style.cssText = 'position:absolute;top:40px;left:50%;transform:translateX(-50%);padding:16px 28px;border-radius:14px;background:rgba(12,15,22,.88);font:28px/1.4 sans-serif;max-width:85%;transition:opacity .3s;text-align:center';
    root.appendChild(video); root.appendChild(label); document.body.appendChild(root);
    message('Connecting…');
  }
  function iceReady(peer) {
    return new Promise(function (resolve) {
      if (peer.iceGatheringState === 'complete') return resolve();
      var t = setTimeout(done, 5000);
      function done() { clearTimeout(t); peer.removeEventListener('icegatheringstatechange', check); resolve(); }
      function check() { if (peer.iceGatheringState === 'complete') done(); }
      peer.addEventListener('icegatheringstatechange', check);
    });
  }
  async function receive(offer) {
    if (typeof RTCPeerConnection !== 'function') throw new Error('This TV firmware does not support tab casting');
    stage('cast');
    var mine = generation;
    try {
      pc = new RTCPeerConnection({iceServers: []});
      var peer = pc;
      peer.ontrack = function (e) {
        if (mine !== generation) return;
        if (!video.srcObject) video.srcObject = new MediaStream();
        if (!video.srcObject.getTracks().some(function(t) { return t.id === e.track.id; })) video.srcObject.addTrack(e.track);
        video.play().catch(function (e) { error = String(e); message('Press Play on the remote to start casting'); });
      };
      peer.onconnectionstatechange = function () {
        if (mine !== generation) return;
        if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
          phase = 'disconnected';if(window.__darwinLoading)window.__darwinLoading.reset(); message('Cast disconnected. Stop and cast again from Brave.');
        }
      };
      await peer.setRemoteDescription(offer);
      await peer.setLocalDescription(await peer.createAnswer());
      await iceReady(peer);
      if (mine !== generation) throw new Error('Cast canceled');
      return {type: peer.localDescription.type, sdp: peer.localDescription.sdp};
    } catch (e) { if(window.__darwinLoading)window.__darwinLoading.reset();error = String(e); phase = 'error'; message(error); if (pc) { pc.close(); pc = null; } throw e; }
  }
  function playURL(url, options) {
    options = options || {};
    stage('direct');
    activeCastId=/^[a-f0-9]{32}$/.test(options.castId||'')?options.castId:'';playTitle=String(options.title||'Video').slice(0,300);
    var mine = generation;
    return new Promise(function(resolve, reject) {
      var settled = false, finished = false, startPosition = 0;
      function ended() {
        if (mine !== generation || finished) return;
        finished = true; phase = 'ended';
        if(window.__darwinLoading)window.__darwinLoading.stop();
        message('Finished');
      }
      function ready() {
        if (mine !== generation || settled || finished) return;
        settled = true; preparing=false;cancelPlay=null; clearTimeout(playTimer); phase = nativeState==='PAUSED'?'paused':'playing';
        if(window.__darwinLoading){window.__darwinLoading.hide('connecting');window.__darwinLoading.hide('buffering');}
        message(options.title || 'Playing on TV',null,!!options.title); fade(); resolve(status());
      }
      function fail(e) {
        if (mine !== generation || settled || finished) return;
        settled = true; finished=true; preparing=false;cancelPlay=null; clearTimeout(playTimer); error = String(e); phase = 'error';
        if(window.__darwinLoading)window.__darwinLoading.reset();
        message('Unable to play this video: {error}',{error:tr(error)}); reject(new Error(error));
      }
      cancelPlay=fail;
      playTimer = setTimeout(function(){ fail('The video server or TV did not start playback'); }, 20000);
      if (window.webapis && webapis.avplay) {
        try {
          av = webapis.avplay;
          var object = document.createElement('object'); object.type = 'application/avplayer';
          object.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%';
          root.insertBefore(object, label); video.style.display = 'none'; root.style.background = 'transparent';
          av.open(url);if(options.local){try{av.setBufferingParam('PLAYER_BUFFER_FOR_PLAY','PLAYER_BUFFER_SIZE_IN_SECOND',4);av.setBufferingParam('PLAYER_BUFFER_FOR_RESUME','PLAYER_BUFFER_SIZE_IN_SECOND',4);}catch(e){}} av.setDisplayRect(0, 0, 1920, 1080);
          av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
          av.setListener({
            onbufferingstart: function(){ if(mine!==generation||finished)return;phase='buffering';if(window.__darwinLoading)window.__darwinLoading.show('buffering','Buffering…'); message('Buffering…'); },
            onbufferingprogress: function(n){ if(mine!==generation||finished)return;message('Buffering {percent}%',{percent:n}); },
            onbufferingcomplete: function(){ if(mine!==generation||finished||!settled)return;phase=nativeState==='PAUSED'?'paused':'playing';if(window.__darwinLoading)window.__darwinLoading.hide('buffering'); fade(); },
            oncurrentplaytime: function(t){ if(mine!==generation||finished)return;var previousTime=nativeTime;nativeTime=Math.max(0,Number(t)/1000||0);var advanced=nativeTime>previousTime&&nativeTime>startPosition;if(!settled){if(!advanced)return;ready();}if(advanced&&window.__darwinLoading)window.__darwinLoading.hide('buffering');if(nativeState!=='PAUSED'){nativeState='PLAYING';phase='playing';} },
            onsubtitlechange: function(duration,text){if(mine!==generation||finished)return;if(window.__darwinLocalFile)window.__darwinLocalFile.nativeCaption(text,duration);},
            onstreamcompleted: ended,
            onerror: function(e){ if(mine!==generation||finished)return;if (!settled) fail(e); else {if(window.__darwinLoading)window.__darwinLoading.reset();phase='error';error=String(e);message('Playback error: {error}',{error:tr(error)});} }
          });
          preparing=true;av.prepareAsync(function(){
            if(mine!==generation||finished)return;preparing=false;
            try {
              nativeDuration=av.getDuration()/1000;nativeState='READY';refreshTracks();
              if(options.local){try{av.setSilentSubtitle(true);}catch(e){}}
              if (options.time > 0 && nativeDuration > options.time) {av.seekTo(options.time * 1000);startPosition=options.time;}
              av.play();nativeState='PLAYING';
            } catch(e){fail(e);}
          }, fail);
          return;
        } catch(e) {
          if(av){try{av.close();}catch(_){}av=null;}
          var obj=root.querySelector('object');if(obj)obj.remove();video.style.display='';root.style.background='#000';
        }
      }
      video.onplaying=function(){if(mine!==generation||finished)return;if(window.__darwinLoading){window.__darwinLoading.hide('connecting');window.__darwinLoading.hide('buffering');}phase='playing';ready();};
      video.onerror=function(){fail('Media error '+(video.error && video.error.code));};
      video.onended=ended;
      video.onloadedmetadata=function(){if(options.time>0&&video.duration>options.time)video.currentTime=options.time;};
      video.src=url; video.play().catch(fail);
    });
  }
  function showError(text) { if(window.__darwinLoading)window.__darwinLoading.reset();error=String(text); phase='error'; message(error); clearTimeout(timer); }
  function clock(seconds) {
    seconds=Math.max(0,Math.floor(seconds||0));var h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),s=seconds%60;
    return (h?h+':'+('0'+m).slice(-2):m)+':'+('0'+s).slice(-2);
  }
  function runSeek() {
    var target=seekTarget,mine=generation;seeking=true;if(window.__darwinLoading)window.__darwinLoading.show('seeking','Seeking…');
    try { av.seekTo(target,function(){
      if(mine!==generation)return;seeking=false;
      if(seekTarget!==target)runSeek();else{seekTarget=null;if(window.__darwinLoading)window.__darwinLoading.hide('seeking');fade();}
    },function(){if(mine!==generation)return;seeking=false;seekTarget=null;if(window.__darwinLoading)window.__darwinLoading.hide('seeking');message('This video could not seek to that position.');fade();}); }
    catch(e){seeking=false;seekTarget=null;if(window.__darwinLoading)window.__darwinLoading.hide('seeking');message('Seeking is not available yet.');fade();}
  }
  function jump(seconds) {
    var duration=av?av.getDuration()/1000:video&&video.duration;
    if(!isFinite(duration)||duration<=0){message('This live stream does not support seeking.');fade();return 'Live stream';}
    var current=av?(seekTarget!==null?seekTarget:av.getCurrentTime())/1000:video.currentTime;
    var target=Math.max(0,Math.min(duration-1,current+seconds));
    message('{offset} seconds · {time} / {duration}',{offset:(seconds<0?'−':'+')+Math.abs(seconds),time:clock(target),duration:clock(duration)});
    if(av){seekTarget=Math.round(target*1000);if(!seeking)runSeek();}else{video.currentTime=target;fade();}
    return 'Seeked to '+clock(target);
  }
  function action(a) {
    if (!mode) return '';
    if (a === 'status') return JSON.stringify(status());
    if (a === 'fullscreen') return 'Already fullscreen';
    if (av) {
      try {
        var state = av.getState();
        if (a === 'toggle' || a === 'play' || a === 'pause') {
          var pause=a==='pause'||a==='toggle'&&state==='PLAYING';
          if(pause&&(state==='PLAYING'||state==='PAUSED')){if(state==='PLAYING')av.pause();nativeState='PAUSED';phase='paused';return 'Paused';}
          if(!pause&&(state==='PLAYING'||state==='PAUSED'||state==='READY')){if(state!=='PLAYING')av.play();nativeState='PLAYING';phase='playing';return 'Playing';}
          return 'Player is '+state.toLowerCase();
        }
        if (a === 'back10' || a === 'fwd10') return jump(a==='fwd10'?10:-10);
      } catch (e) { return String(e); }
    }
    if (mode === 'cast' && (a === 'back10' || a === 'fwd10')) return 'Seek in the original Brave tab';
    if (video && (a === 'toggle' || a === 'play' || a === 'pause')) { var pause=a==='pause'||a==='toggle'&&!video.paused; if(!pause){phase='playing';if(video.paused)video.play().catch(function(e){message(String(e));});return 'Playing';}if(!video.paused)video.pause();phase='paused';return 'Paused'; }
    if (video && (a === 'back10' || a === 'fwd10')) return jump(a==='fwd10'?10:-10);
    return '';
  }
  function refreshTracks() { if(av&&!preparing){try{cachedTracks=av.getCurrentStreamInfo();}catch(e){}} }
  function status() {
    var result = {mode:mode,phase:phase,error:error,castId:activeCastId,title:playTitle,connection:pc&&pc.connectionState,
      ice:pc&&pc.iceConnectionState,width:video&&video.videoWidth,height:video&&video.videoHeight,
      time:video&&video.currentTime,duration:video&&isFinite(video.duration)?video.duration:0};
    if(av) {
      result.nativeState=preparing?'IDLE':nativeState;
      result.time=nativeTime;result.duration=nativeDuration;result.tracks=cachedTracks;
      cachedTracks.forEach(function(t){if(t.type==='VIDEO'){try{var info=JSON.parse(t.extra_info||'{}');result.width=info.Width||info.width;result.height=info.Height||info.height;}catch(e){}}});
    }
    return result;
  }
  var rewindKey=412, forwardKey=417;
  try {
    tizen.tvinputdevice.registerKey('MediaRewind');tizen.tvinputdevice.registerKey('MediaFastForward');
    rewindKey=tizen.tvinputdevice.getKey('MediaRewind').code;forwardKey=tizen.tvinputdevice.getKey('MediaFastForward').code;
  } catch(e) {}
  document.addEventListener('keydown', function(e) {
    if (!mode) return;
    if((window.__darwinLocalFile&&window.__darwinLocalFile.menuOpen())||(window.__darwinQuality&&window.__darwinQuality.menuOpen())||(window.__darwinSubtitles&&window.__darwinSubtitles.menuOpen())||(window.__darwinPhone&&window.__darwinPhone.menuOpen()))return;
    if(mode==='direct' && (e.keyCode===37||e.keyCode===39||e.keyCode===rewindKey||e.keyCode===forwardKey)){e.preventDefault();e.stopImmediatePropagation();action(e.keyCode===37||e.keyCode===rewindKey?'back10':'fwd10');return;}
    if(mode==='direct' && e.keyCode===13){e.preventDefault();e.stopImmediatePropagation();message(action('toggle'));fade();return;}
    if (e.keyCode === 10009 || e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); window.__darwinPlayer.stop(); }
    if (e.keyCode === 10252 || e.keyCode === 415 || e.keyCode === 19) { e.preventDefault(); e.stopImmediatePropagation(); action('toggle'); }
  }, true);
  async function stats() {
    var result = status();
    if (pc) {
      result.tracks = [];
      var reports = await pc.getStats();
      reports.forEach(function(r) { if (r.type === 'inbound-rtp') result.tracks.push({kind:r.kind || r.mediaType, packets:r.packetsReceived, bytes:r.bytesReceived, frames:r.framesDecoded, audioEnergy:r.totalAudioEnergy}); });
    }
    return result;
  }
  window.__darwinPlayer = {refreshTracks:refreshTracks,showError: showError, stats: stats, receive: receive, playURL: playURL, stop: stop, action: action, status: status};
})();
