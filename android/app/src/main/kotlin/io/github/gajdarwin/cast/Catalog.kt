package io.github.gajdarwin.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

data class SavedVideo(val id:String,val kind:String,val source:String,val title:String,val page:String="",val duration:Double=0.0,val position:Double=0.0,val favorite:Boolean=false,val completed:Boolean=false,val played:Long=0,val headers:Map<String,String> = emptyMap(),val choices:String="{}") {
 val resumable get()=position>5&&!completed&&(duration<=0||position<duration-5)
 fun json()=JSONObject().put("id",id).put("kind",kind).put("source",source).put("title",title).put("page",page).put("duration",duration).put("position",position).put("favorite",favorite).put("completed",completed).put("played",played).put("headers",JSONObject(headers)).put("choices",JSONObject(choices))
 companion object { fun read(j:JSONObject):SavedVideo {val h=j.optJSONObject("headers")?:JSONObject();return SavedVideo(j.getString("id"),j.getString("kind"),j.getString("source"),j.getString("title"),j.optString("page"),j.optDouble("duration",0.0),j.optDouble("position",0.0),j.optBoolean("favorite"),j.optBoolean("completed"),j.optLong("played"),h.keys().asSequence().associateWith{h.getString(it)},j.optJSONObject("choices")?.toString()?:"{}")} }
}
class Catalog(context:Context) {
 val items=mutableStateListOf<SavedVideo>();val queue=mutableStateListOf<String>()
 var autoplay by mutableStateOf(true);var recovery by mutableStateOf(true);var loaded by mutableStateOf(false)
 private val file=AtomicFile(File(context.filesDir,"library.json"));private val io=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper());private val saveJob=Runnable{persist()}
 init {io.execute {val data=try{file.openRead().use{JSONObject(String(it.readBytesLimited(4*1024*1024)))}}catch(_:Exception){JSONObject()};main.post {
  val a=data.optJSONArray("items")?:JSONArray();for(i in 0 until minOf(a.length(),500))try{items.add(SavedVideo.read(a.getJSONObject(i)))}catch(_:Exception){}
  val q=data.optJSONArray("queue")?:JSONArray();for(i in 0 until minOf(q.length(),100)){val id=q.optString(i);if(items.any{it.id==id})queue.add(id)}
  autoplay=data.optBoolean("autoplay",true);recovery=data.optBoolean("recovery",true);loaded=true
 }}}
 fun item(id:String)=items.find{it.id==id}
 fun add(kind:String,source:String,title:String,page:String="",duration:Double=0.0,headers:Map<String,String> = emptyMap()):SavedVideo {
  check(loaded){"Library is still loading. Try again in a moment."}
  val id=MessageDigest.getInstance("SHA-256").digest((kind+"\u0000"+source).toByteArray()).joinToString(""){"%02x".format(it)}.take(32)
  val old=item(id);val safe=headers.filterKeys{it.lowercase() in listOf("referer","origin","user-agent","accept")}
  val row=(old?:SavedVideo(id,kind,source,title.take(200))).copy(title=title.ifBlank{old?.title?:"Video"}.take(200),page=page, duration=duration.takeIf{it>0}?:old?.duration?:0.0,headers=safe.ifEmpty{old?.headers?:emptyMap()})
  if(old==null){check(items.size<500){"The library holds 500 videos. Remove an unused entry first."};items.add(0,row)}else items[items.indexOf(old)]=row
  changed();return row
 }
 fun update(id:String,change:(SavedVideo)->SavedVideo){val i=items.indexOfFirst{it.id==id};if(i>=0){items[i]=change(items[i]);changed()}}
 fun remove(id:String){items.removeAll{it.id==id};queue.removeAll{it==id};changed()}
 fun enqueue(id:String){if(item(id)!=null&&queue.size<100){queue.add(id);changed()}}
 fun dequeue(index:Int){if(index in queue.indices){queue.removeAt(index);changed()}}
 fun move(index:Int,delta:Int){val next=index+delta;if(index in queue.indices&&next in queue.indices){val id=queue.removeAt(index);queue.add(next,id);changed()}}
 fun changed(){if(!loaded)return;main.removeCallbacks(saveJob);main.postDelayed(saveJob,250)}
 private fun persist(){val data=JSONObject().put("version",1).put("items",JSONArray(items.map{it.json()})).put("queue",JSONArray(queue.toList())).put("autoplay",autoplay).put("recovery",recovery).toString();io.execute{var out:java.io.FileOutputStream?=null;try{out=file.startWrite();out.write(data.toByteArray());file.finishWrite(out)}catch(_:Exception){out?.let{file.failWrite(it)}}}}
}
