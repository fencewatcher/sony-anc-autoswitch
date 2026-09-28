package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony MDR-v2 frame building, escaping, and ACK/decoder helpers.
 * Payload construction is handled by [HeadphoneProfile] per model.
 */
object SonyAncProtocol {

    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D
    private const val DATA_TYPE = 0x0C

    /** Build a complete MDR frame (SOF + escaped body + EOF). */
    fun buildFrame(seq: Int, payload: ByteArray): ByteArray {
        require(seq in 0..1) { "seq must be 0 or 1, got $seq" }
        val size = payload.size
        val sizeBytes = byteArrayOf(
            (size shr 24 and 0xFF).toByte(),
            (size shr 16 and 0xFF).toByte(),
            (size shr 8 and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
        val body = ByteArrayOutputStream(size + 6).apply {
            write(DATA_TYPE); write(seq); write(sizeBytes); write(payload)
        }.toByteArray()
        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()
        val escaped = escape(body + checksum)
        return byteArrayOf(SOF) + escaped + byteArrayOf(EOF)
    }

    private fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        for (b in data) {
            when (b.toInt() and 0xFF) {
                0x3E -> { out.write(0x3D); out.write(0x2E) }
                0x3C -> { out.write(0x3D); out.write(0x2C) }
                0x3D -> { out.write(0x3D); out.write(0x2D) }
                else -> out.write(b.toInt())
            }
        }
        return out.toByteArray()
    }

    fun describePayload(payload: ByteArray): String =
        payload.joinToString(" ") { "%02x".format(it) }
}