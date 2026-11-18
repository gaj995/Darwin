package io.github.gajdarwin.cast
internal fun redactDiagnostic(text:String):String = text
 .replace(Regex("(?:https?|wss?|darwincast)://[^\\s<>\"']+"),"[URL]")
 .replace(Regex("(?i)(token|password|authorization|cookie|secret|pairing.?key|signature|x-darwin-key|key)[\"']?\\s*[=:]\\s*(\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"),"$1=[redacted]")
 .replace(Regex("(?i)\\b[a-f0-9]{64}\\b"),"[redacted]").take(1500)
