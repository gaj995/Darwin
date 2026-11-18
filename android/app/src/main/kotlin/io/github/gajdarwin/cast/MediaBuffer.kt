package io.github.gajdarwin.cast

/** Temporary playback buffer; never writes movies or request credentials to storage. */
class MediaBuffer<K,V>(val limit:Int=32*1024*1024,val itemLimit:Int=4*1024*1024,private val ttl:Long=600000,private val now:()->Long={System.nanoTime()/1000000}) {
 private data class Entry<V>(val value:V,val bytes:Int,val at:Long)
 private val entries=LinkedHashMap<K,Entry<V>>(16,.75f,true)
 var bytes=0;private set
 var hits=0;private set
 private var closed=false
 @Synchronized fun get(key:K):V? {
  if(closed)return null
  val e=entries[key]?:return null
  if(now()-e.at>=ttl){entries.remove(key);bytes-=e.bytes;return null}
  hits++;return e.value
 }
 @Synchronized fun put(key:K,value:V,size:Int) {
  if(closed||size<=0||size>itemLimit||size>limit)return
  entries.remove(key)?.let{bytes-=it.bytes}
  while(entries.isNotEmpty()&&bytes+size>limit){val iterator=entries.entries.iterator();val old=iterator.next();bytes-=old.value.bytes;iterator.remove()}
  entries[key]=Entry(value,size,now());bytes+=size
 }
 @Synchronized fun close(){closed=true;entries.clear();bytes=0}
}
