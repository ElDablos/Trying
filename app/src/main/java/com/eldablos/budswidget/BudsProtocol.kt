package com.eldablos.budswidget

/**
 * Galaxy Buds RFCOMM message framing, as documented by the open-source GalaxyBudsClient project:
 *
 *   SOM | size (2 bytes LE, low 10 bits) | id | payload | CRC16 (LE) | EOM
 *
 * where size counts id + payload + CRC and the CRC is CRC16-CCITT (XModem) over id + payload.
 */
object BudsProtocol {
    const val MSG_STATUS_UPDATED = 0x60
    const val MSG_EXTENDED_STATUS_UPDATED = 0x61
    const val MSG_NOISE_CONTROLS_UPDATE = 0x77
    const val MSG_NOISE_CONTROLS = 0x78
    const val MSG_SET_AMBIENT_MODE = 0x80
    const val MSG_AMBIENT_MODE_UPDATED = 0x81
    const val MSG_SET_NOISE_REDUCTION = 0x98
    const val MSG_NOISE_REDUCTION_MODE_UPDATE = 0x9B

    private const val SOM = 0xFD
    private const val EOM = 0xDD
    private const val SOM_LEGACY = 0xFE
    private const val EOM_LEGACY = 0xEE

    class Frame(val id: Int, val payload: ByteArray)

    fun encode(id: Int, payload: ByteArray, legacy: Boolean): ByteArray {
        val size = payload.size + 3
        val out = ByteArray(size + 4)
        out[0] = (if (legacy) SOM_LEGACY else SOM).toByte()
        out[1] = (size and 0xFF).toByte()
        out[2] = ((size shr 8) and 0x03).toByte()
        out[3] = id.toByte()
        payload.copyInto(out, 4)
        val crc = crc16(out, 3, payload.size + 1)
        out[4 + payload.size] = (crc and 0xFF).toByte()
        out[5 + payload.size] = ((crc shr 8) and 0xFF).toByte()
        out[out.size - 1] = (if (legacy) EOM_LEGACY else EOM).toByte()
        return out
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /** Reassembles frames from the RFCOMM byte stream, resyncing on garbage. */
    class Decoder {
        private var data = ByteArray(4096)
        private var length = 0

        fun feed(chunk: ByteArray, count: Int): List<Frame> {
            if (length + count > data.size) data = data.copyOf(maxOf(data.size * 2, length + count))
            System.arraycopy(chunk, 0, data, length, count)
            length += count

            val frames = mutableListOf<Frame>()
            var start = 0
            while (true) {
                while (start < length && !isSom(data[start])) start++
                if (length - start < 3) break
                val size = ((data[start + 1].toInt() and 0xFF) or ((data[start + 2].toInt() and 0xFF) shl 8)) and 0x3FF
                if (size < 3) {
                    start++
                    continue
                }
                val total = size + 4
                if (length - start < total) break
                val eom = data[start + total - 1].toInt() and 0xFF
                if (eom != EOM && eom != EOM_LEGACY) {
                    start++
                    continue
                }
                val id = data[start + 3].toInt() and 0xFF
                frames += Frame(id, data.copyOfRange(start + 4, start + 4 + size - 3))
                start += total
            }
            System.arraycopy(data, start, data, 0, length - start)
            length -= start
            return frames
        }

        private fun isSom(b: Byte): Boolean {
            val v = b.toInt() and 0xFF
            return v == SOM || v == SOM_LEGACY
        }
    }
}
