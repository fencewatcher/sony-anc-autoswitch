package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony WH-1000XM6 Bluetooth control protocol (MDR-v2 framing).
 *
 * **9-byte payload**: 68 19 01 <enable> <mode> <ambientVoice> <level> <na> <naSens>
 *   enable (byte[3]): 0x00=OFF, 0x01=ON
 *   mode   (byte[4]): 0x00=NC, 0x01=ambient
 *   av     (byte[5]): voice passthrough (0x00=off)
 *   level  (byte[6]): 1-20 (0x14 = 20)
 *   na     (byte[7]): noiseAdaptive (0x00)
 *   naSens (byte[8]): sensitivity (0x00)
 */
object SonyAncProtocol {

    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D
    private const val DATA_TYPE = 0x0C

    val ANC_ON = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // enable = ON
        0x00,               // mode = NC
        0x00,               // ambientVoice = off
        0x14,               // level = 20
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    val AMBIENT = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,               // enable = ON
        0x01,               // mode = ambient
        0x00,               // ambientVoice = off
        0x14,               // level = 20
        0x00,               // noiseAdaptive = off
        0x00,               // naSensitivity = 0
    )

    val ANC_OFF = byteArrayOf(
        0x68, 0x19, 0x01,
        0x00,               // enable = OFF
        0x00,               // mode = N/A
        0x00,               // ambientVoice = off
        0x14,               // level = 20
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

    fun describePayload(payload: ByteArray): String = payload.joinToString(" ") { "%02x".format(it) }
}