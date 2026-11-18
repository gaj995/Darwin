package io.github.gajdarwin.cast

import org.json.JSONArray
import org.json.JSONObject

object Subtitles {
    fun parse(input: String): JSONArray {
        require(input.toByteArray().size <= 1048576) { "Choose a subtitle file smaller than 1 MB." }
        val cues = JSONArray()
        val timing = Regex("(\\d{1,2}:)?(\\d{2}):(\\d{2})[,.](\\d{3})")
        fun seconds(v: String): Double {
            val m = timing.find(v) ?: error("Invalid subtitle time")
            return (m.groupValues[1].trimEnd(':').toIntOrNull() ?: 0) * 3600.0 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt() + m.groupValues[4].toInt() / 1000.0
        }
        input.removePrefix("\uFEFF").replace("\r", "").split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.lines(); val i = lines.indexOfFirst { it.contains("-->") }
            if (i >= 0) {
                val times = lines[i].split("-->"); val start = seconds(times[0]); val end = seconds(times[1])
                val text = lines.drop(i + 1).joinToString("\n").replace(Regex("<[^>]*>"), "").trim().take(2000)
                if (end > start && text.isNotEmpty()) cues.put(JSONObject().put("start", start).put("end", end).put("text", text))
            }
            require(cues.length() <= 12000) { "Too many subtitle lines." }
        }
        require(cues.length() > 0) { "No subtitles found. Choose an SRT or WebVTT file." }
        return cues
    }
}
