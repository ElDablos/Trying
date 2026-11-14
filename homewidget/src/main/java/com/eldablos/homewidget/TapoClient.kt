package com.eldablos.homewidget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class TapoException(message: String, val code: Int? = null) : IOException(message)
class TapoAuthException(message: String) : TapoException(message)

/**
 * Talks to a Tapo bulb on the local network using TP-Link's "KLAP" protocol,
 * the same one the Tapo app uses when it's on the same Wi-Fi.
 *
 * Protocol as documented by the open-source python-kasa project.
 */
class TapoClient(
    val host: String,
    email: String,
    password: String,
    private val port: Int = 80,
    private val timeoutMs: Int = 4000,
) {
    private enum class Version { V2, V1 }

    private val authHashes = listOf(
        Version.V2 to Bytes.sha256(Bytes.sha1(email.toByteArray()), Bytes.sha1(password.toByteArray())),
        Version.V1 to Bytes.md5(Bytes.md5(email.toByteArray()), Bytes.md5(password.toByteArray())),
    )
    private val terminalUuid = Base64.getEncoder().encodeToString(Bytes.md5(UUID.randomUUID().toString().toByteArray()))

    private var session: KlapSession? = null
    private var cookie: String? = null
    private var sessionExpiresAt = 0L

    /** Sends a request and returns its "result" object. Re-logs in once if the session has gone stale. */
    @Synchronized
    fun request(method: String, params: JSONObject? = null): JSONObject {
        var lastError: IOException? = null
        repeat(2) {
            try {
                val active = session?.takeIf { System.currentTimeMillis() < sessionExpiresAt } ?: handshake()
                return send(active, method, params)
            } catch (e: TapoAuthException) {
                throw e
            } catch (e: TapoException) {
                // Device-level errors (bad params etc.) aren't fixed by logging in again.
                if (e.code != null && e.code !in SESSION_ERRORS) throw e
                session = null
                lastError = e
            } catch (e: IOException) {
                session = null
                lastError = e
            }
        }
        throw lastError ?: TapoException("Request failed")
    }

    fun deviceInfo(): JSONObject = request("get_device_info")

    fun setDeviceInfo(params: JSONObject): JSONObject = request("set_device_info", params)

    private fun handshake(): KlapSession {
        session = null
        cookie = null
        val localSeed = Bytes.random(16)
        val first = post("/app/handshake1", localSeed)
        if (first.status != 200) throw TapoException("Light refused login (HTTP ${first.status})")
        if (first.body.size < 48) throw TapoException("Unexpected reply from light — is that IP really the bulb?")
        val remoteSeed = first.body.copyOfRange(0, 16)
        val serverHash = first.body.copyOfRange(16, 48)
        cookie = first.cookie
        val timeoutSeconds = first.timeoutSeconds ?: 86_400L

        val (version, authHash) = authHashes.firstOrNull { (version, hash) ->
            val expected = when (version) {
                Version.V2 -> Bytes.sha256(localSeed, remoteSeed, hash)
                Version.V1 -> Bytes.sha256(localSeed, hash)
            }
            expected.contentEquals(serverHash)
        } ?: throw TapoAuthException(
            "The light didn't accept your Tapo email/password. Check them, and make sure " +
                "Third-Party Compatibility is on in the Tapo app (Me → Third-Party Services)."
        )

        val payload = when (version) {
            Version.V2 -> Bytes.sha256(remoteSeed, localSeed, authHash)
            Version.V1 -> Bytes.sha256(remoteSeed, authHash)
        }
        val second = post("/app/handshake2", payload)
        if (second.status != 200) throw TapoException("Light rejected login step 2 (HTTP ${second.status})")

        return KlapSession(localSeed, remoteSeed, authHash).also {
            session = it
            // Log in again a bit before the device forgets us.
            sessionExpiresAt = System.currentTimeMillis() + (timeoutSeconds - 600).coerceAtLeast(60) * 1000
        }
    }

    private fun send(active: KlapSession, method: String, params: JSONObject?): JSONObject {
        val body = JSONObject()
            .put("method", method)
            .put("request_time_milis", System.currentTimeMillis())
            .put("terminal_uuid", terminalUuid)
        if (params != null) body.put("params", params)
        val (encrypted, seq) = active.encrypt(body.toString().toByteArray())
        val response = post("/app/request?seq=$seq", encrypted)
        if (response.status != 200) throw TapoException("Light replied HTTP ${response.status}")
        val json = JSONObject(String(active.decrypt(response.body), Charsets.UTF_8))
        val code = json.optInt("error_code", 0)
        if (code != 0) throw TapoException("Light returned error $code", code)
        return json.optJSONObject("result") ?: JSONObject()
    }

    private class HttpResult(val status: Int, val body: ByteArray, val cookie: String?, val timeoutSeconds: Long?)

    private fun post(path: String, data: ByteArray): HttpResult {
        val conn = URL("http://$host:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Accept", "*/*")
            cookie?.let { conn.setRequestProperty("Cookie", "$COOKIE_NAME=$it") }
            conn.setFixedLengthStreamingMode(data.size)
            conn.outputStream.use { it.write(data) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val setCookies = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .joinToString(";")
            val sessionId = Regex("$COOKIE_NAME=([^;]+)").find(setCookies)?.groupValues?.get(1)
            val timeout = Regex("TIMEOUT=(\\d+)").find(setCookies)?.groupValues?.get(1)?.toLongOrNull()
            return HttpResult(status, body, sessionId, timeout)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val COOKIE_NAME = "TP_SESSIONID"
        // Session-related device errors that a fresh login fixes.
        private val SESSION_ERRORS = setOf(9999, 1002, -1012, -1501, 1003, -40401)

        /** Quick check used by the network scan: does anything at this IP speak KLAP? */
        fun looksLikeTapo(host: String, timeoutMs: Int = 1500): Boolean = try {
            val conn = URL("http://$host/app/handshake1").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(16)
                conn.outputStream.use { it.write(Bytes.random(16)) }
                conn.responseCode == 200 && conn.inputStream.use { it.readBytes() }.size == 48
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Per-login encryption state. Each request bumps the sequence number, which also forms part of the IV. */
internal class KlapSession(localSeed: ByteArray, remoteSeed: ByteArray, authHash: ByteArray) {
    private val key = Bytes.sha256("lsk".toByteArray(), localSeed, remoteSeed, authHash).copyOfRange(0, 16)
    private val ivBase: ByteArray
    private val signature = Bytes.sha256("ldk".toByteArray(), localSeed, remoteSeed, authHash).copyOfRange(0, 28)
    var seq: Int
        private set

    init {
        val fullIv = Bytes.sha256("iv".toByteArray(), localSeed, remoteSeed, authHash)
        ivBase = fullIv.copyOfRange(0, 12)
        seq = java.nio.ByteBuffer.wrap(fullIv, 28, 4).int
    }

    fun encrypt(plain: ByteArray): Pair<ByteArray, Int> {
        seq += 1
        val cipherText = cipher(Cipher.ENCRYPT_MODE, seq).doFinal(plain)
        val sig = Bytes.sha256(signature, Bytes.int32(seq), cipherText)
        return (sig + cipherText) to seq
    }

    fun decrypt(message: ByteArray): ByteArray {
        if (message.size < 48) throw TapoException("Short reply from light")
        return cipher(Cipher.DECRYPT_MODE, seq).doFinal(message, 32, message.size - 32)
    }

    private fun cipher(mode: Int, seq: Int): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ivBase + Bytes.int32(seq)))
    }
}
