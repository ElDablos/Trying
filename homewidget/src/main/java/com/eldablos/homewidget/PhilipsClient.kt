package com.eldablos.homewidget

import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption used by Philips Air+ devices over local CoAP,
 * as documented by the open-source aioairctrl project.
 *
 * Wire format (uppercase hex text): [8-char counter][ciphertext][64-char SHA-256].
 */
internal object PhilipsCrypto {
    private const val SECRET = "JiangPan"

    fun nextKey(key: String): String =
        ((key.toLong(16) + 1) and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    fun encrypt(key: String, plain: String): String {
        val cipherHex = Bytes.hex(cipher(Cipher.ENCRYPT_MODE, key).doFinal(plain.toByteArray()))
        return key + cipherHex + digest(key + cipherHex)
    }

    fun decrypt(message: String): String {
        val text = message.trim()
        if (text.length < 8 + 32 + 64) throw IOException("Short reply from heater")
        val key = text.substring(0, 8)
        val cipherHex = text.substring(8, text.length - 64)
        if (!digest(key + cipherHex).equals(text.takeLast(64), ignoreCase = true)) {
            throw IOException("Heater reply failed its checksum")
        }
        return String(cipher(Cipher.DECRYPT_MODE, key).doFinal(Bytes.unhex(cipherHex)), Charsets.UTF_8)
    }

    private fun digest(text: String) = Bytes.hex(Bytes.sha256(text.toByteArray()))

    private fun cipher(mode: Int, key: String): Cipher {
        val keyAndIv = Bytes.hex(Bytes.md5((SECRET + key).toByteArray()))
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                mode,
                SecretKeySpec(keyAndIv.substring(0, 16).toByteArray(), "AES"),
                IvParameterSpec(keyAndIv.substring(16).toByteArray()),
            )
        }
    }
}

class PhilipsClient(private val host: String, port: Int = PORT, timeoutMs: Int = 4000) : java.io.Closeable {
    private val coap = CoapClient(host, port, timeoutMs)
    private var key: String? = null

    override fun close() = coap.close()

    /** Handshake: agrees a message counter with the device. Must come first, from the same port. */
    fun sync() {
        val reply = try {
            coap.request(CoapMessage.POST, SYNC_PATH, Bytes.hex(Bytes.random(4)).toByteArray())
        } catch (e: IOException) {
            throw IOException("Heater didn't answer the handshake (${e.message})", e)
        }
        val text = String(reply, Charsets.US_ASCII).trim()
        if (!text.matches(Regex("[0-9A-Fa-f]{8}"))) throw IOException("Unexpected handshake reply from heater: ${text.take(40)}")
        key = text.uppercase()
    }

    /**
     * The device's full reported state, e.g. {"D03102":1,"D0310E":22,"D03224":160,...}.
     * Philips devices can be slow to answer this (Home Assistant allows 30 s), so [patient]
     * waits up to 25 s; the widget uses a shorter wait so taps stay snappy.
     */
    fun status(patient: Boolean = false): JSONObject {
        if (key == null) sync()
        // Normal request first. If nothing comes back, ask for the reply in small chunks
        // (512, then 256 bytes) in case one big reply is being lost on the Wi-Fi.
        val attempts = if (patient) {
            listOf(
                StatusAttempt("observe", true, null, listOf(5_000L, 5_000L)),
                StatusAttempt("512-byte blocks", true, 5, listOf(5_000L)),
                StatusAttempt("256-byte blocks", true, 4, listOf(5_000L)),
                StatusAttempt("plain read", false, null, listOf(5_000L)),
            )
        } else {
            listOf(
                StatusAttempt("observe", true, null, listOf(4_000L)),
                StatusAttempt("512-byte blocks", true, 5, listOf(4_000L)),
            )
        }
        val failures = mutableListOf<String>()
        var reply: ByteArray? = null
        for (attempt in attempts) {
            try {
                reply = coap.request(
                    CoapMessage.GET, STATUS_PATH, observe = attempt.observe,
                    waitsMs = attempt.waitsMs, blockSizeExp = attempt.blockSizeExp,
                )
                break
            } catch (e: IOException) {
                failures += "${attempt.name}: ${e.message}"
            }
        }
        if (reply == null) {
            throw IOException("Handshake OK, but the heater didn't send its status.\n" + failures.joinToString("\n"))
        }
        val text = String(reply, Charsets.US_ASCII)
        val json = try {
            JSONObject(PhilipsCrypto.decrypt(text))
        } catch (e: Exception) {
            throw IOException("Couldn't read the heater's status (${e.message}). Reply started: ${text.take(60)}", e)
        }
        return json.getJSONObject("state").getJSONObject("reported")
    }

    fun control(values: Map<String, Any>) {
        val desired = JSONObject()
            .put("CommandType", "app")
            .put("DeviceId", "")
            .put("EnduserId", "")
        values.forEach { (k, v) -> desired.put(k, v) }
        val body = JSONObject().put("state", JSONObject().put("desired", desired)).toString()
        repeat(3) { attempt ->
            if (key == null || attempt == 1) sync()
            val next = PhilipsCrypto.nextKey(key!!)
            key = next
            val reply = coap.request(CoapMessage.POST, CONTROL_PATH, PhilipsCrypto.encrypt(next, body).toByteArray())
            val status = runCatching { JSONObject(String(reply, Charsets.UTF_8)).optString("status") }.getOrNull()
            if (status == "success") return
        }
        throw IOException("Heater didn't accept the command")
    }

    companion object {
        const val PORT = 5683
        private const val SYNC_PATH = "/sys/dev/sync"
        private const val STATUS_PATH = "/sys/dev/status"
        private const val CONTROL_PATH = "/sys/dev/control"

        // Keys used by the newer Philips heaters (CX3120 / CX5120 family).
        const val KEY_POWER = "D03102"
        const val KEY_TARGET_TEMP = "D0310E"
        const val KEY_ROOM_TEMP = "D03224" // tenths of a degree
        const val KEY_NAME = "D01S03"
        const val KEY_MODEL = "D01S05"
        const val MIN_TEMP = 1
        const val MAX_TEMP = 37

        /** Sends a sync to every host at once and returns the ones that answer like a Philips device. */
        fun scan(hosts: List<String>, port: Int = PORT, waitMs: Int = 2500): List<String> {
            val found = linkedSetOf<String>()
            DatagramSocket().use { socket ->
                val path = SYNC_PATH.trim('/').split('/').map { CoapMessage.OPTION_URI_PATH to it.toByteArray() }
                for (host in hosts) {
                    val msg = CoapMessage(
                        CoapMessage.NON, CoapMessage.POST, kotlin.random.Random.nextInt(0x10000),
                        Bytes.random(4), path, Bytes.hex(Bytes.random(4)).toByteArray(),
                    ).encode()
                    runCatching { socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), port)) }
                }
                val deadline = System.currentTimeMillis() + waitMs
                val buffer = ByteArray(2048)
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val reply = runCatching { CoapMessage.decode(packet.data, packet.length) }.getOrNull() ?: continue
                    if (reply.codeClass == 2 && String(reply.payload).trim().matches(Regex("[0-9A-Fa-f]{8}"))) {
                        found += packet.address.hostAddress ?: continue
                    }
                }
            }
            return found.toList()
        }
    }
}

private class StatusAttempt(val name: String, val observe: Boolean, val blockSizeExp: Int?, val waitsMs: List<Long>)
