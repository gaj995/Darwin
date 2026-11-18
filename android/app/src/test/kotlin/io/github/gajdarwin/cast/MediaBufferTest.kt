package io.github.gajdarwin.cast
import org.junit.Assert.*
import org.junit.Test
class MediaBufferTest {
 @Test fun evictsLeastRecentAndExpiresWithoutCrossingRanges(){var now=0L;val b=MediaBuffer<String,String>(8,4,10){now};b.put("0-3","abcd",4);b.put("4-7","efgh",4);assertEquals("abcd",b.get("0-3"));b.put("other","ijkl",4);assertNull(b.get("4-7"));assertEquals(8,b.bytes);now=10;assertNull(b.get("0-3"));b.close();b.put("x","x",1);assertNull(b.get("other"));assertEquals(0,b.bytes)}
 @Test fun oversizedEntriesAreNeverBuffered(){val b=MediaBuffer<String,String>(8,4);b.put("big","12345",5);assertEquals(0,b.bytes);assertNull(b.get("big"))}
}
