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

    // ---- ANC payloads (v2, wind-noise-capable variant) ----
    // 68 17 01 <ascOnOff> <ambientFlag> <wind> <focusOnVoice> <level>

    /** Noise cancelling ON */
    val ANC_ON = byteArrayOf(
        0x68, 0x17, 0x01,  // NCASM_SET_PARAM + v2 sub-type + const
        0x01,               // ascOnOff = ON
        0x00,               // ambientFlag = NC (not ambient)
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x00,               // level = N/A for NC
    )

    /** Ambient sound ON, level 20 (full passthrough) */
    val AMBIENT = byteArrayOf(
        0x68, 0x17, 0x01,
        0x01,               // ascOnOff = ON
        0x01,               // ambientFlag = ambient
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x14,               // level = 20
    )

    /** ANC/Ambient OFF (all processing disabled) */
    val ANC_OFF = byteArrayOf(
        0x68, 0x17, 0x01,
        0x00,               // ascOnOff = OFF
        0x00,               // ambientFlag = N/A
        0x02,               // wind = normal
        0x00,               // focusOnVoice = off
        0x00,               // level = N/A
    )

    /**
     * Build a full MDR frame from a payload.
     *
     * @param seq     Sequence bit (0 or 1), alternated per message on this connection.
     * @param payload Raw payload bytes (e.g. [ANC_ON]).
     * @return Ready-to-send frame bytes.
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

        // Body before escaping: DATA_TYPE + seq + SIZE(4) + payload
        val body = ByteArrayOutputStream(size + 6).apply {
            write(DATA_TYPE)
            write(seq)
            write(sizeBytes)
            write(payload)
        }.toByteArray()

        // Checksum = sum of all body bytes, mod 256
        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()

        // Append checksum to body before escaping
        val bodyWithChecksum = body + checksum

        // Escape everything between SOF and EOF
        val escaped = escape(bodyWithChecksum)

        return byteArrayOf(SOF) + escaped + byteArrayOf(EOF)
    }

    /**
     * Escape reserved bytes in data.
     * 0x3C → 0x3D 0x2C
     * 0x3D → 0x3D 0x2D
     * 0x3E → 0x3D 0x2E
     */
    private fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2) // worst-case all reserved
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

    /** For debug: describe a payload */
    fun describePayload(payload: ByteArray): String {
        return payload.joinToString(" ") { "%02x".format(it) }
    }
}