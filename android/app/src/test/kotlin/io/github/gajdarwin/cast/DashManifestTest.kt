package io.github.gajdarwin.cast
import org.junit.Assert.*
import org.junit.Test
class DashManifestTest {
 @Test fun nestedTemplatesAndAudioKeepTheirCorrectOrigins(){
  val requests=mutableListOf<Triple<String,Boolean,Boolean>>()
  val xml="""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"><BaseURL>https://cdn.example/movie/</BaseURL><Period><AdaptationSet contentType="video"><SegmentTemplate media="v/${'$'}RepresentationID${'$'}/${'$'}Number%05d${'$'}.m4s" initialization="init-${'$'}RepresentationID${'$'}.mp4"/><Representation id="low" height="720"/><Representation id="high" height="1080"/></AdaptationSet><AdaptationSet contentType="audio"><Representation id="audio"><BaseURL>audio.mp4</BaseURL><SegmentBase indexRange="100-200"/></Representation></AdaptationSet></Period></MPD>"""
  val result=DashManifest.rewrite(xml,"https://site.example/watch/manifest.mpd",720){url,prefix,vod->requests.add(Triple(url,prefix,vod));"http://192.168.1.2:9000/token/"+requests.size+if(prefix)"/"else""}
  assertEquals(listOf(1080,720),result.qualities);assertFalse(result.xml.contains("height=\"1080\""));assertTrue(result.xml.contains("id=\"audio\""));assertTrue(requests.contains(Triple("https://cdn.example/movie/audio.mp4",false,true)));assertTrue(result.xml.contains("${'$'}RepresentationID${'$'}"));assertTrue(result.xml.contains("${'$'}Number%05d${'$'}"));assertTrue(requests.all{it.third})
 }
 @Test fun liveSegmentsAreNeverCached(){var recorded=true;DashManifest.rewrite("<MPD type=\"dynamic\"><Period><BaseURL>https://cdn.example/live/</BaseURL></Period></MPD>","https://cdn.example/master.mpd",0){_,_,vod->recorded=recorded&&vod;"http://192.168.1.2/resource/"};assertFalse(recorded)}
 @Test fun privateTemplateScopeCannotEscapeItsDirectory(){
  val base="https://cdn.example/movie/";assertEquals(base+"v/10.m4s?auth=signed",DashManifest.resolveChild(base,"v/10.m4s","auth=signed"))
  for(tail in listOf("../other","%2e%2e/other","/other","https://evil.example/a","v\\..\\other")){try{DashManifest.resolveChild(base,tail,null);fail(tail)}catch(_:IllegalArgumentException){}}
 }
 @Test fun drmAndExternalEntitiesAreRejected(){for(xml in listOf("<MPD><ContentProtection/></MPD>","<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///secret'>]><MPD>&x;</MPD>")){try{DashManifest.rewrite(xml,"https://cdn.example/master.mpd",0){_,_,_->"unused"};fail("protected XML accepted")}catch(_:IllegalArgumentException){}}}
 @Test fun diagnosticSecretsAreRemoved(){val s=redactDiagnostic("{\"secret\":\"private-value\", \"Cookie\":\"session=a b\"} https://site.example/v?auth=key darwincast://pair?key=secret");assertFalse(s.contains("private-value"));assertFalse(s.contains("session=a"));assertFalse(s.contains("auth=key"));assertFalse(s.contains("pair?"))}
}
