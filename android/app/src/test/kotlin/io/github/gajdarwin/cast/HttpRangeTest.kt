package io.github.gajdarwin.cast
import org.junit.Assert.*
import org.junit.Test
class HttpRangeTest {
 @Test fun handlesHundredGiB(){val size=100L*1024*1024*1024;val range=HttpRange.parse("bytes=${size-8}-${size+20}",size);assertEquals(size-8,range.start);assertEquals(8L,range.length);assertEquals(HttpRange(size-8,size-1,true),HttpRange.parse("bytes=-8",size))}
 @Test fun fullLengthIsNotTruncated(){assertEquals(100L*1024*1024*1024,HttpRange.parse(null,100L*1024*1024*1024).length)}
 @Test fun rejectsInvalidRanges(){for(r in listOf("bytes=-0","bytes=10-","bytes=5-2","bytes=0-1,2-3","bytes=-")){try{HttpRange.parse(r,10);fail(r)}catch(_:Exception){}}}
}
