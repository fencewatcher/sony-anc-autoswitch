package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony WH-1000XM6 Bluetooth control protocol (MDR-v2 framing).
 *
 * Payload: 68 19 01 <vcs> <totalEffect> <level> <mode> <ambientVoice> <na> <naSens>
 *
 * Verified empirically: byte[5]=level, byte[6]=mode, byte[7]=ambientVoice
 *
 * Frame: SOF(0x3E) | escape(DATA_TYPE seq SIZE-bigendian-4b PAYLOAD CHECKSUM) | EOF(0x3C)
 * Escape: 0x3C→0x3D 0x2C, 0x3D→0x3D 0x2D, 0x3E→0x3D 0x2E
 */
object SonyAncProtocol {

    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D
    private const val DATA_TYPE = 0x0C

    // 68 19 01 <vcs=1> <totalEffect> <ambientVoice> <level> <mode> <na> <naSens>

    val ANC_ON = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x01,               // totalEffect = ON
        0x00,               // ambientVoice = off
        0x14,               // level = 20
        0x00,               // mode = NC
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    val AMBIENT = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x01,               // totalEffect = ON
        0x00,               // ambientVoice = off
        0x14,               // level = 20
        0x01,               // mode = ambient
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    val ANC_OFF = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // vcs
        0x00,               // totalEffect = OFF
        0x00,               // ambientVoice = off
        0x14,               // level = 20
        0x00,               // mode = N/A
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    fun buildFrame(seq: Int, payload: ByteArray): ByteArray {
        require(seq in 0..1) { "seq must be 0 or 1, got $seq" }

        val size = payload.size
        val sizeBytes = byteArrayOf(
            (size shr 24 and 0xFF).toByte(),
            (size shr 16 and 0xFF).toByte(),
            (size shr 8 and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )

        val body = java.io.ByteArrayOutputStream(size + 6).apply {
            write(DATA_TYPE); write(seq); write(sizeBytes); write(payload)
        }.toByteArray()

        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()
        val bodyWithChecksum = body + checksum
        val escaped = escape(bodyWithChecksum)
        return byteArrayOf(SOF) + escaped + byteArrayOf(EOF)
    }

    private fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
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