package io.github.gajdarwin.cast

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.net.NetworkInterface
import kotlin.concurrent.thread

private val Ink = Color(0xFF101A20)
private val Mint = Color(0xFFBBF2CD)
private val Muted = Color(0xFFA9B9C0)
data class FoundVideo(val url: String, val kind: String, val headers: Map<String,String>,val group:String=url,val title:String="")

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("darwin", MODE_PRIVATE) }
    private var tab by mutableIntStateOf(4)
    private lateinit var session:PlaybackSession
    private var selectedFileId:String?=null
    private var exportText=""
    private val logExporter=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null){val text=exportText;thread{try{contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())}}catch(e:Exception){runOnUiThread{message="Could not export logs"}}}}}
    private var address by mutableStateOf("")
    private var pageTitle by mutableStateOf("")
    private var pageOpen by mutableStateOf(false)
    private var message by mutableStateOf("Choose a video. Make it a big-screen night.")
    private var connection by mutableStateOf("TV not connected")
    private var tvPaired by mutableStateOf(false)
    private var tvState by mutableStateOf(JSONObject())
    private var pairDialog by mutableStateOf(false)
    private var pairText by mutableStateOf("")
    private var pairError by mutableStateOf("")
    private var subtitleDialog by mutableStateOf(false)
    private var localDescription by mutableStateOf<JSONObject?>(null)
    private var localBusy by mutableStateOf(false)
    private var tracksDialog by mutableStateOf(false)
    private var pendingUri: Uri? = null
    private val found = mutableStateListOf<FoundVideo>()
    private lateinit var browser: WebView
    private lateinit var tv: TvConnection
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { pairText = it; pairError = ""; pairDialog = true; connect() }
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread {
                try {
                    val input = contentResolver.openInputStream(uri) ?: error("File unavailable")
                    val bytes = input.use { it.readBytesLimited(1048576) }
                    val cues = Subtitles.parse(String(bytes, Charsets.UTF_8))
                    var name="Imported subtitles";contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())name=c.getString(0)}
                    val saved=session.subtitles.save(name,prefs.getString("subtitleLanguage","ea")?:"ea",cues)
                    runOnUiThread { session.useSubtitle(saved.id); message = "Saved ${cues.length()} subtitle lines." }
                } catch (e: Exception) { runOnUiThread { message = e.message ?: "Could not read subtitles" } }
            }
        }
    }
    private val moviePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}
            localBusy=true;message="Inspecting the movie…";tab=3
            startForegroundService(Intent(this,CastService::class.java))
            thread {
                try {
                    val movie=LocalMovie(applicationContext,uri,tv.ip)
                    val d=try{movie.prepare(phoneAddress())}finally{movie.stop()}
                    runOnUiThread{val row=session.catalog.add("file",uri.toString(),d.optString("title","Movie"),duration=d.getJSONObject("local").getDouble("duration"));selectedFileId=row.id;localDescription=d;localBusy=false;message="Added to Library. The original file stays on your phone."}
                }catch(e:Exception){runOnUiThread{localBusy=false;message=e.message?:"Could not open movie"}}
            }
        }
    }
    private fun phoneAddress():String = java.net.DatagramSocket().use { socket -> socket.connect(java.net.InetAddress.getByName(tv.ip),9);socket.localAddress.hostAddress?:error("Connect to Wi-Fi") }
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(AppLanguage.wrap(base));AppLanguage.initialize(base)}
    override fun onSaveInstanceState(outState:Bundle){outState.putInt("darwin.tab",tab);outState.putString("darwin.address",address);super.onSaveInstanceState(outState)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        session=CastService.session(this);tv=session.tv;tvPaired=session.paired;connection=session.connection;tvState=session.state
        session.onChange={label,state->tvPaired=session.paired;connection=label;state?.let{tvState=it}}
        createBrowser()
        if(savedInstanceState==null)handleIntent(intent)else{tab=savedInstanceState.getInt("darwin.tab",4);address=savedInstanceState.getString("darwin.address","")}
        if(!session.paired&&intent.data?.scheme!="darwincast")session.reconnect()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Color(0xFF1B282F),onSurface=Color(0xFFF0F5F3),secondary=Mint,secondaryContainer=Color(0xFF304B3E),onSecondaryContainer=Mint)) { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(i: Intent) {
        if (i.data?.scheme == "darwincast") { pairText = i.data.toString(); pairDialog = true }
        if (i.action == Intent.ACTION_SEND) { address = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty(); tab = 0 }
    }
    private fun createBrowser() {
        browser = WebView(this)
        browser.setBackgroundColor(android.graphics.Color.rgb(16,26,32))
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false; allowContentAccess = false; setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false; mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        val playback = assets.open("playback.js").bufferedReader().use { it.readText() }
        val detection = playback + "\n" + assets.open("detect.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(browser, detection, setOf("*"))
        browser.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null && url != address) { found.clear(); address = url; pageTitle = view.title.orEmpty(); pauseBrowse() }
            }
            override fun onPageFinished(view: WebView, url: String) { pageTitle = view.title.orEmpty(); address = url; view.evaluateJavascript(detection,null) }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in listOf("http","https")
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (Regex("\\.(m3u8|mpd|mp4|webm|m4v|mov)([?#]|$)",RegexOption.IGNORE_CASE).containsMatchIn(url))
                    addVideo(url, kind(url), request.requestHeaders)
                return null
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView?, dialog: Boolean, gesture: Boolean, result: android.os.Message?) = false
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().startsWith("DARWIN_MEDIA:")) {
                    try { val d = JSONObject(m.message().substringAfter("DARWIN_MEDIA:"));if(d.optString("event")=="page"){runOnUiThread{val next=d.optString("url");if(next!=address){found.clear();address=next};pageTitle=d.optString("title")}}else {
                        val url=d.getString("url");val variants=d.optJSONArray("variants");if(variants!=null)runOnUiThread{val children=(0 until variants.length()).map{variants.getJSONObject(it).optString("url")};found.removeAll{it.url in children}}
                        addVideo(url,if(d.optString("kind")=="YouTube")"YouTube"else kind(url),mapOf("Referer" to d.optString("referer")),d.optString("group",url),d.optString("title"))
                    }} catch (_:Exception) {}
                    return true
                }
                if (m.message() == "DARWIN_DRM") { message = "This player uses protected media. Direct casting may not be available."; return true }
                return false
            }
        }
    }
    private fun kind(url: String) = when { url.contains(".m3u8",true) -> "HLS stream"; url.contains(".mpd",true) -> "DASH stream"; else -> "Video" }
    private fun addVideo(url: String, kind: String, headers: Map<String,String>,group:String=url,title:String="") {
        if (url.length > 8192 || Uri.parse(url).scheme !in listOf("http","https") || Regex("(?i)(?:^|[/_-])(?:seg(?:ment)?|chunk|frag(?:ment)?|init)[-_\\d.]").containsMatchIn(Uri.parse(url).path.orEmpty())) return
        runOnUiThread {
            if(YoutubeCast.canonicalURL(address)!=null&&kind!="YouTube")return@runOnUiThread
            val idx = found.indexOfFirst { it.group == group }
            if (idx >= 0) found[idx] = found[idx].copy(url=url,headers=found[idx].headers+headers,title=title.ifBlank{found[idx].title})
            else if (found.size < 150) found.add(FoundVideo(url,kind,headers,group,title))
        }
    }
    private fun browse(value: String = address) {
        session.notice=""
        val url = value.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val uri = Uri.parse(url)
        if (uri.host.isNullOrBlank() || uri.userInfo != null) { message="Enter a website address or direct video URL."; return }
        found.clear(); pageOpen=true; tab=0; address=url; browser.loadUrl(url)
        if (Regex("\\.(m3u8|mpd|mp4|webm)([?#]|$)",RegexOption.IGNORE_CASE).containsMatchIn(url)) addVideo(url,kind(url),emptyMap())
        message="Press play on the website, then open Videos."
    }
    private fun connect() {
        try {
            val u = Uri.parse(pairText.trim()); require(u.scheme=="darwincast" && u.host=="pair") { "Scan the Pair Android QR inside Darwin on the TV." }
            require(CastProtocol.localIP(u.getQueryParameter("ip").orEmpty()) && u.getQueryParameter("key").orEmpty().matches(Regex("[a-f0-9]{64}"))) { "This pairing QR is incomplete. Scan Pair Android on your TV again." }
            session.connect(pairText.trim()); pairError=""; pairDialog=false; tab=2
        } catch(e:Exception) { pairError=e.message ?: "Invalid pairing code"; pairDialog=true }
    }
    private fun saveYoutube(play:Boolean=false,queue:Boolean=false) {
        val url=YoutubeCast.canonicalURL(address)?:run{message="Open an individual YouTube video first.";return}
        try{val row=session.catalog.add("youtube",url,pageTitle.ifBlank{"YouTube video"},url);if(queue){session.catalog.enqueue(row.id);message="Added to Up next."}else if(play){playSaved(row,false)}else message="Saved to Library."}catch(e:Exception){message=e.message?:"Could not save video"}
    }
    private fun castYoutube(){if(!tvPaired){pairText=prefs.getString("pair","").orEmpty();pairDialog=true;return};saveYoutube(play=true)}
    private fun saveVideo(v:FoundVideo,play:Boolean=false,queue:Boolean=false){try{val row=session.catalog.add(if(v.kind=="YouTube")"youtube"else"direct",v.url,v.title.ifBlank{pageTitle.ifBlank{Uri.parse(v.url).host?:"Video"}},address,headers=v.headers+mapOf("User-Agent" to browser.settings.userAgentString));session.rememberHeaders(row.id,v.headers+mapOf("User-Agent" to browser.settings.userAgentString)+listOfNotNull(CookieManager.getInstance().getCookie(v.url)?.let{"Cookie" to it}).toMap());if(queue){session.catalog.enqueue(row.id);message="Added to Up next."}else if(play)playSaved(row,false)else message="Saved to Library."}catch(e:Exception){message=e.message?:"Could not save video"}}
    private fun cast(v:FoundVideo){if(!tvPaired){pairText=prefs.getString("pair","").orEmpty();pairDialog=true;return};saveVideo(v,play=true)}
    private fun playSaved(row:SavedVideo,restart:Boolean){pauseBrowse();session.play(row,restart);tab=2}
    private fun pauseBrowse() { if (::browser.isInitialized) browser.evaluateJavascript("window.__darwinPauseBrowse && window.__darwinPauseBrowse()", null) }
    override fun onPause() { pauseBrowse(); super.onPause() }
    @Composable private fun App() {
        LaunchedEffect(tab) { if (tab != 0) pauseBrowse() }
        Surface(Modifier.fillMaxSize(),color=Ink) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val rail=maxWidth>=600.dp
                val labels=if(rail)listOf("Library","Browse","Videos","Remote","Files","Up next","Health","Logs","Subtitles","TV browser","Mac tools","Settings")else listOf("Library","Browse","Remote","Files","More")
                val sections=if(rail)listOf(4,0,1,2,3,5,6,7,8,9,11,12)else listOf(4,0,2,3,10)
                val icons=if(rail)listOf(R.drawable.ic_files,R.drawable.ic_browse,R.drawable.ic_videos,R.drawable.ic_remote,R.drawable.ic_files,R.drawable.ic_videos,R.drawable.ic_tune,R.drawable.ic_tune,R.drawable.ic_captions,R.drawable.ic_browse,R.drawable.ic_tune,R.drawable.ic_tune)else listOf(R.drawable.ic_videos,R.drawable.ic_browse,R.drawable.ic_remote,R.drawable.ic_files,R.drawable.ic_tune)
                Row(Modifier.fillMaxSize()) {
                    if(rail)NavigationRail(containerColor=Color(0xFF152229),modifier=Modifier.fillMaxHeight().verticalScroll(rememberScrollState())){
                        Spacer(Modifier.height(18.dp))
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.darwin_icon),null,modifier=Modifier.size(44.dp).then(Modifier.clip(RoundedCornerShape(13.dp))))
                        Spacer(Modifier.height(24.dp))
                        labels.forEachIndexed{index,label->NavigationRailItem(selected=tab==sections[index],onClick={tab=sections[index]},icon={Icon(androidx.compose.ui.res.painterResource(icons[index]),null)},label={Text(L(label))})}
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(22.dp,12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                if(!rail)androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.darwin_icon),null,modifier=Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)))
                                Text(L("darwin"),fontSize=27.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
                            }
                            OutlinedButton(onClick={pairText=prefs.getString("pair","").orEmpty();pairDialog=true}){Text(L(if(tvPaired)"Connected"else "Connect TV"),fontSize=12.sp)}
                        }
                        Box(Modifier.weight(1f)){when(tab){0->BrowserScreen();1->VideosScreen();2->RemoteScreen();3->FilesScreen();4->LibraryPanel(session,{tab=0},{if(!tvPaired){pairText=prefs.getString("pair","").orEmpty();pairDialog=true}else moviePicker.launch(arrayOf("*/*"))},::playSaved);5->QueuePanel(session);6->HealthPanel(session){tab=7};7->LogsPanel(session){exportText=it;logExporter.launch("Darwin-logs.txt")};8->SubtitlePage(session,{subtitlePicker.launch(arrayOf("*/*"))},{subtitleDialog=true},{tab=11});9->TVBrowserPanel(session.controller){tab=11};11->MacConnectionPanel(session.controller);12->SettingsScreen();else->ToolsScreen()}}
                        if((session.notice.ifBlank{message}).isNotBlank() && !(tab==2 && session.notice.isBlank() && message=="Choose a video. Make it a big-screen night."))Text(L(session.notice.ifBlank{message}),Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=8.dp),fontSize=12.sp,color=Muted,maxLines=2)
                        if(!rail)NavigationBar(containerColor=Color(0xFF152229),windowInsets=WindowInsets(0)){
                            labels.forEachIndexed{index,label->NavigationBarItem(selected=tab==sections[index],onClick={tab=sections[index]},icon={Icon(androidx.compose.ui.res.painterResource(icons[index]),null)},label={Text(L(label))})}
                        }
                    }
                }
            }
        }
        if(pairDialog) AlertDialog(onDismissRequest={pairDialog=false},title={Text(L("Connect to Darwin"))},text={Column {
            Text(L("Open Darwin on your TV, choose Pair Android, then scan its QR code. Hold your phone upright and keep both devices on the same Wi-Fi."),color=Muted)
            Spacer(Modifier.height(18.dp)); Button(onClick={pairError="";scanner.launch(ScanOptions().setCaptureActivity(PortraitCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(L("Hold upright · Scan the Pair Android QR on your TV")).setBeepEnabled(false).setOrientationLocked(true))},modifier=Modifier.fillMaxWidth()){Text(L("Scan TV QR"))}
            OutlinedTextField(pairText,{pairText=it;pairError=""},label={Text(L("Or paste pairing link"))},isError=pairError.isNotBlank(),supportingText={if(pairError.isNotBlank())Text(L(pairError))},modifier=Modifier.fillMaxWidth().padding(top=14.dp),maxLines=3)
        }},confirmButton={TextButton(onClick={connect()}){Text(L("Connect"))}},dismissButton={TextButton(onClick={pairDialog=false}){Text(L("Cancel"))}})
        if(subtitleDialog) SubtitleControls()
        if(tracksDialog) TrackControls()
    }
    @Composable private fun BrowserScreen() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal=20.dp).padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(address,{address=it},placeholder={Text(L("Paste a website or video link"))},singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp))
                Spacer(Modifier.width(8.dp));FilledTonalButton(onClick={browse()}){Text(L("Go"))}
            }
            if(YoutubeCast.canonicalURL(address)!=null)Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={castYoutube()},enabled=!localBusy&&!session.busy){Text(L(if(session.busy)"Connecting…"else "Cast YouTube"))};TextButton(onClick={saveYoutube()}){Text(L("Save"))};TextButton(onClick={saveYoutube(queue=true)}){Text(L("Queue"))}}
            TextButton(onClick={tab=1},modifier=Modifier.padding(horizontal=12.dp)){Text(L("Detected videos · ${found.size}"))}
            if(pageOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={if(browser.canGoBack())browser.goBack()}){Text(L("← Back"))}
                    TextButton(onClick={browser.reload()}){Text(L("Reload"))}
                    TextButton(onClick={pageOpen=false;browser.loadUrl("about:blank")}){Text(L("Home"))}
                }
                AndroidView(factory={ (browser.parent as? ViewGroup)?.removeView(browser);browser },modifier=Modifier.fillMaxSize())
            } else Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Spacer(Modifier.height(20.dp)); Text(L("Find it.\nCast it.\nSettle in."),fontSize=48.sp,lineHeight=51.sp,fontWeight=FontWeight.Bold,letterSpacing=(-2).sp)
                Spacer(Modifier.height(16.dp));Text(L("Open a video website, press play, and send the video straight to your TV."),color=Muted,fontSize=16.sp,lineHeight=24.sp)
                Spacer(Modifier.height(28.dp))
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF263D35)),shape=RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(22.dp)) { Text(L("YOUR PHONE IS THE REMOTE"),color=Mint,fontSize=11.sp,letterSpacing=1.sp);Spacer(Modifier.height(12.dp));Text(L("Same Wi-Fi.\nMore room to watch."),fontSize=24.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(14.dp));Text(L("Seek, pause, and add your own subtitles without covering the screen."),color=Muted,fontSize=14.sp);Spacer(Modifier.height(12.dp));TextButton(onClick={pairText=prefs.getString("pair","").orEmpty();pairDialog=true},contentPadding=PaddingValues(0.dp)){Text(L("Connect your TV  →"),color=Mint)} }
                }
                Spacer(Modifier.height(20.dp));Text(L("TRY A VIDEO"),color=Muted,fontSize=11.sp,letterSpacing=1.sp)
                TextButton(onClick={browse("https://www.w3schools.com/html/mov_bbb.mp4")},contentPadding=PaddingValues(vertical=8.dp)){Text(L("Big Buck Bunny · sample clip  ↗"))}
                Text(L("No account. No subscription. Open source."),color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=12.dp))
            }
        }
    }
    @Composable private fun VideosScreen() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(L("Ready for the big screen"),fontSize=28.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp))
            Text(L("Videos found on the page you're browsing. Pick a stream after pressing play on its website."),color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=18.dp)) { Column(Modifier.weight(1f)){Text(L("Relay through this phone"));Text(L("Helps streams that require website headers."),fontSize=12.sp,color=Muted)};Text(L("On"),color=Mint) }
            if(found.isEmpty()) Card { Column(Modifier.padding(24.dp)){Text(L("No videos detected yet"),fontSize=20.sp);Spacer(Modifier.height(12.dp));Text(L("Open a website in Browse and start its player. Protected and some embedded players cannot be cast."),color=Muted);TextButton(onClick={tab=0}){Text(L("Browse a website →"))}} }
            found.forEachIndexed { i,v -> Card(Modifier.fillMaxWidth().padding(bottom=12.dp),shape=RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) { Text(L("${i+1}. ${L(v.kind)}"),fontWeight=FontWeight.Bold);Text(v.title.ifBlank{Uri.parse(v.url).host.orEmpty()},fontSize=13.sp,color=Muted);Text(Uri.parse(v.url).lastPathSegment.orEmpty().take(90),fontSize=12.sp,color=Muted,maxLines=2);Row{Button(onClick={cast(v)},modifier=Modifier.padding(top=10.dp)){Text(L("Cast to TV"))};TextButton(onClick={saveVideo(v)}){Text(L("Save"))};TextButton(onClick={saveVideo(v,queue=true)}){Text(L("Queue"))}}}} }
        }
    }
    @Composable private fun RemoteScreen() {
        DarwinRemotePanel(session.paired,session.state,session.connection,{op,data->session.command(op,data)},
            {pairText=prefs.getString("pair","").orEmpty();pairDialog=true},
            {tracksDialog=true},{tab=8},
            {session.stop()})
    }
    @Composable private fun FilesScreen() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(L("Your files. Your screen."),fontSize=30.sp,fontWeight=FontWeight.Bold)
            Text(L("Play a movie saved on your phone. MP4, MKV, AVI, MOV, and more."),color=Muted,modifier=Modifier.padding(vertical=16.dp))
            Button(onClick={if(!tvPaired){pairText=prefs.getString("pair","").orEmpty();pairDialog=true}else moviePicker.launch(arrayOf("*/*"))},enabled=!localBusy,modifier=Modifier.fillMaxWidth()){Text(L(if(localBusy)"Inspecting movie…"else "Choose video file"))}
            if(localBusy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(vertical=16.dp))
            localDescription?.let { d -> val info=d.getJSONObject("local")
                Card(Modifier.fillMaxWidth().padding(top=24.dp),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(22.dp)){
                    Text(d.optString("title"),fontSize=22.sp,fontWeight=FontWeight.Bold)
                    Text(L("${"%.2f".format(info.optLong("size")/1073741824.0)} GB · ${clock(info.optDouble("duration").toFloat())}"),color=Muted,modifier=Modifier.padding(vertical=10.dp))
                    Text(L("Original quality · streams in small chunks"),color=Mint,fontSize=13.sp)
                    Button(onClick={selectedFileId?.let{session.catalog.item(it)}?.let{playSaved(it,false)}},enabled=tvPaired&&!localBusy&&!session.busy,modifier=Modifier.fillMaxWidth().padding(top=16.dp)){Text(L("Play on TV"))};TextButton(onClick={selectedFileId?.let{session.catalog.enqueue(it)}}){Text(L("Add to Up next"))}
                }}
            }
            Spacer(Modifier.height(24.dp));Text(L("Large files stay on your phone"),fontWeight=FontWeight.Bold)
            Text(L("Darwin reads only the requested bytes—even for files over 100 GB. Keep the phone on Wi-Fi while watching. If the TV cannot play the original, compatibility playback runs on this phone."),color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=10.dp))
            Text(L("High-bitrate originals need a fast connection. Video conversion may be slower on some phones. HDR is preserved in original playback; HDR-to-SDR tone mapping is not included yet."),color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
        }
    }
    @Composable private fun ToolsScreen(){
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            PanelTitle("More from Darwin","Everything for your TV, in one place.")
            for((id,label) in listOf(1 to "Detected videos",5 to "Up next",6 to "Playback health",7 to "TV & phone logs",8 to "Subtitles",9 to "TV browser",11 to "Mac tools connection",12 to "Settings"))OutlinedButton(onClick={tab=id},modifier=Modifier.fillMaxWidth().height(58.dp)){Text(L(label))}
            Text(L("Darwin for Android · phone casting works directly over Wi-Fi"),fontSize=12.sp,color=Muted)
        }
    }
    @Composable private fun SettingsScreen(){
        val selected=AppLanguage.current(this)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            PanelTitle("Settings","Device and app preferences.")
            Text(L("App language"),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
            Text(L("Choose the language for every Darwin screen. Your choice is saved on this phone."),color=Muted)
            for((code,label) in listOf("en" to "English","es-MX" to "Español · México")){
                OutlinedButton(onClick={AppLanguage.choose(this@MainActivity,code)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){
                    RadioButton(selected==code,onClick=null);Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f))
                }
            }
            Text(L("Language changes do not stop the video on your TV."),fontSize=13.sp,color=Muted)
        }
    }
    @Composable private fun TrackControls() {
        val local=tvState.optJSONObject("local")
        AlertDialog(onDismissRequest={tracksDialog=false},title={Text(L("Audio, subtitles & quality"))},text={Column(Modifier.verticalScroll(rememberScrollState())){
            if(local==null){
                Text(L("Video quality"),fontWeight=FontWeight.Bold)
                TextButton(onClick={tv.command("quality_set",JSONObject().put("quality",0))}){Text(L("Auto"))}
                val options=CastService.youtube?.qualities?:CastService.relay?.qualities?:(tvState.optJSONArray("qualities")?.let{a->(0 until a.length()).map{a.getInt(it)}}?:emptyList())
                options.forEach { q -> TextButton(onClick={tv.command("quality_set",JSONObject().put("quality",q))}){Text(L("${q}p"))} }
                if(options.isEmpty())Text(L("This source currently offers one quality."),color=Muted)
            }
            else {
                val tracks=local.getJSONArray("tracks")
                for(kind in listOf("audio","subtitle")){
                    Text(L(if(kind=="audio")"Audio language"else "Embedded subtitles"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                    if(kind=="subtitle")TextButton(onClick={tv.command("local_track",JSONObject().put("kind",kind).put("track",-1))}){Text(L("Off"))}
                    for(i in 0 until tracks.length()) {val t=tracks.getJSONObject(i);if(t.optString("type")==kind)TextButton(onClick={tv.command("local_track",JSONObject().put("kind",kind).put("track",t.getInt("id")))}){Text("${t.optString("language","und")} · ${t.optString("title").ifBlank{t.optString("codec")}}${if(local.optInt(kind)==t.getInt("id")) " ✓" else ""}")}}
                }
                Text(L("Video quality"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                val choices=if(local.optBoolean("remote"))listOf(0)+(local.optJSONArray("qualities")?.let{a->(0 until a.length()).map{a.getInt(it)}}?:emptyList())else listOf(0,-1,2160,1080,720,480).filter{it<=0||it<=local.optJSONObject("video")!!.optInt("height")}
                for(q in choices)TextButton(onClick={tv.command("local_quality",JSONObject().put("quality",q))}){Text(L(if(q==0)"Auto · fit connection"else if(q== -1)"Original quality"else "${q}p"))}
            }
        }},confirmButton={TextButton(onClick={tracksDialog=false}){Text(L("Done"))}})
    }
    @Composable private fun SubtitleControls() {
        var font by remember{mutableStateOf(prefs.getString("font","sans")!!)}
        var subtitleLanguage by remember{mutableStateOf(prefs.getString("subtitleLanguage","ea")!!)}
        var subtitleBackground by remember{mutableStateOf(prefs.getBoolean("subtitleBackground",true))}
        var size by remember{mutableFloatStateOf(prefs.getFloat("fontSize",40f))}
        var delay by remember{mutableFloatStateOf(tvState.optJSONObject("subtitles")?.optDouble("offset",0.0)?.toFloat()?:0f)}
        var rate by remember{mutableFloatStateOf(tvState.optJSONObject("subtitles")?.optDouble("rate",1.0)?.toFloat()?:1f)}
        fun appearance(){prefs.edit().putString("font",font).putFloat("fontSize",size).putString("subtitleLanguage",subtitleLanguage).putBoolean("subtitleBackground",subtitleBackground).apply();tv.command("subtitle_style",JSONObject().put("settings",JSONObject().put("font",font).put("size",size.toInt()).put("background",subtitleBackground).put("language",subtitleLanguage)))}
        AlertDialog(onDismissRequest={subtitleDialog=false},title={Text(L("Subtitles"))},text={Column(Modifier.verticalScroll(rememberScrollState())){
            Text(L("Add an SRT or WebVTT file from your phone."),color=Muted)
            Button(onClick={subtitlePicker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth().padding(vertical=12.dp)){Text(L("Choose subtitle file"))}
            Row{FilterChip(subtitleLanguage=="ea",{subtitleLanguage="ea";appearance()},label={Text(L("Spanish · LATAM"))});FilterChip(subtitleLanguage=="en",{subtitleLanguage="en";appearance()},label={Text(L("English"))})};Row{Text(L("Subtitle background"),Modifier.weight(1f));Switch(subtitleBackground,{subtitleBackground=it;appearance()})}
            Text(L("Font · saved for next time"),fontSize=13.sp,color=Muted)
            Row { listOf("sans" to "Sans","serif" to "Serif","mono" to "Mono").forEach{(id,label)->FilterChip(selected=font==id,onClick={font=id;appearance()},label={Text(L(label))},modifier=Modifier.padding(end=4.dp))} }
            Text(L("Size · ${size.toInt()} px"));Slider(size,{size=it},onValueChangeFinished={appearance()},valueRange=24f..72f)
            Text(L("Subtitle delay · ${"%.1f".format(delay)} seconds"))
            Row {TextButton(onClick={delay-=0.5f;tv.command("subtitle_config",JSONObject().put("offset",delay.toDouble()))}){Text(L("Earlier −0.5s"))};TextButton(onClick={delay+=0.5f;tv.command("subtitle_config",JSONObject().put("offset",delay.toDouble()))}){Text(L("Later +0.5s"))}}
            Text(L("Subtitle speed · ${"%.1f".format(rate*100)}%"))
            Slider(rate,{rate=it},onValueChangeFinished={tv.command("subtitle_config",JSONObject().put("rate",rate.toDouble()))},valueRange=.5f..2f)
            Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={rate=1f;delay=0f;tv.command("subtitle_config",JSONObject().put("rate",1).put("offset",0))}){Text(L("Reset timing"))};TextButton(onClick={tv.command("subtitle_config",JSONObject().put("enabled",!(tvState.optJSONObject("subtitles")?.optBoolean("enabled",true)?:true)))}){Text(L(if(tvState.optJSONObject("subtitles")?.optBoolean("enabled",true)!=false)"Hide subtitles"else"Show subtitles"))}}
        }},confirmButton={TextButton(onClick={subtitleDialog=false}){Text(L("Done"))}})
    }
    private fun clock(v: Float):String {val s=v.toInt().coerceAtLeast(0);return if(s>=3600)"${s/3600}:${"%02d".format(s/60%60)}:${"%02d".format(s%60)}" else "${s/60}:${"%02d".format(s%60)}"}
    override fun onDestroy(){ if(!isChangingConfigurations)session.onChange=null;if(::browser.isInitialized)browser.destroy();super.onDestroy() }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
    while(true){val n=read(buf);if(n<0)break;require(out.size()+n<=limit){"File is too large"};out.write(buf,0,n)}
    return out.toByteArray()
}
