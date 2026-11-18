package io.github.gajdarwin.cast

import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.FilterInputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Token-scoped, TV-only relay. The TV cannot choose arbitrary upstream URLs. */
class MediaRelay(private val tvIP: String) : NanoHTTPD(0) {
    data class Source(val url: String, val headers: Map<String, String>)
    private val sources = ConcurrentHashMap<String, Source>()
    private val indexed = ConcurrentHashMap<Pair<Source,Boolean>, String>()
    private val prefixes=ConcurrentHashMap.newKeySet<String>()
    private val cacheable=ConcurrentHashMap.newKeySet<Source>()
    private data class Buffered(val status:Int,val mime:String,val headers:Map<String,String>,val body:ByteArray)
    private val buffer=MediaBuffer<Pair<Source,String>,Buffered>()
    @Volatile var bytesSent=0L;private set
    fun bufferStats()=buffer.bytes to buffer.hits
    private val token = CastProtocol.token()
    private var origin = ""
    var quality = 0
    var qualities: List<Int> = emptyList(); private set
    private var root = ""
    val qualityUrl get() = "$origin/$token/quality"
    fun add(url:String,headers:Map<String,String>,recorded:Boolean=false):String = register(Source(url,headers),recorded)
    private val client = OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).also { addresses ->
            require(addresses.none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }) { "Private upstream address blocked" }
        }
    }).followRedirects(false).readTimeout(30, TimeUnit.SECONDS).build()
    fun begin(phoneIP: String, url: String, headers: Map<String, String>, recorded:Boolean=false): String {
        require(CastProtocol.localIP(phoneIP)) { "Connect your phone to Wi-Fi first." }
        start(SOCKET_READ_TIMEOUT, true); origin = "http://$phoneIP:$listeningPort"
        return register(Source(url, headers),recorded).also { root=it }
    }
    @Synchronized private fun register(source: Source,recorded:Boolean=false,prefix:Boolean=false): String {
        if(recorded)cacheable.add(source)
        indexed[source to prefix]?.let { return it }
        require(sources.size < 12000) { "Stream session is too large; cast again." }
        val u = source.url.toHttpUrl(); require(u.username.isEmpty() && u.password.isEmpty())
        val id = CastProtocol.token(16); sources[id] = source;if(prefix)prefixes.add(id)
        val suffix = if (u.encodedPath.endsWith(".m3u8")) ".m3u8" else if (u.encodedPath.endsWith(".mp4")) ".mp4" else if(u.encodedPath.endsWith(".mpd")) ".mpd" else ""
        return (if(prefix)"$origin/$token/$id/"else "$origin/$token/$id$suffix").also { indexed[source to prefix] = it }
    }
    private fun child(base: Source, raw: String,recorded:Boolean=false): String {
        val parent = base.url.toHttpUrl(); val next = parent.resolve(raw) ?: error("Bad playlist URL")
        val headers = if (next.host == parent.host && next.scheme == parent.scheme && next.port == parent.port) base.headers else base.headers.filterKeys { it.lowercase() !in listOf("cookie", "authorization") }
        return register(Source(next.toString(), headers),recorded)
    }
    private fun fetch(source: Source, range: String?): Pair<okhttp3.Response, Source> {
        var current = source
        repeat(6) {
            val b = Request.Builder().url(current.url).header("Accept-Encoding", "identity")
            current.headers.forEach { (k, v) -> if (k.lowercase() in listOf("user-agent", "referer", "origin", "cookie", "authorization", "accept")) b.header(k, v) }
            if (range != null && range.matches(Regex("bytes=\\d*-\\d*(,\\d*-\\d*)*"))) b.header("Range", range)
            val r = client.newCall(b.build()).execute()
            if (r.code !in listOf(301,302,303,307,308)) return r to current
            val next = r.header("Location"); r.close(); require(next != null)
            val old = current.url.toHttpUrl(); val dest = old.resolve(next) ?: error("Bad redirect")
            require(!(old.isHttps && !dest.isHttps)) { "Unsafe redirect" }
            current = Source(dest.toString(), if (old.host == dest.host && old.port == dest.port) current.headers else current.headers.filterKeys { it.lowercase() !in listOf("cookie", "authorization") })
        }
        error("Too many redirects")
    }
    override fun serve(s: IHTTPSession): NanoHTTPD.Response {
        if (s.remoteIpAddress !in listOf(tvIP,"127.0.0.1","::1") || s.method !in listOf(Method.GET, Method.HEAD,Method.POST,Method.OPTIONS)) return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        if(s.uri=="/$token/quality") {
            val out=try{
                if(s.method==Method.OPTIONS)newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT,"text/plain","")
                else {
                    if(s.method==Method.POST){val n=s.headers["content-length"]?.toIntOrNull()?:0;require(n in 1..2048);val b=ByteArray(n);var at=0;while(at<n){val read=s.inputStream.read(b,at,n-at);require(read>0);at+=read};val requested=org.json.JSONObject(String(b,Charsets.UTF_8)).getInt("quality");require(requested==0||requested in qualities);quality=requested}
                    newFixedLengthResponse(NanoHTTPD.Response.Status.OK,"application/json",org.json.JSONObject().put("url",root).put("qualities",org.json.JSONArray(qualities)).put("quality",quality).toString())
                }
            }catch(_:Exception){newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,"text/plain","Quality unavailable")}
            out.addHeader("Access-Control-Allow-Origin","null");out.addHeader("Access-Control-Allow-Methods","GET, POST, OPTIONS");out.addHeader("Access-Control-Allow-Headers","Content-Type");return out
        }
        if(s.method !in listOf(Method.GET,Method.HEAD))return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,"text/plain","")
        val p = s.uri.split('/'); val id = p.getOrNull(2)?.substringBefore('.')
        val registered = if (p.getOrNull(1) == token) sources[id] else null
        if (registered == null) return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Expired stream")
        val recorded=registered in cacheable
        val source=if(id!=null&&id in prefixes)try{registered.copy(url=DashManifest.resolveChild(registered.url,p.drop(3).joinToString("/"),s.queryParameterString))}catch(_:Exception){return newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,"text/plain","Invalid stream path")}else registered
        val cacheKey=source to (s.headers["range"]?:"")
        if(recorded)buffer.get(cacheKey)?.let { saved ->
            if(s.method==Method.GET)bytesSent+=saved.body.size
            val out=newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(saved.status),saved.mime,saved.body.inputStream(),saved.body.size.toLong())
            saved.headers.forEach{(name,value)->out.addHeader(name,value)};out.addHeader("Cache-Control","no-store");return out
        }
        return try {
            val (upstream, resolved) = fetch(source, s.headers["range"])
            val body = upstream.body ?: error("Empty stream")
            val mime = upstream.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
            val playlist = resolved.url.toHttpUrl().encodedPath.endsWith(".m3u8") || mime.contains("mpegurl", true)
            val dash=resolved.url.toHttpUrl().encodedPath.endsWith(".mpd")||mime.contains("dash+xml",true)
            if(dash&&upstream.isSuccessful){
                val bytes=body.byteStream().readBytesLimited(2*1024*1024);upstream.close()
                val rewritten=DashManifest.rewrite(String(bytes,Charsets.UTF_8),resolved.url,quality){url,prefix,recorded->val parent=resolved.url.toHttpUrl();val next=url.toHttpUrl();val headers=if(parent.host==next.host&&parent.port==next.port&&parent.scheme==next.scheme)resolved.headers else resolved.headers.filterKeys{it.lowercase() !in listOf("cookie","authorization")};register(Source(url,headers),recorded,prefix)}
                qualities=rewritten.qualities;newFixedLengthResponse(NanoHTTPD.Response.Status.OK,"application/dash+xml",rewritten.xml)
            }else if (playlist && upstream.isSuccessful) {
                val bytes = body.byteStream().readBytesLimited(2 * 1024 * 1024); upstream.close(); require(bytes.size <= 2 * 1024 * 1024)
                var lines=String(bytes,Charsets.UTF_8).lines()
                val recorded=lines.any{it.trim()=="#EXT-X-ENDLIST"}
                val variants=lines.mapIndexedNotNull { i,line -> if(line.startsWith("#EXT-X-STREAM-INF:"))Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let{i to it} else null }
                if(variants.isNotEmpty()){qualities=variants.map{it.second}.distinct().sortedDescending();if(quality>0){val chosen=qualities.minBy{ kotlin.math.abs(it-quality) };val remove=variants.filter{it.second!=chosen}.flatMap{listOf(it.first,it.first+1)}.toSet();lines=lines.filterIndexed{i,_->i !in remove}}}
                val rewritten = lines.joinToString("\n") { line ->
                    if (line.isNotBlank() && !line.startsWith('#')) child(resolved, line.trim(),recorded)
                    else Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${child(resolved, it.groupValues[1],recorded&&line.startsWith("#EXT-X-MAP:"))}\"" }
                }
                newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
            } else {
                val status = NanoHTTPD.Response.Status.lookup(upstream.code) ?: NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
                val size = body.contentLength()
                val responseHeaders=listOf("Content-Range","Accept-Ranges","ETag","Last-Modified").mapNotNull{name->upstream.header(name)?.let{name to it}}.toMap()
                val canStore=recorded&&s.method==Method.GET&&upstream.code in listOf(200,206)&&size in 1..buffer.itemLimit.toLong()
                val input = object : FilterInputStream(body.byteStream()) {
                    private var collected=if(canStore)java.io.ByteArrayOutputStream() else null
                    private fun collect(b:ByteArray,offset:Int,n:Int) {
                        val out=collected?:return
                        if(n>0&&out.size()+n<=buffer.itemLimit)out.write(b,offset,n) else if(n>0){collected=null;return}
                        if(out.size().toLong()==size){val data=out.toByteArray();buffer.put(cacheKey,Buffered(upstream.code,mime,responseHeaders,data),data.size);collected=null}
                    }
                    override fun read(b:ByteArray,offset:Int,length:Int):Int {val n=super.read(b,offset,length);if(n>0)bytesSent+=n;collect(b,offset,n);return n}
                    override fun read():Int {val n=super.read();if(n>=0)bytesSent++;if(n>=0)collect(byteArrayOf(n.toByte()),0,1);return n}
                    override fun close() { collected=null;super.close();upstream.close() }
                }
                val out = if (size >= 0) newFixedLengthResponse(status, mime, input, size) else newChunkedResponse(status, mime, input)
                listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified").forEach { name -> upstream.header(name)?.let { out.addHeader(name,it) } }
                out
            }
        } catch (_: Exception) { newFixedLengthResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream unavailable. Reopen the video and cast again.") }
    }
    override fun stop() { buffer.close();cacheable.clear();super.stop(); client.dispatcher.cancelAll(); sources.clear(); indexed.clear();prefixes.clear() }
}
