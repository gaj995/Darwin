package io.github.gajdarwin.cast

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Serves only a user-selected SAF file, with independent seekable file descriptors. */
class LocalMovie(private val context:Context,private val uri:Uri,private val tvIP:String):NanoHTTPD(0){
    @Volatile var bytesSent=0L;private set
    private val token=CastProtocol.token();private val native=NativeMedia(context)
    private var phoneIP="";private var info=JSONObject();private var name="Movie";private var size=0L
    private var automatic=true;private var networkMbps=0.0
    private var audio=-1;private var subtitle=-1;private var start=0.0;private var mode="original";private var revision=0;private var quality=0
    private val processes=ConcurrentHashMap.newKeySet<Process>();private var stopped=false
    private val base get()="http://$phoneIP:$listeningPort/local-media/$token"
    private val sourceURL get()="http://127.0.0.1:$listeningPort/local-media/$token/original"
    fun prepare(ip:String):JSONObject {
        require(CastProtocol.localIP(ip)){"Connect the phone to Wi-Fi."};phoneIP=ip
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){val ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);val si=c.getColumnIndex(OpenableColumns.SIZE);if(ni>=0)name=c.getString(ni).take(240);if(si>=0&&!c.isNull(si))size=c.getLong(si)}}
        context.contentResolver.openAssetFileDescriptor(uri,"r")!!.use{fd->
            if(fd.length>0)size=fd.length
            require(size>0){"Choose a downloaded file with a known size."}
            try{Os.lseek(fd.fileDescriptor,fd.startOffset,OsConstants.SEEK_SET)}catch(_:Exception){error("This provider cannot seek. Choose the downloaded file from device storage.")}
        }
        start(SOCKET_READ_TIMEOUT,true)
        try{info=native.probe(sourceURL)}catch(e:Exception){stop();throw e}
        val tracks=info.getJSONArray("tracks");val audioTracks=(0 until tracks.length()).map{tracks.getJSONObject(it)}.filter{it.optString("type")=="audio"}
        audio=(audioTracks.find{it.optBoolean("default")}?:audioTracks.firstOrNull())?.getInt("id")?:-1
        return descriptor()
    }
    @Synchronized fun descriptor():JSONObject {
        val metadata=JSONObject(info.toString()).put("base",base).put("size",size).put("audio",audio).put("subtitle",subtitle).put("mode",mode).put("start",start).put("quality",quality).put("revision",revision).put("automatic",automatic).put("networkMbps",networkMbps).put("streamAudio",org.json.JSONArray((0 until info.getJSONArray("tracks").length()).map{info.getJSONArray("tracks").getJSONObject(it)}.filter{it.optString("type")=="audio"}.take(16).map{it.getInt("id")}))
        return JSONObject().put("title",name).put("url",if(mode=="original")"$base/original.${name.substringAfterLast('.',"mp4").lowercase().filter{it.isLetterOrDigit()}.take(8)}" else "$base/stream/$revision.mp4").put("local",metadata)
    }
    @Synchronized fun configure(v:JSONObject):JSONObject {
        require(!stopped){"Session ended"}
        if(v.optString("op")=="close"){stop();return JSONObject()}
        val tracks=info.getJSONArray("tracks")
        for((field,type) in listOf("audio" to "audio","subtitle" to "subtitle"))if(v.has(field)){
            val id=v.getInt(field);require(id == -1||(0 until tracks.length()).any{val t=tracks.getJSONObject(it);t.optInt("id")==id&&t.optString("type")==type}){"Invalid track"}
            if(field=="audio")audio=id else subtitle=id
        }
        if(v.optBoolean("metadataOnly"))return descriptor()
        var nextMode=v.optString("mode",mode);require(nextMode in listOf("original","compatible","encode"))
        var nextQuality=v.optInt("quality",quality);require(nextQuality in listOf(-1,0,480,720,1080,2160))
        val t=v.optDouble("time",0.0);require(t.isFinite());start=t.coerceIn(0.0,info.getDouble("duration")-.1)
        automatic=v.optBoolean("automatic",automatic)
        if(v.has("networkMbps")){val rate=v.getDouble("networkMbps");require(rate.isFinite()&&rate in .1..10000.0);networkMbps=rate}
        if(automatic&&networkMbps>0){val sourceMbps=size*8/info.getDouble("duration")/1000000;if(sourceMbps>networkMbps*.65){nextMode="compatible";nextQuality=minOf(info.getJSONObject("video").optInt("height",1080),if(networkMbps>=7)1080 else if(networkMbps>=4)720 else 480)}else{nextMode="original";nextQuality=0}}
        mode=nextMode;quality=nextQuality;revision++;kill();return descriptor()
    }
    private fun openAt(position:Long,count:Boolean=true):InputStream{
        val afd=context.contentResolver.openAssetFileDescriptor(uri,"r")?:error("File access was revoked")
        try{Os.lseek(afd.fileDescriptor,afd.startOffset+position,OsConstants.SEEK_SET)}catch(e:Exception){afd.close();throw e}
        return object:FilterInputStream(ParcelFileDescriptor.AutoCloseInputStream(afd.parcelFileDescriptor)){override fun read(b:ByteArray,offset:Int,length:Int):Int{val n=super.read(b,offset,length);if(count&&n>0)bytesSent+=n;return n};override fun read():Int{val n=super.read();if(count&&n>=0)bytesSent++;return n}}
    }
    private fun reply(status:Response.Status,text:String,mime:String="text/plain"):Response=newFixedLengthResponse(status,mime,text)
    override fun serve(s:IHTTPSession):Response {
        val prefix="/local-media/$token/"
        if(s.remoteIpAddress !in listOf(tvIP,"127.0.0.1","::1")||!s.uri.startsWith(prefix)||stopped)return reply(Response.Status.FORBIDDEN,"Session unavailable")
        val path=s.uri.removePrefix(prefix)
        val result=try{
            when{
                s.method==Method.OPTIONS->reply(Response.Status.NO_CONTENT,"")
                s.method==Method.POST&&path=="configure"->{
                    require(s.headers["content-type"]?.startsWith("application/json")==true);val n=s.headers["content-length"]?.toIntOrNull()?:0;require(n in 1..8192)
                    val b=ByteArray(n);var read=0;while(read<n){val c=s.inputStream.read(b,read,n-read);require(c>0);read+=c}
                    reply(Response.Status.OK,configure(JSONObject(String(b,Charsets.UTF_8))).toString(),"application/json")
                }
                s.method !in listOf(Method.GET,Method.HEAD)->reply(Response.Status.METHOD_NOT_ALLOWED,"")
                path=="probe.bin"->newFixedLengthResponse(Response.Status.OK,"application/octet-stream",java.io.ByteArrayInputStream(ByteArray(2*1024*1024)),2*1024*1024L)
                (path=="original"||path.startsWith("original."))->{
                    val range=try{HttpRange.parse(s.headers["range"],size)}catch(_:Exception){return reply(Response.Status.RANGE_NOT_SATISFIABLE,"").apply{addHeader("Content-Range","bytes */$size")}}
                    val mime=context.contentResolver.getType(uri)?:"application/octet-stream"
                    val input=if(s.method==Method.HEAD)java.io.ByteArrayInputStream(ByteArray(0)) else openAt(range.start,s.remoteIpAddress==tvIP)
                    newFixedLengthResponse(if(range.partial)Response.Status.PARTIAL_CONTENT else Response.Status.OK,mime,input,range.length).apply{addHeader("Accept-Ranges","bytes");if(range.partial)addHeader("Content-Range","bytes ${range.start}-${range.end}/$size")}
                }
                Regex("stream/\\d+\\.mp4").matches(path)->{
                    require(path=="stream/$revision.mp4"){"Expired stream"}
                    if(s.method==Method.HEAD)newChunkedResponse(Response.Status.OK,"video/mp4",java.io.ByteArrayInputStream(ByteArray(0))).apply{addHeader("Accept-Ranges","none")} else {
                        kill();val process=native.start("ffmpeg",native.compatibility(sourceURL,info,start,audio,subtitle,mode=="encode",quality));processes.add(process)
                        val input=object:FilterInputStream(process.inputStream){override fun read(b:ByteArray,offset:Int,length:Int):Int{val n=super.read(b,offset,length);if(n>0)bytesSent+=n;return n};override fun close(){super.close();process.destroy();processes.remove(process)}}
                        newChunkedResponse(Response.Status.OK,"video/mp4",input).apply{addHeader("Accept-Ranges","none")}
                    }
                }
                Regex("captions/\\d+/\\d+\\.json").matches(path)->{
                    val parts=path.split('/');val id=parts[1].toInt();val at=parts[2].substringBefore('.').toInt();val tracks=info.getJSONArray("tracks")
                    require((0 until tracks.length()).any{val t=tracks.getJSONObject(it);t.optInt("id")==id&&t.optBoolean("text")});require(at>=0&&at<=info.getDouble("duration"))
                    val beginning=(floor(at/30.0)*30-30).coerceAtLeast(0.0)
                    val vtt=native.output("ffmpeg",listOf("-v","error","-nostdin","-ss",beginning.toString(),"-i",sourceURL,"-t","90","-map","0:$id","-f","webvtt","pipe:1"),1048576,25)
                    reply(Response.Status.OK,JSONObject().put("start",beginning).put("vtt",vtt).toString(),"application/json")
                }
                else->reply(Response.Status.NOT_FOUND,"Not found")
            }
        }catch(e:Exception){reply(Response.Status.BAD_REQUEST,e.message?.take(200)?:"Could not serve the local file")}
        result.addHeader("Access-Control-Allow-Origin","null");result.addHeader("Access-Control-Allow-Methods","GET, HEAD, POST, OPTIONS");result.addHeader("Access-Control-Allow-Headers","Content-Type, Range");result.addHeader("Cache-Control","no-store")
        return result
    }
    private fun kill(){processes.forEach{it.destroy()};processes.clear()}
    override fun stop(){stopped=true;kill();super.stop()}
}
