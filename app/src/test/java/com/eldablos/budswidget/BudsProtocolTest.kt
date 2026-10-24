package com.eldablos.budswidget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BudsProtocolTest {
    @Test
    fun encodeFramesNoiseControlMessage() {
        val frame = BudsProtocol.encode(BudsProtocol.MSG_NOISE_CONTROLS, byteArrayOf(1), legacy = false)
        assertEquals(8, frame.size)
        assertEquals(0xFD, frame[0].toInt() and 0xFF)
        assertEquals(4, frame[1].toInt()) // id + payload + crc
        assertEquals(0, frame[2].toInt())
        assertEquals(BudsProtocol.MSG_NOISE_CONTROLS, frame[3].toInt() and 0xFF)
        assertEquals(0xDD, frame[7].toInt() and 0xFF)
    }

    @Test
    fun decoderRoundTripsAcrossChunksAndGarbage() {
        val status = byteArrayOf(1, 0, 85, 72, 1, 0, 0x33, 40)
        val stream = byteArrayOf(0x00, 0x42) +
            BudsProtocol.encode(BudsProtocol.MSG_EXTENDED_STATUS_UPDATED, status, legacy = false) +
            BudsProtocol.encode(BudsProtocol.MSG_NOISE_CONTROLS_UPDATE, byteArrayOf(2), legacy = false)

        val decoder = BudsProtocol.Decoder()
        val frames = mutableListOf<BudsProtocol.Frame>()
        for (i in stream.indices step 5) {
            val chunk = stream.copyOfRange(i, minOf(i + 5, stream.size))
            frames += decoder.feed(chunk, chunk.size)
        }

        assertEquals(2, frames.size)
        assertEquals(BudsProtocol.MSG_EXTENDED_STATUS_UPDATED, frames[0].id)
        assertArrayEquals(status, frames[0].payload)
        assertEquals(BudsProtocol.MSG_NOISE_CONTROLS_UPDATE, frames[1].id)
        assertArrayEquals(byteArrayOf(2), frames[1].payload)
    }

    @Test
    fun modelDetectionFromDeviceName() {
        assertEquals(BudsModel.MODERN, BudsModel.fromName("Galaxy Buds2 Pro (A1B2)"))
        assertEquals(BudsModel.LIVE, BudsModel.fromName("Galaxy Buds Live (A1B2)"))
        assertEquals(BudsModel.PLUS, BudsModel.fromName("Galaxy Buds+ (A1B2)"))
        assertEquals(BudsModel.ORIGINAL, BudsModel.fromName("Galaxy Buds (A1B2)"))
        assertEquals(BudsModel.MODERN, BudsModel.fromName("Galaxy Buds3 Pro"))
    }
}
