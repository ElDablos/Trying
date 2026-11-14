package com.eldablos.homewidget

import java.security.MessageDigest
import java.security.SecureRandom

internal object Bytes {
    private val random = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)
    fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", parts)
    fun md5(vararg parts: ByteArray): ByteArray = digest("MD5", parts)

    private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { i ->
            ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
        }
    }

    fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private const val HEX = "0123456789ABCDEF"
}
