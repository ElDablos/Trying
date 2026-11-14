package com.eldablos.homewidget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** Expected values were produced by the reference Python libraries (aioairctrl, python-kasa). */
class ProtocolTest {

    @Test
    fun philipsEncryptMatchesReference() {
        val encrypted = PhilipsCrypto.encrypt(PhilipsCrypto.nextKey("0000ABCC"), """{"state":{"desired":{"D03102":1}}}""")
        assertEquals(
            "0000ABCD42BC775FC44B889B168A38FC9F8D54261B610020BD324634E9D6297AB59EAC7889104C796F60EDD8765BE9E2407687239AA5A9550869327D5802B834A2648523ECE8234BBA4CF2F742B76A5FE7912754",
            encrypted,
        )
        assertEquals("""{"state":{"desired":{"D03102":1}}}""", PhilipsCrypto.decrypt(encrypted))
    }

    @Test
    fun philipsKeyWrapsAround() {
        assertEquals("00000000", PhilipsCrypto.nextKey("FFFFFFFF"))
        assertEquals("0000000A", PhilipsCrypto.nextKey("00000009"))
    }

    @Test
    fun klapEncryptMatchesReference() {
        fun h(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray())
        val auth = MessageDigest.getInstance("SHA-256").run {
            update(h("SHA-1", "me@example.com")); update(h("SHA-1", "secret pass")); digest()
        }
        val session = KlapSession(ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 16).toByte() }, auth)
        val (out, seq) = session.encrypt("""{"method":"get_device_info"}""".toByteArray())
        assertEquals(-827672852, seq)
        assertEquals(
            "f5032983c609a14954d882d9b111eb56a7625680e3c213a93b8a07db4ea8e1fbb5d7f7e445a21de21e6b2ce59311b247bcb253f6bb6b48a91305c235c55ba726",
            Bytes.hex(out).lowercase(),
        )
    }

    @Test
    fun coapRoundTrip() {
        val message = CoapMessage(
            CoapMessage.NON, CoapMessage.GET, 0x1234, byteArrayOf(1, 2, 3, 4),
            listOf(
                CoapMessage.OPTION_URI_PATH to "sys".toByteArray(),
                CoapMessage.OPTION_OBSERVE to CoapMessage.uint(0),
                CoapMessage.OPTION_URI_PATH to "dev".toByteArray(),
                CoapMessage.OPTION_BLOCK2 to CoapMessage.uint(0x1E),
                300 to ByteArray(20) { 7 },
            ),
            "hello".toByteArray(),
        )
        val decoded = CoapMessage.decode(message.encode())
        assertEquals(CoapMessage.NON, decoded.type)
        assertEquals(CoapMessage.GET, decoded.code)
        assertEquals(0x1234, decoded.messageId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.token)
        assertEquals(listOf(6, 11, 11, 23, 300), decoded.options.map { it.first })
        assertEquals(0x1E, CoapMessage.uintValue(decoded.option(CoapMessage.OPTION_BLOCK2)!!))
        assertEquals("hello", String(decoded.payload))
    }
}
