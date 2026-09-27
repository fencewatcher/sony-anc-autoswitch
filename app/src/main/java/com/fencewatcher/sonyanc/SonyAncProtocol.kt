package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony WH-1000XM6 Bluetooth control protocol (MDR-v2 framing).
 *
 * **XM6 uses sub-type 0x19** and a 9-byte NCASM_SET_PARAM payload.
 * Byte positions verified empirically against user firmware.
 *
 *   68 19 01 <vcs> <totalEffect> <mode> <level> <ambientVoice> <na> <naSens>
 *
 * Frame: SOF(0x3E) | escape(DATA_TYPE seq SIZE-bigendian-4b PAYLOAD CHECKSUM) | EOF(0x3C)
 * Escape: 0x3C→0x3D 0x2C, 0x3D→0x3D 0x2D, 0x3E→0x3D 0x2E
 * Checksum: sum(DATA_TYPE + seq + SIZE + PAYLOAD) mod 256
 * Seq: alternates 0→1→0…, resets on reconnect
 */
object SonyAncProtocol {

    // ---- frame delimiters ----
    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D

    // ---- data type for control commands ----
    private const val DATA_TYPE = 0x0C

    // ---- ANC payloads (XM6, sub-type 0x19, 9 bytes) ----
    // 68 19 01 <vcs> <totalEffect> <mode> <level> <ambientVoice> <na> <naSensitivity>

    /** Noise cancelling ON */
    val ANC_ON = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x01,               // totalEffect = ON
        0x00,               // mode = NC
        0x14,               // level = 20
        0x00,               // ambientVoice = off
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    /** Ambient sound ON, level 20 */
    val AMBIENT = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x01,               // totalEffect = ON
        0x01,               // mode = ambient
        0x14,               // level = 20
        0x00,               // ambientVoice = off
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    /** ANC/Ambient OFF */
    val ANC_OFF = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x00,               // totalEffect = OFF
        0x00,               // mode = N/A
        0x14,               // level = 20
        0x00,               // ambientVoice = off
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
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