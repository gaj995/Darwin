package io.github.gajdarwin.cast

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TvConnection(private val onChange: (String, JSONObject?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var secret = ""
    private var session = ""
    private var sequence = 0L
    private var received = 0L
    private var generation = 0
    private var challenge = ""
    var ip = ""; private set
    var paired = false; private set
    private val poll = object : Runnable {
        override fun run() { if (paired) { command("status"); main.postDelayed(this, 1000) } }
    }
    private fun update(message: String, state: JSONObject? = null) = main.post { onChange(message, state) }
    fun connect(host: String, key: String) {
        require(CastProtocol.localIP(host)) { "Use the TV's private Wi-Fi address." }
        require(key.matches(Regex("[a-f0-9]{64}"))) { "Scan the Pair Android QR on your TV." }
        disconnect(); ip = host; secret = key; challenge = CastProtocol.token(16)
        val mine = generation
        update("Connecting to TV…")
        socket = client.newWebSocket(Request.Builder().url("ws://$host:8001/api/v2/channels/${CastProtocol.CHANNEL}?name=DarwinAndroid").build(), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (mine != generation || text.length > 1500000) return
                try {
                    val outer = JSONObject(text)
                    if (outer.optString("event") in listOf("ms.channel.connect", "ms.channel.ready", "ms.channel.clientConnect")) {
                        if (!paired) send(JSONObject().put("op", "hello").put("challenge", challenge))
                    }
                    if (outer.optString("event") != "darwin.tv") return
                    val data = CastProtocol.decrypt(secret, outer.getString("data"))
                    if (data.optString("challenge") != challenge) return
                    if (data.optString("op") == "welcome" && !paired) {
                        session = data.getString("session"); sequence = 0; received = 0; paired = true
                        update("Connected to TV", data.optJSONObject("state")); main.removeCallbacks(poll); main.post(poll)
                    } else if (paired && data.optString("session") == session && data.optLong("seq") > received) {
                        received = data.getLong("seq")
                        update(data.optString("error").ifEmpty { "Connected to TV" }, data.optJSONObject("state"))
                    }
                } catch (_: Exception) { /* Ignore unauthenticated or malformed messages. */ }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { if (mine == generation) { paired = false; main.removeCallbacks(poll); update("TV disconnected. Open Darwin, then reconnect.") } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (mine == generation) { paired = false; main.removeCallbacks(poll); update("TV disconnected") } }
        })
        main.postDelayed({ if (mine == generation && !paired) update("Open Darwin on the TV and check that both devices use the same Wi-Fi.") }, 8000)
    }
    private fun send(value: JSONObject): Boolean {
        val encrypted = CastProtocol.encrypt(secret, value)
        return socket?.send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "darwin.phone").put("data", encrypted).put("to", "broadcast")).toString()) ?: false
    }
    fun command(op: String, args: JSONObject = JSONObject()): Boolean {
        if (!paired) { update("Connect to your TV first."); return false }
        args.put("op", op).put("session", session).put("seq", ++sequence)
        return send(args)
    }
    fun disconnect() { generation++; main.removeCallbacks(poll); paired = false; socket?.cancel(); socket = null; session = "" }
}
