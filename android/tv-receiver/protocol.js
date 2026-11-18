(function(root){
 'use strict';
 function hex(bytes){return Array.prototype.map.call(bytes,function(n){return n.toString(16).padStart(2,'0');}).join('');}
 function bytes(hexValue){return new Uint8Array(hexValue.match(/../g).map(function(v){return parseInt(v,16);}));}
 function base64(b){return btoa(String.fromCharCode.apply(null,new Uint8Array(b))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');}
 function unbase64(s){s=s.replace(/-/g,'+').replace(/_/g,'/');return Uint8Array.from(atob(s),function(c){return c.charCodeAt(0);});}
 async function key(secret){if(!/^[0-9a-f]{64}$/.test(secret))throw Error('Invalid pairing key');return crypto.subtle.importKey('raw',bytes(secret),'AES-GCM',false,['encrypt','decrypt']);}
 var api={
  secret:function(){return hex(crypto.getRandomValues(new Uint8Array(32)));},
  id:function(){var b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;var s=hex(b);return s.slice(0,8)+'-'+s.slice(8,12)+'-'+s.slice(12,16)+'-'+s.slice(16,20)+'-'+s.slice(20);},
  hash:async function(value){return hex(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))));},
  encrypt:async function(secret,data,aad){var iv=crypto.getRandomValues(new Uint8Array(12));var encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:new TextEncoder().encode(aad)},await key(secret),new TextEncoder().encode(JSON.stringify(data)));return base64(iv)+'.'+base64(encrypted);},
  decrypt:async function(secret,payload,aad){var parts=payload.split('.');if(parts.length!==2)throw Error('Invalid message');var raw=await crypto.subtle.decrypt({name:'AES-GCM',iv:unbase64(parts[0]),additionalData:new TextEncoder().encode(aad)},await key(secret),unbase64(parts[1]));return JSON.parse(new TextDecoder().decode(raw));},
  mediaURL:function(value){var u;try{u=new URL(String(value).trim());}catch(e){throw Error('Paste a complete video URL beginning with https://.');}if(!/^https?:$/.test(u.protocol)||u.username||u.password)throw Error('Use an HTTP or HTTPS video URL without a username or password.');if(u.href.length>8192)throw Error('This video URL is too long.');return u.href;},
  clock:function(n){n=Math.max(0,Math.floor(Number(n)||0));var h=Math.floor(n/3600),m=Math.floor(n%3600/60),s=n%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s).padStart(2,'0');}
 };root.DarwinProtocol=api;
})(globalThis);
