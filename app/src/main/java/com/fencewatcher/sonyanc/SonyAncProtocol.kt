package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony MDR-v2 frame building, escaping, and ACK/decoder helpers.
 * Supports both frame types: 0x0C (standard command) and 0x0E (peripheral/multipoint).
 *
 * Payload construction is handled by [HeadphoneProfile] per model.
 */
object SonyAncProtocol {

    private const val SOF: Byte = 0x3E
    private const val EOF: Byte = 0x3C
    private const val ESC: Byte = 0x3D

    /** Build a complete MDR frame (SOF + escaped body + EOF). */
    fun buildFrame(seq: Int, payload: ByteArray, type: Int = 0x0C): ByteArray {
        require(seq in 0..1) { "seq must be 0 or 1, got $seq" }
        val size = payload.size
        val body = ByteArrayOutputStream(size + 6).apply {
            write(type)               // frame type byte
            write(seq)                 // sequence number
            // 4-byte big-endian length
            write((size shr 24 and 0xFF))
            write((size shr 16 and 0xFF))
            write((size shr 8 and 0xFF))
            write(size and 0xFF)
            write(payload)
        }.toByteArray()
        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()
        val escaped = escape(body + checksum)
        return byteArrayOf(SOF) + escaped + byteArrayOf(EOF)
    }

    private fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        for (b in data) {
            val ub = b.toInt() and 0xFF
            when (ub) {
                0x3E -> { out.write(ESC.toInt()); out.write(0x2E) }
                0x3C -> { out.write(ESC.toInt()); out.write(0x2C) }
                0x3D -> { out.write(ESC.toInt()); out.write(0x2D) }
                else -> out.write(ub)
            }
        }
        return out.toByteArray()
    }

    fun describePayload(payload: ByteArray): String =
        payload.joinToString(" ") { "%02x".format(it) }

    // ---- Multipoint device list (PERIPHERAL family, frame type 0x0E) ----

    /** Build GET request for the multipoint device list (0x36 0x02). */
    fun buildDeviceListGet(): ByteArray = byteArrayOf(0x36, 0x02)

    /** Parse a device list reply (0x37/0x39 payload). Returns null on parse failure. */
    fun decodeDeviceList(payload: ByteArray): List<MultipointDevice>? {
        if (payload.size < 4) return null
        val cmd = payload[0].toInt() and 0xFF
        if (cmd != 0x37 && cmd != 0x39) return null
        val subtype = payload[1].toInt() and 0xFF
        if (subtype != 0x02 && subtype != 0x00) return null
        val withClassOfDevice = subtype == 0x02
        val count = payload[2].toInt() and 0xFF
        if (count < 1 || count > 15) return emptyList()
        val playbackStatus = payload.last().toInt() and 0xFF

        val devices = mutableListOf<MultipointDevice>()
        var i = 3
        for (n in 0 until count) {
            if (i + 17 > payload.size) return null
            val mac = String(payload.sliceArray(i until i + 17), Charsets.UTF_8)
            i += 17
            if (i >= payload.size) return null
            val status = payload[i].toInt() and 0xFF
            i += 1
            if (withClassOfDevice) i += 3 // skip class-of-device bytes
            if (i >= payload.size) return null
            val nameLen = payload[i].toInt() and 0xFF
            i += 1
            if (i + nameLen > payload.size) return null
            val name = String(payload.sliceArray(i until i + nameLen), Charsets.UTF_8)
            i += nameLen
            devices.add(MultipointDevice(
                macAddress = mac,
                name = name,
                connectedStatus = status,
                isPlayback = status > 0 && status == playbackStatus,
            ))
        }
        return devices
    }

    /** Build a SOURCE_SWITCH_SET command to switch playback to a given MAC (0x3C 0x01 + 17-byte MAC). */
    fun buildSourceSwitchSet(macAddress: String): ByteArray? {
        val macBytes = macAddress.encodeToByteArray()
        if (macBytes.size != 17) return null
        return byteArrayOf(0x3C, 0x01) + macBytes
    }

    // ---- Auto power off (0x28 0x05) ----

    fun buildAutoPowerOffGet(): ByteArray = byteArrayOf(0x26, 0x05)
    fun buildAutoPowerOffSet(mode: Byte): ByteArray = byteArrayOf(0x28, 0x05, mode, 0x00)

    fun decodeAutoPowerOff(payload: ByteArray): Int? {
        if (payload.size < 4 || payload[1].toInt() and 0xFF != 0x05) return null
        return payload[2].toInt() and 0xFF
    }

    // ---- Pause-when-taken-off (0xF8 0x01) ----

    fun buildPauseWhenTakenOffGet(): ByteArray = byteArrayOf(0xF6.toByte(), 0x01)
    fun buildPauseWhenTakenOffSet(enabled: Boolean): ByteArray =
        byteArrayOf(0xF8.toByte(), 0x01, if (enabled) 0x00 else 0x01)

    fun decodePauseWhenTakenOff(payload: ByteArray): Boolean? {
        if (payload.size < 3 || payload[1].toInt() and 0xFF != 0x01) return null
        return payload[2].toInt() and 0xFF == 0x00
    }
}

/** A device in the headphones' multipoint connection list. */
data class MultipointDevice(
    val macAddress: String,
    val name: String,
    /** Non-zero when connected (the byte value may vary between 0x01 and 0x03). */
    val connectedStatus: Int,
    /** True when this device is the current playback audio source. */
    val isPlayback: Boolean,
)