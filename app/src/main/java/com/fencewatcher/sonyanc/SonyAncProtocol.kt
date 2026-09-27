package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony WH-1000XM5/XM6 Bluetooth control protocol (MDR framing).
 *
 * Frame: SOF(0x3E) | escape(DATA_TYPE seq SIZE-bigendian-4b PAYLOAD CHECKSUM) | EOF(0x3C)
 * Escape: 0x3C→0x3D 0x2C, 0x3D→0x3D 0x2D, 0x3E→0x3D 0x2E
 * Checksum: sum(DATA_TYPE + seq + SIZE + PAYLOAD) mod 256
 * Seq: alternates 0→1→0…, resets on reconnect
 *
 * @see <a href="https://github.com/MamaJo3/sony-mx5-desktop-toggle/blob/main/docs/PROTOCOL.md">Protocol docs</a>
 */
object SonyAncProtocol {

    // ---- frame delimiters ----
    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D

    // ---- data type for control commands ----
    private const val DATA_TYPE = 0x0C

    // ---- ANC payloads ----
    // 68 17 01 <ascOnOff> <ambientFlag> <wind> <focusOnVoice> <level>
    // XM3/XM4 7-byte: 68 17 01 <ascOnOff> <ambientFlag> <level>

    // ---- XM5-style (8 bytes, v2 with wind reduction) ----
    
    val ANC_ON = byteArrayOf(
        0x68, 0x17, 0x01,
        0x01,               // ascOnOff = ON
        0x00,               // ambientFlag = NC
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x14,               // level = 20
    )

    val AMBIENT = byteArrayOf(
        0x68, 0x17, 0x01,
        0x01,               // ascOnOff = ON
        0x01,               // ambientFlag = ambient
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x14,               // level = 20
    )

    // ---- XM6 alternative byte order (level at byte 6) ----
    
    val AMBIENT_XM6 = byteArrayOf(
        0x68, 0x17, 0x01,
        0x01,               // ascOnOff = ON
        0x01,               // ambientFlag = ambient
        0x02,               // wind = normal
        0x14,               // level = 20
        0x00,               // focusOnVoice = off
    )

    // ---- XM3/XM4 style (7 bytes, no wind/focus fields) ----
    
    val AMBIENT_7 = byteArrayOf(
        0x68, 0x17, 0x01,
        0x01,               // ascOnOff = ON
        0x01,               // ambientFlag = ambient
        0x14,               // level = 20
    )

    // ---- All variants for testing ----
    val ALL_AMBIENT = listOf(
        "XM5-8byte" to AMBIENT,
        "XM6-swapped" to AMBIENT_XM6,
        "XM4-7byte" to AMBIENT_7,
    )

    val ANC_OFF = byteArrayOf(
        0x68, 0x17, 0x01,
        0x00,               // ascOnOff = OFF
        0x00,               // ambientFlag = N/A
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x14,               // level = 20
    )

    /**
     * Build a full MDR frame from a payload.
     */
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
            write(DATA_TYPE)
            write(seq)
            write(sizeBytes)
            write(payload)
        }.toByteArray()

        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()
        val bodyWithChecksum = body + checksum
        val escaped = escape(bodyWithChecksum)

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

    fun describePayload(payload: ByteArray): String {
        return payload.joinToString(" ") { "%02x".format(it) }
    }
}