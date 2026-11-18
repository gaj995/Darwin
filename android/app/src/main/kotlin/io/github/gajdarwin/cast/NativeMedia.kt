package io.github.gajdarwin.cast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NativeMedia(context: Context) {
    private val directory=context.applicationInfo.nativeLibraryDir
    fun start(tool: String, args: List<String>):Process {
        require(tool in listOf("ffmpeg","ffprobe"))
        val binary=File(directory,"lib$tool.so")
        require(binary.canExecute()){"Compatibility tools are missing for this device. Install the complete Darwin APK."}
        val p=ProcessBuilder(listOf(binary.absolutePath)+args).start()
        thread(isDaemon=true,name="darwin-media-log"){var tail="";p.errorStream.bufferedReader().use{reader->val buffer=CharArray(1024);while(true){val n=reader.read(buffer);if(n<0)break;tail=(tail+String(buffer,0,n)).takeLast(4096)}};val exit=p.waitFor();if(exit!=0&&exit !in listOf(143,255))CastService.logIfRunning("media","error",tail.ifBlank{"$tool exited with code $exit"})}
        return p
    }
    fun output(tool: String,args: List<String>,limit: Int=2097152,timeout:Long=40):String {
        val p=start(tool,args)
        val watchdog=thread(isDaemon=true){try{if(!p.waitFor(timeout,TimeUnit.SECONDS))p.destroyForcibly()}catch(_:InterruptedException){}}
        return try{val b=p.inputStream.use{it.readBytesLimited(limit)};require(p.waitFor()==0){"The media tools could not read this file."};String(b,Charsets.UTF_8)}finally{watchdog.interrupt();p.destroy()}
    }
    fun probe(url: String):JSONObject {
        val d=JSONObject(output("ffprobe",listOf("-v","error","-show_format","-show_streams","-of","json",url)))
        val all=d.getJSONArray("streams");var video:JSONObject?=null;val tracks=JSONArray()
        for(i in 0 until all.length()){
            val s=all.getJSONObject(i);val kind=s.optString("codec_type");val codec=s.optString("codec_name","unknown");val tags=s.optJSONObject("tags")?:JSONObject()
            if(kind=="video"&&s.optJSONObject("disposition")?.optInt("attached_pic",0)!=1&&video==null)video=s
            if(kind in listOf("audio","subtitle"))tracks.put(JSONObject().put("id",s.getInt("index")).put("type",kind).put("codec",codec).put("language",tags.optString("language","und")).put("title",tags.optString("title")).put("text",codec in listOf("subrip","srt","ass","ssa","webvtt","mov_text","text","microdvd","sami")).put("default",s.optJSONObject("disposition")?.optInt("default",0)==1))
        }
        require(video!=null){"Choose a file containing video."}
        val duration=d.optJSONObject("format")?.optDouble("duration",0.0)?:0.0
        require(duration.isFinite()&&duration>0){"No readable duration in this file."}
        return JSONObject().put("duration",duration).put("tracks",tracks).put("video",JSONObject().put("id",video.getInt("index")).put("codec",video.optString("codec_name")).put("width",video.optInt("width")).put("height",video.optInt("height")).put("hdr",video.optString("color_transfer") in listOf("smpte2084","arib-std-b67")))
    }
    fun compatibility(source:String,info:JSONObject,start:Double,audio:Int,subtitle:Int,force:Boolean,quality:Int=0):List<String>{
        val v=info.getJSONObject("video");val tracks=info.getJSONArray("tracks")
        val subtitleInfo=(0 until tracks.length()).map{tracks.getJSONObject(it)}.find{it.getInt("id")==subtitle}
        val image=subtitleInfo!=null&&subtitleInfo.optString("type")=="subtitle"&&!subtitleInfo.optBoolean("text")
        val height=if(quality>0)quality else 1080;val width=height*16/9
        val encode=force||image||v.optString("codec") !in listOf("h264","hevc")||(quality>0&&v.optInt("height")>quality)
        val scale="scale=w='min($width,iw)':h='min($height,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p"
        val a=mutableListOf("-hide_banner","-loglevel","error","-nostdin","-threads","2","-ss",start.toString(),"-i",source)
        if(image)a+=listOf("-filter_complex","[0:${v.getInt("id")}][0:$subtitle]overlay=eof_action=pass,$scale[v]","-map","[v]") else a+=listOf("-map","0:${v.getInt("id")}")
        val audioTracks=if(audio>=0)(0 until tracks.length()).map{tracks.getJSONObject(it)}.filter{it.optString("type")=="audio"}.take(16)else emptyList()
        audioTracks.forEach{a+=listOf("-map","0:${it.getInt("id")}")}
        if(encode){if(!image)a+=listOf("-vf",scale);a+=listOf("-c:v","libx264","-preset","ultrafast","-crf","23","-maxrate",if(height>1080)"15M"else if(height>720)"5M"else if(height>480)"3M"else"1500k","-bufsize",if(height>1080)"30M"else if(height>720)"10M"else if(height>480)"6M"else"3M","-g","60","-threads","2")}else a+=listOf("-c:v","copy")
        if(audioTracks.isNotEmpty())a+=if(audioTracks.all{it.optString("codec")=="aac"})listOf("-c:a","copy")else listOf("-c:a","aac","-ac","2","-b:a","160k")else a+="-an"
        audioTracks.forEachIndexed{i,t->a+=listOf("-metadata:s:a:$i","language=${t.optString("language","und")}","-metadata:s:a:$i","title=${t.optString("title")}")}
        a+=listOf("-sn","-map_metadata","-1","-avoid_negative_ts","make_zero","-movflags","frag_keyframe+empty_moov+default_base_moof","-frag_duration","2000000","-f","mp4","pipe:1")
        return a
    }
}
