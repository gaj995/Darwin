package io.github.gajdarwin.cast

/** Inclusive byte bounds. Uses Long throughout, including Content-Length. */
data class HttpRange(val start: Long, val end: Long, val partial: Boolean) {
    val length get() = end - start + 1
    companion object {
        fun parse(value: String?, size: Long): HttpRange {
            require(size > 0) { "Empty file" }
            if (value == null) return HttpRange(0,size-1,false)
            val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: error("Invalid range")
            val a=match.groupValues[1];val b=match.groupValues[2]
            require(a.isNotEmpty()||b.isNotEmpty())
            if(a.isEmpty()){val n=b.toLong();require(n>0);return HttpRange((size-n).coerceAtLeast(0),size-1,true)}
            val start=a.toLong();val end=if(b.isEmpty())size-1 else b.toLong().coerceAtMost(size-1)
            require(start<size&&end>=start);return HttpRange(start,end,true)
        }
    }
}
