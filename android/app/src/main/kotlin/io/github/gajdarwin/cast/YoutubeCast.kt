package io.github.gajdarwin.cast

import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.TimeUnit

/** Keep one decoder family per adaptation set; prefer widely supported AVC. */
internal fun <T> youtubeVideoSelection(streams:List<T>,quality:Int,height:(T)->Int,codec:(T)->String):List<T> {
    if(streams.isEmpty())return emptyList()
    val candidates=if(quality==0)streams.filter{height(it)<=1080}.ifEmpty{listOf(streams.minBy{height(it)})}else streams.filter{height(it)==quality}
    val avc=candidates.filter{codec(it).lowercase().startsWith("avc")}
    if(avc.isNotEmpty())return avc
    val family=candidates.firstOrNull()?.let{codec(it).lowercase().substringBefore('.')}?:return emptyList()
    return candidates.filter{codec(it).lowercase().substringBefore('.')==family}
}

/** Public YouTube extraction. Media bytes stay on the phone-to-TV Wi-Fi path. */
class YoutubeCast(private val tvIP:String):NanoHTTPD(0) {
    private val token=CastProtocol.token()
    private var phone=""
    private var duration=0L
    private var title="YouTube"
    private var video:List<VideoStream> = emptyList()
    private lateinit var audio:AudioStream
    private var quality=0
    private val relay=MediaRelay(tvIP)
    val bytesSent get()=relay.bytesSent
    fun bufferStats()=relay.bufferStats()
    private val links=mutableMapOf<String,String>()
    private val base get()="http://$phone:$listeningPort/$token"
    fun chooseQuality(value:Int){quality=if(value in qualities)value else 0}
    val qualityUrl get()="$base/quality"
    val qualities get()=video.map{it.height}.distinct().sortedDescending()
    companion object {
        private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
        private val downloader=object:Downloader(){
            override fun execute(request:Request):org.schabi.newpipe.extractor.downloader.Response {
                val builder=okhttp3.Request.Builder().url(request.url())
                request.headers().forEach{(key,values)->values.forEach{builder.addHeader(key,it)}}
                val body=request.dataToSend()?.toRequestBody()
                builder.method(request.httpMethod(),body)
                client.newCall(builder.build()).execute().use{r->
                    val text=r.body?.byteStream()?.use{String(it.readBytesLimited(12*1024*1024),Charsets.UTF_8)}?:""
                    return org.schabi.newpipe.extractor.downloader.Response(r.code,r.message,r.headers.toMultimap(),text,r.request.url.toString())
                }
            }
        }
        @Synchronized private fun initialize(){NewPipe.init(downloader)}
        fun canonicalURL(value:String):String? = try {
            val u=Uri.parse(value); val host=u.host?.lowercase(); val parts=u.pathSegments
            val allowed=listOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be","www.youtu.be","youtube-nocookie.com","www.youtube-nocookie.com")
            if(u.scheme !in listOf("http","https") || host !in allowed || u.userInfo != null) null else {
                val id=when {host in listOf("youtu.be","www.youtu.be")->parts.firstOrNull();u.path=="/watch"->u.getQueryParameter("v");parts.firstOrNull() in listOf("shorts","live","embed")->parts.getOrNull(1);else->null}
                if(id?.matches(Regex("[A-Za-z0-9_-]{11}"))==true) "https://www.youtube.com/watch?v=$id" else null
            }
        } catch(_:Exception){null}
        fun isYoutube(value:String):Boolean=Uri.parse(value).host?.lowercase() in listOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be","www.youtu.be")
    }
    fun prepare(url:String,phoneIP:String):JSONObject {
        val videoUrl=canonicalURL(url) ?: error("Open an individual YouTube video first.");require(CastProtocol.localIP(phoneIP));phone=phoneIP
        initialize()
        val info=StreamInfo.getInfo(ServiceList.YouTube,videoUrl)
        title=info.name;duration=info.duration;require(duration>0){"Live YouTube casting is not supported by this version. Try a recorded public video."}
        video=info.videoOnlyStreams.filter{it.content.startsWith("https://")&&it.format?.mimeType=="video/mp4"&&it.initStart>=0&&it.initEnd>=it.initStart&&it.indexStart>=0&&it.indexEnd>it.indexStart&&!it.codec.isNullOrBlank()&&it.height>0}
        audio=info.audioStreams.filter{it.content.startsWith("https://")&&it.format?.mimeType in listOf("audio/mp4","audio/x-m4a")&&it.initStart>=0&&it.initEnd>=it.initStart&&it.indexStart>=0&&it.indexEnd>it.indexStart&&!it.codec.isNullOrBlank()}.maxByOrNull{it.averageBitrate}?:error("No compatible YouTube audio stream was found.")
        require(video.isNotEmpty()){"No compatible public YouTube video streams were found."}
        fun validate(value:String){val u=Uri.parse(value);require(u.scheme=="https"&&u.host?.endsWith(".googlevideo.com")==true){"Unexpected video host"}}
        video.forEach{validate(it.content)};validate(audio.content)
        val headers=mapOf("User-Agent" to "Mozilla/5.0","Referer" to "https://www.youtube.com/")
        links[audio.content]=relay.begin(phoneIP,audio.content,headers,recorded=true)
        video.forEach{links[it.content]=relay.add(it.content,headers,recorded=true)}
        start(SOCKET_READ_TIMEOUT,true)
        return JSONObject().put("url","$base/stream.mpd").put("title",title).put("qualityUrl",qualityUrl)
    }
    private fun xml(s:String)=s.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;")
    private fun manifest():String {
        val selected=youtubeVideoSelection(video,quality,{it.height},{it.codec.orEmpty()})
        fun segment(url:String,begin:Int,end:Int,first:Int,last:Int)="<BaseURL>${xml(links[url]!!)}</BaseURL><SegmentBase indexRange=\"$first-$last\"><Initialization range=\"$begin-$end\"/></SegmentBase>"
        val vids=selected.joinToString(""){v->"<Representation id=\"v${v.itag}\" bandwidth=\"${v.bitrate.coerceAtLeast(100000)}\" width=\"${v.width}\" height=\"${v.height}\" codecs=\"${xml(v.codec.orEmpty())}\">${segment(v.content,v.initStart,v.initEnd,v.indexStart,v.indexEnd)}</Representation>"}
        val aud="<Representation id=\"a${audio.itag}\" bandwidth=\"${audio.bitrate.coerceAtLeast(128000)}\" codecs=\"${xml(audio.codec.orEmpty())}\">${segment(audio.content,audio.initStart,audio.initEnd,audio.indexStart,audio.indexEnd)}</Representation>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT${duration}S\" minBufferTime=\"PT2S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"><Period duration=\"PT${duration}S\"><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" segmentAlignment=\"true\">$vids</AdaptationSet><AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\">$aud</AdaptationSet></Period></MPD>"
    }
    override fun serve(s:IHTTPSession):NanoHTTPD.Response {
        if(s.remoteIpAddress !in listOf(tvIP,"127.0.0.1","::1")||!s.uri.startsWith("/$token/"))return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,"text/plain","Session unavailable")
        val r=try{when{
            s.method==Method.OPTIONS->newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT,"text/plain","")
            s.uri=="/$token/stream.mpd"&&s.method in listOf(Method.GET,Method.HEAD)->newFixedLengthResponse(NanoHTTPD.Response.Status.OK,"application/dash+xml",manifest())
            s.uri=="/$token/quality"->{
                if(s.method==Method.POST){val n=s.headers["content-length"]?.toIntOrNull()?:0;require(n in 1..2048);val b=ByteArray(n);var at=0;while(at<n){val count=s.inputStream.read(b,at,n-at);require(count>0);at+=count};val q=JSONObject(String(b,Charsets.UTF_8)).getInt("quality");require(q==0||q in qualities);quality=q}
                newFixedLengthResponse(NanoHTTPD.Response.Status.OK,"application/json",JSONObject().put("url","$base/stream.mpd").put("qualities",JSONArray(qualities)).put("quality",quality).toString())
            }
            else->newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND,"text/plain","")
        }}catch(_:Exception){newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,"text/plain","Quality unavailable")}
        r.addHeader("Access-Control-Allow-Origin","null");r.addHeader("Access-Control-Allow-Methods","GET, HEAD, POST, OPTIONS");r.addHeader("Access-Control-Allow-Headers","Content-Type");r.addHeader("Cache-Control","no-store");return r
    }
    override fun stop(){relay.stop();super.stop()}
}
