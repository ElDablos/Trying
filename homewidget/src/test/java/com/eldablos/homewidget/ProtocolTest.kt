package com.eldablos.homewidget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** Expected values were produced by the reference Python library (python-kasa). */
class ProtocolTest {

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
}
