package io.github.gajdarwin.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun durationText(value:Double):String {val s=value.toLong().coerceAtLeast(0);return if(s>=3600)"${s/3600}:${"%02d".format(s/60%60)}:${"%02d".format(s%60)}"else "${s/60}:${"%02d".format(s%60)}"}
@Composable internal fun PanelTitle(title:String,detail:String){Column(Modifier.padding(bottom=18.dp)){Text(L(title),fontSize=30.sp,fontWeight=FontWeight.Bold);Text(L(detail),color=Color(0xFFA9B9C0),fontSize=14.sp,modifier=Modifier.padding(top=8.dp))}}
@Composable private fun Artwork(row:SavedVideo){
 val context=LocalContext.current
 val image by produceState<Bitmap?>(null,row.id){value=withContext(Dispatchers.IO){try{
  val dir=File(context.cacheDir,"artwork").also{it.mkdirs()};val file=File(dir,row.id+".jpg")
  if(file.isFile){file.setLastModified(System.currentTimeMillis());BitmapFactory.decodeFile(file.absolutePath)} else {
   val bitmap=if(row.kind=="file"){val retriever=MediaMetadataRetriever();try{retriever.setDataSource(context,Uri.parse(row.source));retriever.getFrameAtTime(1000000)}finally{retriever.release()}}else if(row.kind=="youtube"){
    val id=Uri.parse(row.source).getQueryParameter("v");val connection=java.net.URL("https://i.ytimg.com/vi/$id/hqdefault.jpg").openConnection().apply{connectTimeout=5000;readTimeout=5000};connection.getInputStream().use{val data=it.readBytesLimited(1024*1024);BitmapFactory.decodeByteArray(data,0,data.size)}
   }else null
   bitmap?.let{val small=Bitmap.createScaledBitmap(it,360,(it.height*360.0/it.width).toInt().coerceAtLeast(1),true);file.outputStream().use{out->small.compress(Bitmap.CompressFormat.JPEG,75,out)};if(small!==it)it.recycle();dir.listFiles()?.filter{f->f.extension=="jpg"}?.sortedByDescending{f->f.lastModified()}?.drop(200)?.forEach{f->f.delete()};small}
  }
 }catch(_:Exception){null}}}
 Box(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF263D35)),contentAlignment=Alignment.Center){if(image!=null)Image(image!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Text(L(if(row.kind=="file")"LOCAL MOVIE"else "VIDEO"),color=Color(0xFFBBF2CD),fontSize=12.sp,letterSpacing=2.sp)}
}
@Composable fun LibraryPanel(session:PlaybackSession,onBrowse:()->Unit,onFile:()->Unit,onPlay:(SavedVideo,Boolean)->Unit){
 val catalog=session.catalog;var query by remember{mutableStateOf("")};var filter by remember{mutableStateOf("All")}
 val rows=catalog.items.filter{(query.isBlank()||(it.title+" "+it.page).contains(query,true))&&(filter!="Favorites"||it.favorite)&&(filter!="Continue"||it.resumable)}.sortedByDescending{it.played}
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=24.dp)){
  item{PanelTitle("Your library","Saved on this phone. Resume a movie or line up what comes next.");Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=onBrowse){Text(L("Find a video"))};OutlinedButton(onClick=onFile){Text(L("Add a file"))}}} 
  item{OutlinedTextField(query,{query=it},label={Text(L("Search your library"))},singleLine=true,modifier=Modifier.fillMaxWidth());Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("All","Favorites","Continue").forEach{label->FilterChip(selected=filter==label,onClick={filter=label},label={Text(L(label))})}}}
  if(rows.isEmpty())item{Text(L(if(!catalog.loaded)"Loading your library…"else "Save a video from Browse or add a local movie to get started."),modifier=Modifier.padding(vertical=24.dp))}
  items(rows,key={it.id}){row->Card(shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){
   Artwork(row);Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text(row.title,fontSize=19.sp,fontWeight=FontWeight.SemiBold,maxLines=3)
    Text(L(if(row.completed)"Watched"else if(row.resumable)"${durationText(row.position)} / ${durationText(row.duration)}"else if(row.duration>0)durationText(row.duration)else row.kind.replaceFirstChar{it.uppercase()}),fontSize=12.sp,color=Color(0xFFA9B9C0))
    if(row.resumable&&row.duration>0)LinearProgressIndicator(progress={ (row.position/row.duration).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={onPlay(row,false)},enabled=session.paired&&!session.busy){Text(L(if(row.resumable)"Resume"else "Play"))};OutlinedButton(onClick={catalog.enqueue(row.id)}){Text(L("Queue"))};TextButton(onClick={catalog.update(row.id){it.copy(favorite=!it.favorite)}}){Text(L(if(row.favorite)"♥"else"♡"))}}
    var menu by remember{mutableStateOf(false)};Box{TextButton(onClick={menu=true}){Text(L("More options"))};DropdownMenu(menu,{menu=false}){
     DropdownMenuItem(text={Text(L("Play from beginning"))},onClick={menu=false;onPlay(row,true)},enabled=session.paired&&!session.busy)
     DropdownMenuItem(text={Text(L("Mark unwatched"))},onClick={menu=false;catalog.update(row.id){it.copy(completed=false,position=0.0)}})
     DropdownMenuItem(text={Text(L("Remove from library"))},onClick={menu=false;catalog.remove(row.id)})
    }}
   }
  }}
 }
}
@Composable fun QueuePanel(session:PlaybackSession){val catalog=session.catalog
 LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{PanelTitle("Up next","Your phone runs this queue while casting. Keep it on Wi-Fi.");Row(verticalAlignment=Alignment.CenterVertically){Text(L("Play next automatically"),Modifier.weight(1f));Switch(catalog.autoplay,{catalog.autoplay=it;catalog.changed()})};Button(onClick={session.next()},enabled=session.paired&&!session.busy&&catalog.queue.isNotEmpty()){Text(L("Play next now"))}}
  if(catalog.queue.isEmpty())item{Text(L("Add videos from Library, Browse, or Files."),Modifier.padding(vertical=20.dp))}
  itemsIndexed(catalog.queue.toList()){index,id->session.catalog.item(id)?.let{row->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(16.dp)){Text("${index+1}. ${row.title}",fontWeight=FontWeight.SemiBold);Row{TextButton(onClick={catalog.move(index,-1)},enabled=index>0){Text(L("Earlier"))};TextButton(onClick={catalog.move(index,1)},enabled=index<catalog.queue.lastIndex){Text(L("Later"))};TextButton(onClick={catalog.dequeue(index)}){Text(L("Remove"))}}}}}}
  item{Text(L("Only natural completion advances the queue. Stop, errors, and another device taking over leave it paused. Opening Darwin never starts the queue by itself."),fontSize=12.sp,color=Color(0xFFA9B9C0))}
 }
}
@Composable fun HealthPanel(session:PlaybackSession,onLogs:()->Unit){
 val cache=CastService.relay?.bufferStats()?:CastService.youtube?.bufferStats()?:Pair(0,0)
 LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{PanelTitle("Playback health","Live measurements from this phone and your TV.");Text(L(session.connection),fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text(L(session.notice),Modifier.padding(top=10.dp))}
  val mode=session.state.optJSONObject("local")?.optString("mode")?:"Stream relay"
  items(listOf("Playback" to session.state.optString("phase","Idle"),"Media transfer" to "${"%.1f".format(session.transferMbps)} Mbps","Playback mode" to mode,"Observed buffering" to "${session.buffering} seconds","Rewind buffer" to "${"%.1f".format(cache.first/1048576.0)} MB · ${cache.second} cache hits","Source bitrate" to (session.state.optJSONObject("local")?.let{if(it.optDouble("duration")>0)"${"%.1f".format(it.optLong("size")*8/it.optDouble("duration")/1e6)} Mbps"else"Unknown"}?:"Unknown"))){metric->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(L(metric.first),fontSize=12.sp,color=Color(0xFFA9B9C0));Text(L(metric.second),fontSize=23.sp,modifier=Modifier.padding(top=6.dp))}}}
  item{Row(verticalAlignment=Alignment.CenterVertically){Text(L("Recover interrupted playback"),Modifier.weight(1f));Switch(session.catalog.recovery,{session.catalog.recovery=it;session.catalog.changed()})};Text(L("At most 3 recovery attempts within 2 minutes. Recovery never turns on the TV. Source restrictions and expired links require opening the website again."),fontSize=12.sp,color=Color(0xFFA9B9C0));Row{Button(onClick={session.retry()},enabled=session.active!=null&&session.paired&&!session.busy){Text(L("Retry now"))};TextButton(onClick=onLogs){Text(L("Open logs"))}}}
  item{Text(L("Transfer rate measures delivered media bytes, not Wi-Fi capacity. Converted local files still reopen their player when seeking; the rewind buffer covers completed recorded-stream segments and exact byte ranges."),fontSize=12.sp,color=Color(0xFFA9B9C0))}
 }
}
@Composable fun LogsPanel(session:PlaybackSession,onExport:(String)->Unit){var query by remember{mutableStateOf("")};var severity by remember{mutableStateOf("All")};var source by remember{mutableStateOf("All sources")};val clipboard=LocalClipboardManager.current;var macNote by remember{mutableStateOf("")};var paused by remember{mutableStateOf(false)};var frozen by remember{mutableStateOf(emptyList<Diagnostic>())}
 LaunchedEffect(session.controller.address){if(session.controller.address.isNotBlank())while(isActive){try{val result=withContext(Dispatchers.IO){session.controller.request("/phone-logs")};session.receiveControllerLogs(result);macNote="Mac controller logs connected"}catch(_:Exception){macNote="Mac logs unavailable. Start the updated Mac controller."};delay(2500)}}
 val rows=(if(paused)frozen else session.logs.toList()).filter{(severity=="All"||it.level==severity.lowercase())&&(source=="All sources"||if(source=="Mac")it.source.startsWith("mac/")else it.source==source.lowercase())&&(query.isBlank()||(it.text+it.source).contains(query,true))}.reversed()
 LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  item{PanelTitle("Diagnostics","TV and phone events, plus Mac controller logs when connected. Credentials are redacted.");if(macNote.isNotBlank())Text(L(macNote),fontSize=12.sp);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("All sources","TV","Phone","Mac").forEach{v->FilterChip(source==v,{source=v},label={Text(L(v))},modifier=Modifier.padding(end=4.dp))}};OutlinedTextField(query,{query=it},label={Text(L("Search messages"))},modifier=Modifier.fillMaxWidth());Row(Modifier.horizontalScroll(rememberScrollState())){listOf("All","Error","Warning").forEach{level->FilterChip(severity==level,{severity=level},label={Text(L(level))},modifier=Modifier.padding(end=6.dp))}};Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={paused=!paused;if(paused)frozen=session.logs.toList()}){Text(L(if(paused)"Resume"else"Pause"))};TextButton(onClick={onExport(rows.reversed().joinToString("\n"){"${java.util.Date(it.time)} [${it.source}] ${it.level}: ${it.text}"})},enabled=rows.isNotEmpty()){Text(L("Export"))};TextButton(onClick={clipboard.setText(AnnotatedString(rows.reversed().joinToString("\n"){"${java.util.Date(it.time)} [${it.source}] ${it.level}: ${it.text}"}))},enabled=rows.isNotEmpty()){Text(L("Copy"))};TextButton(onClick={session.logs.clear();frozen=emptyList()}){Text(L("Clear view"))}}}
  if(rows.isEmpty())item{Text(L("No matching events yet."))}
  items(rows){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date(row.time))} · ${row.source} · ${row.level}",fontSize=11.sp,color=Color(0xFFA9B9C0));Text(row.text,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))}}}
 }
}
