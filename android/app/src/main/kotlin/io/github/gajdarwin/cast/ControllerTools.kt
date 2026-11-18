package io.github.gajdarwin.cast

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ControllerTools(context:Context){
 private val prefs=context.getSharedPreferences("darwin",Context.MODE_PRIVATE)
 var address by mutableStateOf(prefs.getString("controller","").orEmpty());private set
 private val client=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(35,TimeUnit.SECONDS).followRedirects(false).build()
 fun configure(value:String){val u=Uri.parse(value);require(u.scheme=="http"&&CastProtocol.localIP(u.host.orEmpty())&&u.port==8790&&u.userInfo==null&&u.path=="/"&&u.fragment==null&&u.getQueryParameter("k")?.matches(Regex("[a-z0-9]{6,64}"))==true){"Use the local Mac remote link from the TV’s main QR code."};address=value;prefs.edit().putString("controller",value).apply()}
 fun bytes(path:String,body:JSONObject?=null):ByteArray{check(address.isNotBlank()){ "Connect the Mac controller first." };val u=address.toHttpUrl().newBuilder().encodedPath(path).build();val b=Request.Builder().url(u);if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()));client.newCall(b.build()).execute().use{r->if(!r.isSuccessful)error("Mac controller returned HTTP ${r.code}");return r.body!!.byteStream().use{it.readBytesLimited(2*1024*1024)}}}
 fun request(path:String,body:JSONObject?=null):JSONObject{val r=JSONObject(String(bytes(path,body)));if(r.has("ok")&&!r.optBoolean("ok"))error(r.optString("error","Controller unavailable"));return r}
 fun command(body:JSONObject)=request("/cmd",body)
}
@Composable fun MacConnectionPanel(client:ControllerTools){var value by remember(client.address){mutableStateOf(client.address)};var note by remember{mutableStateOf("")};var working by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
 Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){PanelTitle("Mac tools","TV browser, full controller logs, and OpenSubtitles search use the local Mac controller. Phone casting and its library stay independent.");OutlinedTextField(value,{value=it},label={Text(L("Local remote link from the TV QR"))},modifier=Modifier.fillMaxWidth(),maxLines=3);Button(onClick={try{client.configure(value.trim());working=true;scope.launch{try{val s=withContext(Dispatchers.IO){client.request("/state")};note=if(s.optBoolean("connected"))"Mac and TV connected."else"Mac connected. Open Darwin on the TV."}catch(e:Exception){note=e.message?:"Unavailable"}finally{working=false}}}catch(e:Exception){note=e.message?:"Invalid link"}},enabled=!working){Text(L(if(working)"Checking…"else"Save and check"))};Text(L(note),fontSize=13.sp)}
}
@Composable fun TVBrowserPanel(client:ControllerTools,onConnect:()->Unit){
 if(client.address.isBlank()){Column(Modifier.padding(20.dp)){PanelTitle("TV browser","Connect the local Mac controller to control the TV’s browser.");Button(onClick=onConnect){Text(L("Connect Mac tools"))}};return}
 var url by remember{mutableStateOf("")};var typing by remember{mutableStateOf("")};var status by remember{mutableStateOf(JSONObject())};var bitmap by remember{mutableStateOf<android.graphics.Bitmap?>(null)};var note by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
 fun command(d:JSONObject){scope.launch{try{withContext(Dispatchers.IO){client.command(d)};note=""}catch(e:Exception){note=e.message?:"Command failed"}}}
 LaunchedEffect(client.address){while(isActive){try{val s=withContext(Dispatchers.IO){client.request("/state")};status=s;if(url.isEmpty())url=s.optString("url").takeIf{it.startsWith("http")}?:"";if(s.optBoolean("connected")&&s.optJSONObject("direct")?.optString("phase") !in listOf("playing","paused","buffering")){val bytes=withContext(Dispatchers.IO){client.bytes("/frame.jpg")};bitmap=withContext(Dispatchers.Default){BitmapFactory.decodeByteArray(bytes,0,bytes.size)}};note=""}catch(e:Exception){note="Mac unavailable. Start Darwin on the Mac to use this tool."};delay(1500)}}
 LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{PanelTitle("TV browser","Navigate, click, and type on the TV using your local Mac controller.");OutlinedTextField(url,{url=it},label={Text(L("Website on TV"))},modifier=Modifier.fillMaxWidth());Button(onClick={command(JSONObject().put("op","navigate").put("url",url))}){Text(L("Open on TV"))};Row{for(op in listOf("back","forward","reload","home"))TextButton(onClick={command(JSONObject().put("op",op))}){Text(L(op.replaceFirstChar{it.uppercase()}))}}}
  item{if(bitmap!=null)Image(bitmap!!.asImageBitmap(),L("Tap the TV picture to click"),Modifier.fillMaxWidth().aspectRatio(bitmap!!.width.toFloat()/bitmap!!.height).pointerInput(Unit){detectTapGestures{p->command(JSONObject().put("op","click").put("x",(p.x/size.width).toDouble()).put("y",(p.y/size.height).toDouble()))}})else Text(L("TV picture appears when its browser is open. Use Remote for video playback."))}
  item{OutlinedTextField(typing,{typing=it},label={Text(L("Type into the selected TV field"))},modifier=Modifier.fillMaxWidth());Row{Button(onClick={command(JSONObject().put("op","text").put("text",typing));typing=""}){Text(L("Send text"))};TextButton(onClick={command(JSONObject().put("op","key").put("key","Enter"))}){Text(L("Enter"))};TextButton(onClick={command(JSONObject().put("op","hidekb"))}){Text(L("Hide keyboard"))}};Row{TextButton(onClick={command(JSONObject().put("op","scroll").put("x",.5).put("y",.5).put("dx",0).put("dy",-400))}){Text(L("Scroll up"))};TextButton(onClick={command(JSONObject().put("op","scroll").put("x",.5).put("y",.5).put("dx",0).put("dy",400))}){Text(L("Scroll down"))}}}
  item{Text(L("Protection"),fontSize=20.sp);Row{Text(L("Block pop-ups automatically"),Modifier.weight(1f));Switch(status.optBoolean("block_all_popups",true),{command(JSONObject().put("op","protection").put("mode",if(it)"block"else"review"))})};Row{Text(L("Block site ads"),Modifier.weight(1f));Switch(status.optBoolean("site_ads",true),{command(JSONObject().put("op","site_ads").put("enabled",it))})};Row{Text(L("Desktop website"),Modifier.weight(1f));Switch(status.optBoolean("desktop_mode"),{command(JSONObject().put("op","desktop").put("enabled",it))})};TextButton(onClick={command(JSONObject().put("op","clearoverlays"))}){Text(L("Clear overlays"))}}
  val popups=status.optJSONArray("popups");if(popups!=null)for(i in 0 until popups.length()){val popup=popups.getJSONObject(i);item{Card{Column(Modifier.padding(14.dp)){Text(L(popup.optString("site")+" wants to open a page"));Row{TextButton(onClick={command(JSONObject().put("op","popup").put("id",popup.getString("id")).put("decision","once"))}){Text(L("Open once"))};TextButton(onClick={command(JSONObject().put("op","popup").put("id",popup.getString("id")).put("decision","blockalways"))}){Text(L("Always block"))}}}}}}
  item{Text(L(note),fontSize=12.sp)}
 }
}
