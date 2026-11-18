package io.github.gajdarwin.cast
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

data class SavedSubtitle(val id:String,val name:String,val language:String,val count:Int)
class SubtitleShelf(context:Context){
 private val dir=File(context.filesDir,"subtitles");val rows=mutableStateListOf<SavedSubtitle>();private val main=Handler(Looper.getMainLooper())
 init{thread{val list=dir.listFiles()?.filter{it.extension=="json"}?.take(200)?.mapNotNull{try{val d=JSONObject(it.readText());SavedSubtitle(it.nameWithoutExtension,d.getString("name"),d.getString("language"),d.getJSONArray("cues").length())}catch(_:Exception){null}}?:emptyList();main.post{rows.addAll(list)}}}
 fun save(name:String,language:String,cues:JSONArray):SavedSubtitle{require(language in listOf("en","ea"));require(cues.length() in 1..12000);val text=cues.toString();require(text.toByteArray().size<1400000);val id=MessageDigest.getInstance("SHA-256").digest((language+text).toByteArray()).joinToString(""){"%02x".format(it)}.take(32);dir.mkdirs();val file=File(dir,"$id.json");require(file.exists()||(dir.list()?.size?:0)<200){"Subtitle library is full"};val data=JSONObject().put("name",name.take(200)).put("language",language).put("cues",cues);val tmp=File(dir,"$id.tmp");tmp.writeText(data.toString());check(tmp.renameTo(file)){"Could not save subtitles"};val row=SavedSubtitle(id,name.take(200),language,cues.length());main.post{rows.removeAll{it.id==id};rows.add(0,row)};return row}
 fun load(id:String):JSONObject{require(id.matches(Regex("[a-f0-9]{32}")));return File(dir,"$id.json").inputStream().use{JSONObject(String(it.readBytesLimited(1500000)))}}
}
@Composable fun SubtitlePage(session:PlaybackSession,onImport:()->Unit,onAppearance:()->Unit,onMac:()->Unit){
 var query by remember{mutableStateOf(session.state.optString("title").replace(Regex("\\.(mp4|mkv|avi|mov)$",RegexOption.IGNORE_CASE),""))};var language by remember{mutableStateOf("ea")};var results by remember{mutableStateOf(emptyList<JSONObject>())};var working by remember{mutableStateOf(false)};var note by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
 fun select(row:JSONObject){working=true;scope.launch{try{val track=withContext(Dispatchers.IO){session.controller.command(JSONObject().put("op","subtitle_fetch").put("file_id",row.getInt("id"))).getJSONObject("track")};val saved=withContext(Dispatchers.IO){session.subtitles.save(track.optString("name","Subtitles"),track.optString("language",language),track.getJSONArray("cues"))};session.useSubtitle(saved.id);note="Saved and sent to TV."}catch(e:Exception){note=e.message?:"Could not add subtitles"}finally{working=false}}}
 LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{PanelTitle("Subtitles","English and Latin American Spanish. Imported and saved subtitles work directly from your phone.");Row{Button(onClick=onImport){Text(L("Import SRT / VTT"))};TextButton(onClick=onAppearance){Text(L("Appearance & timing"))}};Text(L("The chosen font and size are saved for future videos."),fontSize=12.sp)}
  item{Text(L("Search OpenSubtitles"),fontSize=21.sp);OutlinedTextField(query,{query=it},label={Text(L("Movie or episode title"))},modifier=Modifier.fillMaxWidth());Row{FilterChip(language=="ea",{language="ea"},label={Text(L("Español · México / LATAM"))});FilterChip(language=="en",{language="en"},label={Text(L("English"))},modifier=Modifier.padding(start=8.dp))};if(session.controller.address.isBlank())TextButton(onClick=onMac){Text(L("Connect Mac for online search"))}else Button(onClick={working=true;scope.launch{try{val r=withContext(Dispatchers.IO){session.controller.command(JSONObject().put("op","subtitle_search").put("query",query.trim()).put("language",language))};val a=r.optJSONArray("results")?:JSONArray();results=(0 until a.length()).map{a.getJSONObject(it)};note=if(results.isEmpty())"No matches. Try a shorter title."else "Choose the release that matches your movie."}catch(e:Exception){note=e.message?:"Search unavailable"}finally{working=false}}},enabled=!working&&query.isNotBlank()){Text(L(if(working)"Working…"else"Search"))};Text(L("Online search uses the local Mac controller. Your saved subtitle library remains on this phone."),fontSize=12.sp);Text(L(note),fontSize=13.sp)}
  items(results){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(row.optString("name"));Text(row.optString("release"),fontSize=12.sp);Button(onClick={select(row)},enabled=!working&&session.paired){Text(L("Add and use"))}}}}
  item{Text(L("Saved subtitles"),fontSize=21.sp)}
  items(session.subtitles.rows.toList(),key={it.id}){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(row.name);Text(L((if(row.language=="ea")"Spanish · Latin America"else"English")+" · ${row.count} lines"),fontSize=12.sp);Button(onClick={session.useSubtitle(row.id)},enabled=session.paired){Text(L("Use on TV"))}}}}
  if(session.subtitles.rows.isEmpty())item{Text(L("Import a subtitle file or save a search result to start your library."))}
 }
}
