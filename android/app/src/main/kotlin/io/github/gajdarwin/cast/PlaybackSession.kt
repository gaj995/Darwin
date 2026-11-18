package io.github.gajdarwin.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

data class Diagnostic(val time:Long,val source:String,val level:String,val text:String)
internal data class PlaybackOwner(val castId:String,val phase:String,val title:String,val mode:String) {
 val playing get()=phase in listOf("connecting","preparing","loading","playing","paused","buffering")
 fun completed(ours:String,ended:String,accepted:Boolean)=ours.isNotBlank()&&((accepted&&castId==ours&&phase=="ended")||(castId.isBlank()&&phase in listOf("idle","stopped","ended")&&ended==ours))
 fun replacedBy(current:PlaybackOwner,pendingCastId:String):Boolean {
  if(current.castId==pendingCastId)return false
  if(current.castId.isNotBlank())return current.castId!=castId
  if(!current.playing)return false
  return castId.isNotBlank()||!playing||current.title!=title||current.mode!=mode
 }
}
class PlaybackSession(private val context:Context) {
 val catalog=Catalog(context)
 val subtitles=SubtitleShelf(context)
 var subtitleId by mutableStateOf("");private var pendingSubtitle=""
 val logs=mutableStateListOf<Diagnostic>()
 var connection by mutableStateOf("TV not connected");var paired by mutableStateOf(false);var state by mutableStateOf(JSONObject());var busy by mutableStateOf(false);var notice by mutableStateOf("")
 var active by mutableStateOf<String?>(null);var attempts by mutableIntStateOf(0);var buffering by mutableIntStateOf(0);var transferMbps by mutableDoubleStateOf(0.0)
 val controller=ControllerTools(context)
 var onChange:((String,JSONObject?)->Unit)?=null
 val main=Handler(Looper.getMainLooper())
 private val prefs=context.getSharedPreferences("darwin",Context.MODE_PRIVATE)
 private var pendingOwner:PlaybackOwner?=null
 private fun owner(snapshot:JSONObject)=PlaybackOwner(snapshot.optString("castId"),snapshot.optString("phase"),snapshot.optString("title"),snapshot.optString("mode"))
 private var stableSince=0L;private var bufferTime=0.0;private var lastStatusAt=System.currentTimeMillis();private var generation=0;private var castId="";private var accepted=false;private var pendingUntil=0L;private var lastCheckpoint=0L;private var lastPhase="";private var recoveryStarted=0L;private var retryPending=false;private var lastBytes=0L;private var lastSample=System.nanoTime();private var lastTVLog=0L;private var pendingQueue:String?=null;private val transientHeaders=mutableMapOf<String,Map<String,String>>()
 val tv=TvConnection{label,value->received(label,value)}
 private val recoveryTick=object:Runnable{override fun run(){if(retryPending&&!busy){if(System.currentTimeMillis()-recoveryStarted>=120000||attempts>3){retryPending=false;notice="Recovery paused. Retry when ready."}else if(paired){retryPending=false;active?.let{catalog.item(it)?.let{v->play(v,false,false)}}}else if(System.currentTimeMillis()-recoveryStarted<120000&&attempts<3){attempts++;reconnect()}else{retryPending=false;notice="Recovery paused. Reconnect or retry when ready."}};main.postDelayed(this,8000)}}
 init{main.postDelayed(recoveryTick,8000)}
 fun log(source:String,level:String,text:String){logs.add(Diagnostic(System.currentTimeMillis(),source,if(level=="warn")"warning"else level,redactDiagnostic(text)));while(logs.size>500)logs.removeAt(0)}
 private var controllerLogInstance="";private var controllerLogId=0L
 fun receiveControllerLogs(value:JSONObject){val instance=value.optString("instance");if(instance!=controllerLogInstance){controllerLogInstance=instance;controllerLogId=0};val rows=value.optJSONArray("rows")?:return;for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val id=row.optLong("id");if(id>controllerLogId){log("mac/"+row.optString("source","controller"),row.optString("level","info"),row.optString("message"));controllerLogId=id}}}

 fun connect(link:String){val u=Uri.parse(link.trim());require(u.scheme=="darwincast"&&u.host=="pair"){"Scan the TV’s Android pairing QR."};tv.connect(u.getQueryParameter("ip").orEmpty(),u.getQueryParameter("key").orEmpty());prefs.edit().putString("pair",link.trim()).apply()}
 fun reconnect(){val link=prefs.getString("pair","").orEmpty();if(link.isNotBlank())try{connect(link)}catch(e:Exception){notice=e.message?:"Pair again"}}
 private fun phoneIP()=DatagramSocket().use{it.connect(InetAddress.getByName(tv.ip),9);it.localAddress.hostAddress?:error("Connect to Wi-Fi")}
 fun useSubtitle(id:String){val mine=generation;thread{try{val track=subtitles.load(id);main.post{if(paired&&mine==generation){tv.command("subtitle_load",track);subtitleId=id;notice="Subtitles sent to TV."}}}catch(e:Exception){main.post{notice=e.message?:"Subtitle unavailable"}}}}
 fun command(op:String,args:JSONObject=JSONObject()):Boolean {if(op=="stop"){stop();return true};return tv.command(op,args)}
 fun stop(){checkpoint();pendingOwner=null;generation++;active=null;castId="";accepted=false;retryPending=false;pendingQueue=null;busy=false;tv.command("stop");closeSources();context.stopService(Intent(context,CastService::class.java));notice="Casting stopped.";log("phone","info","Stopped playback")}
 private fun closeSources(){CastService.relay?.stop();CastService.relay=null;CastService.youtube?.stop();CastService.youtube=null;CastService.localMovie?.stop();CastService.localMovie=null}
 fun rememberHeaders(id:String,headers:Map<String,String>){transientHeaders[id]=headers;while(transientHeaders.size>100)transientHeaders.remove(transientHeaders.keys.first())}
 fun play(row:SavedVideo,restart:Boolean=false,manual:Boolean=true){
  if(!paired){notice="Connect to your TV first.";return};if(busy)return
  checkpoint();pendingOwner=owner(state);val previousActive=active;val previousCastId=castId;val previousAccepted=accepted;val previousSubtitle=subtitleId;generation++;val mine=generation;active=row.id;castId=CastProtocol.token(16);val job=castId;pendingSubtitle=JSONObject(row.choices).optString("subtitleLibraryId");subtitleId="";accepted=false;pendingUntil=System.currentTimeMillis()+40000;retryPending=false;busy=true
  if(manual){pendingQueue=null;attempts=0;recoveryStarted=System.currentTimeMillis();buffering=0;bufferTime=0.0;stableSince=0}
  notice="Connecting to the TV…";log("phone","info","Preparing ${row.title}");context.startForegroundService(Intent(context,CastService::class.java));val ip=tv.ip;val position=if(restart||!row.resumable)0.0 else row.position
  thread(name="darwin-prepare") {
   var cleanup:(()->Unit)?=null;var movieSource:LocalMovie?=null;var youtubeSource:YoutubeCast?=null;var relaySource:MediaRelay?=null
   try {
    val phone=phoneIP();val choices=JSONObject(row.choices);val headers=transientHeaders[row.id]?:row.headers
    val data:JSONObject;val op:String
    when(row.kind){
     "file"->{val movie=LocalMovie(context,Uri.parse(row.source),ip);movieSource=movie;cleanup={movie.stop()};movie.prepare(phone);data=movie.configure(JSONObject().put("time",position).put("audio",choices.optInt("audio",movie.descriptor().getJSONObject("local").optInt("audio",-1))).put("subtitle",choices.optInt("subtitle",-1)).put("mode",choices.optString("mode","original")).put("quality",choices.optInt("quality",0)));op="local_play"}
     "youtube"->{val youtube=YoutubeCast(ip);youtubeSource=youtube;cleanup={youtube.stop()};data=youtube.prepare(row.source,phone);youtube.chooseQuality(choices.optInt("quality",0));op="play"}
     else->{val relay=MediaRelay(ip);relaySource=relay;cleanup={relay.stop()};val url=relay.begin(phone,row.source,headers);relay.quality=choices.optInt("quality",0);data=JSONObject().put("url",url).put("title",row.title).put("qualityUrl",relay.qualityUrl);op="play"}
    }
    main.post {
     if(mine!=generation){cleanup?.invoke();return@post}
     closeSources()
     // Keep the server alive in the foreground service, independently of the screen.
     val close=cleanup
     CastService.localMovie=movieSource;CastService.youtube=youtubeSource;CastService.relay=relaySource
     cleanup=null
     val args=if(op=="local_play")JSONObject().put("movie",data) else data.put("time",position)
     args.put("castId",job)
     if(!tv.command(op,args)){close?.invoke();busy=false;notice="TV disconnected before playback.";return@post}
     catalog.update(row.id){it.copy(title=data.optString("title",it.title),played=System.currentTimeMillis(),completed=false)}
     busy=false;notice="Sent to TV. Waiting for playback.";log("phone","info","Cast sent: ${row.title}")
    }
   }catch(e:Exception){cleanup?.invoke();main.post{if(mine==generation){busy=false;notice=e.message?:"Could not play this video";log("phone","error",notice);pendingQueue=null;if(previousAccepted&&state.optString("castId")==previousCastId&&state.optString("phase") in listOf("playing","paused","buffering")){active=previousActive;castId=previousCastId;accepted=true;pendingOwner=null;subtitleId=previousSubtitle;pendingSubtitle="";retryPending=false;notice="Could not open the new video. Current playback continues."}else{scheduleRecovery(notice);if(!retryPending){closeSources();context.stopService(Intent(context,CastService::class.java))}}}}}
  }
 }
 fun next(){val id=catalog.queue.firstOrNull()?:return;val row=catalog.item(id)?:return;if(!paired){notice="Connect to your TV first.";return};play(row,true);if(busy)pendingQueue=id}
 fun retry(){active?.let{catalog.item(it)}?.let{play(it,false)}}
 private fun checkpoint(snapshot:JSONObject=state){val id=active?:return;if(!accepted||snapshot.optString("castId")!=castId||snapshot.optString("phase") !in listOf("playing","paused","buffering","ended"))return;val p=snapshot.optDouble("time",0.0);val d=snapshot.optDouble("duration",0.0);if(!p.isFinite()||!d.isFinite())return;val local=snapshot.optJSONObject("local");val choices=JSONObject().put("subtitleLibraryId",if(local!=null&&local.optInt("subtitle",-1)>=0)""else subtitleId).put("quality",snapshot.optInt("qualityMode",0));if(local!=null)for(k in listOf("audio","subtitle","quality","mode"))if(local.has(k))choices.put(k,local.get(k));catalog.update(id){it.copy(position=p,duration=if(d>0)d else it.duration,choices=choices.toString(),played=System.currentTimeMillis())}}
 private fun scheduleRecovery(error:String){if(active==null||!catalog.recovery||attempts>=3||Regex("(?i)(40[134]|410|DRM|protected|expired|unsupported|no compatible|sign.in|unavailable)").containsMatchIn(error))return;if(attempts==0)recoveryStarted=System.currentTimeMillis();if(System.currentTimeMillis()-recoveryStarted>120000)return;checkpoint();attempts++;retryPending=true;notice="Waiting to recover playback · attempt $attempts of 3"}
 private fun relinquishPlayback(previousState:JSONObject){
  checkpoint(previousState);generation++;busy=false;active=null;castId="";accepted=false;pendingOwner=null;pendingSubtitle="";pendingQueue=null;retryPending=false
  closeSources();context.stopService(Intent(context,CastService::class.java));notice="Another device controls playback."
 }
 private fun received(label:String,value:JSONObject?){
  val wasPaired=paired;paired=tv.paired;connection=label

  if(wasPaired!=paired)log("connection",if(paired)"info"else"warning",label)
  if(value!=null){val previousState=state;state=value;val mac=value.optString("controllerURL");if(controller.address.isBlank()&&mac.isNotBlank())try{controller.configure(mac)}catch(_:Exception){};val phase=value.optString("phase");val id=value.optString("castId");val now=System.currentTimeMillis()
   if(phase!=lastPhase){log("tv",if(phase=="error")"error"else"info",phase+value.optString("error").let{if(it.isBlank())""else": $it"});lastPhase=phase}
   val appearance=value.optJSONObject("subtitles")?.optJSONObject("settings")
   if(appearance!=null){val edit=prefs.edit();val font=appearance.optString("font");if(font in listOf("sans","serif","mono"))edit.putString("font",font);val size=appearance.optInt("size");if(size in 24..96)edit.putFloat("fontSize",size.toFloat());if(appearance.has("background"))edit.putBoolean("subtitleBackground",appearance.optBoolean("background"));val language=appearance.optString("language");if(language in listOf("en","ea"))edit.putString("subtitleLanguage",language);edit.apply()}
   if(phase=="buffering"){bufferTime+=(now-lastStatusAt).coerceIn(0,5000)/1000.0;buffering=bufferTime.toInt()};lastStatusAt=now
   if(accepted&&phase=="playing"){if(stableSince==0L)stableSince=now;else if(now-stableSince>60000){attempts=0;recoveryStarted=0}}else stableSince=0
   if(active!=null&&!accepted&&pendingOwner?.replacedBy(owner(value),castId)==true)relinquishPlayback(previousState)
   if(active!=null){
    if(id==castId&&phase in listOf("playing","paused","buffering")){if(!accepted)notice="Playing on TV";accepted=true;pendingOwner=null;if(pendingSubtitle.isNotBlank()){useSubtitle(pendingSubtitle);pendingSubtitle=""};if(pendingQueue==active){if(catalog.queue.firstOrNull()==active)catalog.dequeue(0);pendingQueue=null}}
    val completed=owner(value).completed(castId,value.optString("endedCastId"),accepted)
    if(accepted&&id!=castId&&!completed)relinquishPlayback(previousState)
    else if(completed){
     val finished=active;checkpoint();finished?.let{catalog.update(it){v->v.copy(completed=true,position=0.0)}};active=null;castId="";accepted=false;pendingOwner=null;retryPending=false;closeSources();if(catalog.autoplay&&catalog.queue.isNotEmpty())next()else{context.stopService(Intent(context,CastService::class.java));notice="Playback finished."}
    }else if(phase=="error"&&!retryPending){notice=value.optString("error","Playback failed");scheduleRecovery(notice)}
    else if(!accepted&&!busy&&now>pendingUntil&&!retryPending){notice="TV did not confirm playback. Check the TV or retry.";scheduleRecovery(notice);pendingUntil=now+40000}
   }
   if(now-lastCheckpoint>10000){checkpoint();lastCheckpoint=now}
   val rows=value.optJSONArray("diagnostics");if(rows!=null)for(i in 0 until rows.length()){val r=rows.getJSONObject(i);if(r.optLong("id")>lastTVLog){log("tv",r.optString("level","info"),r.optString("message"));lastTVLog=r.optLong("id")}}
   val bytes=(CastService.relay?.bytesSent?:0)+(CastService.localMovie?.bytesSent?:0)+(CastService.youtube?.bytesSent?:0);val at=System.nanoTime();transferMbps=if(bytes>=lastBytes)(bytes-lastBytes)*8.0/((at-lastSample).coerceAtLeast(1)/1e9)/1e6 else 0.0;lastBytes=bytes;lastSample=at
  }
  if(paired&&value!=null&&label!="Connected to TV"&&active!=null&&!retryPending){notice=label;log("tv","error",label);scheduleRecovery(label)}
  if(wasPaired&&!paired&&active!=null)scheduleRecovery(label)
  onChange?.invoke(label,value)
 }
}
