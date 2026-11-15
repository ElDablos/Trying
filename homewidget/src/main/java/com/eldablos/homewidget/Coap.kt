package com.eldablos.homewidget

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Just enough CoAP (RFC 7252) to talk to Philips Air+ devices: NON requests, Observe and Block2. */
internal class CoapMessage(
    val type: Int,
    val code: Int,
    val messageId: Int,
    val token: ByteArray,
    val options: List<Pair<Int, ByteArray>> = emptyList(),
    val payload: ByteArray = ByteArray(0),
) {
    val codeClass get() = code ushr 5
    val codeText get() = "${code ushr 5}.${(code and 0x1F).toString().padStart(2, '0')}"

    fun option(number: Int): ByteArray? = options.firstOrNull { it.first == number }?.second

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((1 shl 6) or (type shl 4) or token.size)
        out.write(code)
        out.write(messageId ushr 8 and 0xFF)
        out.write(messageId and 0xFF)
        out.write(token)
        var previous = 0
        for ((number, value) in options.sortedBy { it.first }) {
            val delta = number - previous
            previous = number
            val (deltaNibble, deltaExt) = nibble(delta)
            val (lengthNibble, lengthExt) = nibble(value.size)
            out.write((deltaNibble shl 4) or lengthNibble)
            out.write(deltaExt)
            out.write(lengthExt)
            out.write(value)
        }
        if (payload.isNotEmpty()) {
            out.write(0xFF)
            out.write(payload)
        }
        return out.toByteArray()
    }

    companion object {
        const val CON = 0
        const val NON = 1
        const val ACK = 2
        const val RST = 3

        const val GET = 1
        const val POST = 2

        const val OPTION_OBSERVE = 6
        const val OPTION_URI_PATH = 11
        const val OPTION_BLOCK2 = 23

        private fun nibble(value: Int): Pair<Int, ByteArray> = when {
            value < 13 -> value to ByteArray(0)
            value < 269 -> 13 to byteArrayOf((value - 13).toByte())
            else -> 14 to byteArrayOf(((value - 269) ushr 8).toByte(), (value - 269).toByte())
        }

        fun uint(value: Int): ByteArray = when {
            value == 0 -> ByteArray(0)
            value < 0x100 -> byteArrayOf(value.toByte())
            value < 0x10000 -> byteArrayOf((value ushr 8).toByte(), value.toByte())
            else -> byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        }

        fun uintValue(bytes: ByteArray): Int = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun decode(data: ByteArray, length: Int = data.size): CoapMessage {
            if (length < 4) throw IOException("CoAP message too short")
            val first = data[0].toInt() and 0xFF
            if (first ushr 6 != 1) throw IOException("Not a CoAP message")
            val type = (first ushr 4) and 0x3
            val tokenLength = first and 0xF
            val code = data[1].toInt() and 0xFF
            val messageId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            var i = 4
            val token = data.copyOfRange(i, i + tokenLength)
            i += tokenLength
            val options = mutableListOf<Pair<Int, ByteArray>>()
            var number = 0
            while (i < length) {
                val header = data[i].toInt() and 0xFF
                i++
                if (header == 0xFF) break
                fun extended(nib: Int): Int = when (nib) {
                    13 -> (data[i++].toInt() and 0xFF) + 13
                    14 -> {
                        val v = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        i += 2
                        v + 269
                    }
                    15 -> throw IOException("Bad CoAP option")
                    else -> nib
                }
                val delta = extended(header ushr 4)
                val optionLength = extended(header and 0xF)
                number += delta
                options += number to data.copyOfRange(i, i + optionLength)
                i += optionLength
            }
            val payload = if (i < length) data.copyOfRange(i, length) else ByteArray(0)
            return CoapMessage(type, code, messageId, token, options, payload)
        }
    }
}

/**
 * One CoAP "session": every request goes out from the same local port, because Philips devices
 * tie the sync handshake to the address and port it came from.
 */
internal class CoapClient(private val host: String, private val port: Int = 5683, private val timeoutMs: Int = 2500) :
    java.io.Closeable {
    private var socket: DatagramSocket? = null

    private fun socket(): DatagramSocket = socket ?: DatagramSocket().also { socket = it }

    override fun close() {
        socket?.close()
        socket = null
    }

    /** Sends a NON request and returns the (reassembled) response payload. */
    fun request(
        code: Int, path: String, payload: ByteArray = ByteArray(0), observe: Boolean = false,
        waitsMs: List<Long> = listOf(timeoutMs.toLong(), timeoutMs.toLong()),
        blockSizeExp: Int? = null,
    ): ByteArray {
        val address = InetAddress.getByName(host)
        val socket = socket()
        run {
            val out = ByteArrayOutputStream()
            var blockNumber = 0
            // Asking for small blocks up front keeps each reply in one Wi-Fi packet.
            val askSmall = blockSizeExp != null
            var szx = blockSizeExp ?: 6
            while (true) {
                val options = mutableListOf<Pair<Int, ByteArray>>()
                if (observe && blockNumber == 0) options += CoapMessage.OPTION_OBSERVE to CoapMessage.uint(0)
                path.trim('/').split('/').forEach { options += CoapMessage.OPTION_URI_PATH to it.toByteArray() }
                if (blockNumber > 0 || askSmall) {
                    options += CoapMessage.OPTION_BLOCK2 to CoapMessage.uint((blockNumber shl 4) or szx)
                }
                val response = exchange(socket, address, code, options, if (blockNumber == 0) payload else ByteArray(0), waitsMs)
                if (response.codeClass != 2) throw IOException("Heater replied ${response.codeText}")
                out.write(response.payload)
                val block = response.option(CoapMessage.OPTION_BLOCK2) ?: break
                val value = CoapMessage.uintValue(block)
                if (value and 0x8 == 0) break
                blockNumber = (value ushr 4) + 1
                szx = value and 0x7
            }
            return out.toByteArray()
        }
    }

    /**
     * Sends the request, re-sending it (with a fresh ID and token) after each wait in [waitsMs]
     * until something answers. Anything else the device sends meanwhile is noted for the error.
     */
    private fun exchange(
        socket: DatagramSocket, address: InetAddress, code: Int, options: List<Pair<Int, ByteArray>>, payload: ByteArray,
        waitsMs: List<Long>,
    ): CoapMessage {
        val tokens = mutableListOf<ByteArray>()
        val sentIds = mutableSetOf<Int>()
        val notes = linkedSetOf<String>()
        val buffer = ByteArray(65_535)
        for (wait in waitsMs) {
            val token = Bytes.random(4)
            val messageId = Random.nextInt(0x10000)
            tokens += token
            sentIds += messageId
            val request = CoapMessage(CoapMessage.NON, code, messageId, token, options, payload).encode()
            socket.send(DatagramPacket(request, request.size, address, port))
            val deadline = System.currentTimeMillis() + wait
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
                val message = try {
                    CoapMessage.decode(packet.data, packet.length)
                } catch (_: IOException) {
                    notes += "unreadable packet"
                    continue
                }
                if (message.type == CoapMessage.CON) {
                    val ack = CoapMessage(CoapMessage.ACK, 0, message.messageId, ByteArray(0)).encode()
                    socket.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
                }
                when {
                    message.type == CoapMessage.RST ->
                        notes += if (message.messageId in sentIds) "request rejected (RST)" else "RST"
                    message.code == 0 -> notes += "empty ${typeName(message.type)}"
                    tokens.none { it.contentEquals(message.token) } -> notes += "${message.codeText} for another request"
                    else -> return message
                }
            }
        }
        val extra = if (notes.isEmpty()) "nothing came back" else "got: " + notes.joinToString(", ")
        throw SocketTimeoutException("Heater didn't answer in ${waitsMs.sum() / 1000}s; $extra")
    }

    private fun typeName(type: Int) = when (type) {
        CoapMessage.CON -> "CON"
        CoapMessage.NON -> "NON"
        CoapMessage.ACK -> "ACK"
        else -> "RST"
    }

}
