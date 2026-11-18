package io.github.gajdarwin.cast
import org.junit.Assert.*
import org.junit.Test
class YoutubeSelectionTest {
 private data class Stream(val height:Int,val codec:String)
 private fun choose(values:List<Stream>,quality:Int)=youtubeVideoSelection(values,quality,{it.height},{it.codec})
 @Test fun manualQualityPrefersAvcAndDoesNotMixAv1(){val avc=Stream(720,"avc1.64001F");assertEquals(listOf(avc),choose(listOf(Stream(720,"av01.0.08M.08"),avc,Stream(1080,"avc1.640028")),720))}
 @Test fun autoKeepsAvcAtSupportedHeights(){val a=Stream(720,"avc1.64001F");val b=Stream(1080,"avc1.640028");assertEquals(listOf(a,b),choose(listOf(Stream(2160,"av01.0.12M.08"),a,Stream(1080,"av01.0.08M.08"),b),0))}
 @Test fun fallbackStillUsesOneDecoderFamily(){val av1=Stream(720,"av01.0.08M.08");assertEquals(listOf(av1),choose(listOf(av1,Stream(720,"hev1.1.6.L93.B0")),720))}
 @Test fun missingQualityCannotSilentlySelectAnotherHeight(){assertTrue(choose(listOf(Stream(720,"avc1.64001F")),1080).isEmpty())}
}
