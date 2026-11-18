package io.github.gajdarwin.cast

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CastProtocol {
    const val CHANNEL = "io.github.gajdarwin.cast.v1"
    private val random = SecureRandom()
    fun token(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun un64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP)
    private fun key(s: String): SecretKeySpec {
        require(s.matches(Regex("[a-f0-9]{64}"))) { "Invalid pairing key" }
        return SecretKeySpec(s.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")
    }
    fun encrypt(secret: String, value: JSONObject): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, iv))
        c.updateAAD(CHANNEL.toByteArray())
        return b64(iv) + "." + b64(c.doFinal(value.toString().toByteArray()))
    }
    fun decrypt(secret: String, value: String): JSONObject {
        require(value.length <= 1500000)
        val parts = value.split('.'); require(parts.size == 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, un64(parts[0])))
        c.updateAAD(CHANNEL.toByteArray())
        return JSONObject(String(c.doFinal(un64(parts[1])), Charsets.UTF_8))
    }
    fun localIP(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        return p.size == 4 && p.all { it in 0..255 } && (p[0] == 10 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31)
    }
}
