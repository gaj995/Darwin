package io.github.gajdarwin.cast

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Rewrite recorded/live, non-DRM DASH resources through the scoped phone relay. */
internal object DashManifest {
 data class Result(val xml:String,val qualities:List<Int>)
 fun rewrite(data:String,base:String,quality:Int,link:(String,Boolean,Boolean)->String):Result {
  require(data.length<=2*1024*1024&&!Regex("<!\\s*(DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(data)){"Unsupported DASH declaration"}
  val factory=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;isExpandEntityReferences=false}
  val builder=factory.newDocumentBuilder();builder.setEntityResolver{_,_->throw IllegalArgumentException("External DASH entity blocked")}
  val document=builder.parse(data.byteInputStream());val root=document.documentElement
  fun name(e:Element)=e.localName?:e.tagName
  fun children(e:Element)=(0 until e.childNodes.length).mapNotNull{e.childNodes.item(it) as? Element}
  require(name(root)=="MPD"){"Not a DASH manifest"}
  fun descendants(e:Element):List<Element> = listOf(e)+children(e).flatMap{descendants(it)}
  val nodes=descendants(root);require(nodes.none{name(it)=="ContentProtection"}){"DRM-protected streams cannot be cast"}
  val heights=nodes.filter{name(it)=="Representation"}.mapNotNull{it.getAttribute("height").toIntOrNull()}.distinct().sortedDescending()
  if(quality>0&&heights.isNotEmpty()){val chosen=heights.minBy{kotlin.math.abs(it-quality)};nodes.filter{name(it)=="Representation"&&it.getAttribute("height").toIntOrNull()?.let{h->h!=chosen}==true}.forEach{it.parentNode.removeChild(it)}}
  val recorded=root.getAttribute("type")!="dynamic"
  fun resolve(origin:String,raw:String)=origin.toHttpUrl().resolve(raw)?.toString()?:error("Invalid DASH resource")
  fun resource(origin:String,raw:String):String {
   val remote=resolve(origin,raw)
   if('$' !in remote)return link(remote,false,recorded)
   val url=remote.toHttpUrl();val path=url.encodedPath;val first=path.indexOf('$');val slash=if(first<0)path.lastIndexOf('/')else path.lastIndexOf('/',first)
   val prefix=url.newBuilder().encodedPath(path.substring(0,slash+1)).query(null).fragment(null).build().toString()
   return link(prefix,true,recorded)+path.substring(slash+1)+(url.encodedQuery?.let{"?$it"}?:"")
  }
  fun walk(node:Element,inherited:String){
   val bases=children(node).filter{name(it)=="BaseURL"};val remote=if(bases.isEmpty())inherited else resolve(inherited,bases.first().textContent.trim())
   for(b in bases){val target=resolve(inherited,b.textContent.trim());b.textContent=link(target,target.toHttpUrl().encodedPath.endsWith('/'),recorded)}
   for(attr in listOf("media","initialization","sourceURL","index"))if(node.hasAttribute(attr))node.setAttribute(attr,resource(remote,node.getAttribute(attr)))
   for(child in children(node))if(name(child)!="BaseURL")walk(child,remote)
  }
  walk(root,base)
  if(children(root).none{name(it)=="BaseURL"}){val b=document.createElementNS(root.namespaceURI,"BaseURL");b.textContent=link(resolve(base,"."),true,recorded);root.insertBefore(b,root.firstChild)}
  val out=java.io.StringWriter();TransformerFactory.newInstance().newTransformer().transform(DOMSource(document),StreamResult(out));return Result(out.toString(),heights)
 }
 /** Only descendants of the registered remote directory can be requested. */
 fun resolveChild(base:String,tail:String,query:String?):String {
  require(!tail.startsWith('/')&&!tail.contains('\\')&&!tail.contains('\u0000')){"Invalid DASH path"}
  val origin=base.toHttpUrl();val decoded=java.net.URLDecoder.decode(tail,"UTF-8")
  require(decoded.split('/').none{it==".."||it=="."}&&!decoded.contains('\\')){"Invalid DASH path"}
  val result=origin.resolve(tail+(query?.let{"?$it"}?:""))?:error("Invalid DASH path")
  require(result.scheme==origin.scheme&&result.host==origin.host&&result.port==origin.port&&result.encodedPath.startsWith(origin.encodedPath)){"DASH resource outside session"}
  return result.toString()
 }
}
