package com.fencewatcher.sonyanc

import android.util.Log

/**
 * Protocol profiles for different Sony headphone models.
 * Encapsulates the service UUID, handshake, and ANC payload format
 * differences between XM5, XM6, and other models.
 *
 * Detection is by device name; default is XM6 (most capable).
 */
sealed class HeadphoneProfile(val modelName: String) {

    /** RFCOMM service UUID for SDP lookup. */
    abstract val serviceUuid: String

    /** ANC inquiry command sent during handshake (e.g., 66 19 for XM6). */
    abstract val ancInquiry: ByteArray

    /** Build an ANC ON (noise cancelling) payload. */
    abstract fun ancOn(level: Int): ByteArray

    /** Build an ANC OFF payload. */
    abstract fun ancOff(level: Int): ByteArray

    /** Build an ambient-sound payload with settings. */
    abstract fun ambient(level: Int, voice: Boolean, noiseAdaptive: Boolean): ByteArray

    /** Human-readable description of a payload. */
    abstract fun describe(payload: ByteArray): String

    // ---- XM6 (9-byte payload) ----

    object Xm6 : HeadphoneProfile("WH-1000XM6") {
        override val serviceUuid = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
        override val ancInquiry = byteArrayOf(0x66, 0x19)

        // 9-byte: 68 19 01 <enable> <mode> <av> <level> <na> <naSens>
        override fun ancOn(level: Int) = byteArrayOf(
            0x68, 0x19, 0x01, 0x01, 0x00, 0x00, level.coerceIn(1, 20).toByte(), 0x00, 0x00,
        )
        override fun ancOff(level: Int) = byteArrayOf(
            0x68, 0x19, 0x01, 0x00, 0x00, 0x00, level.coerceIn(1, 20).toByte(), 0x00, 0x00,
        )
        override fun ambient(level: Int, voice: Boolean, noiseAdaptive: Boolean) = byteArrayOf(
            0x68, 0x19, 0x01,
            0x01,
            0x01,
            if (voice) 0x01 else 0x00,
            level.coerceIn(1, 20).toByte(),
            if (noiseAdaptive) 0x01 else 0x00,
            0x00,
        )
        override fun describe(payload: ByteArray): String {
            if (payload.size < 9 || (payload[0].toInt() and 0xFF) != 0x68) return "?"
            val enable = payload[3].toInt() and 0xFF
            val mode = payload[4].toInt() and 0xFF
            val level = payload[6].toInt() and 0xFF
            return when {
                enable == 0 -> "Off"
                mode == 0 -> "NC"
                mode == 1 -> "Ambient $level"
                else -> "?"
            }
        }
    }

    // ---- XM5 (7-byte payload) ----

    object Xm5 : HeadphoneProfile("WH-1000XM5") {
        override val serviceUuid = "96cc203e-5068-46ad-b32d-e316f5e069ba"
        override val ancInquiry = byteArrayOf(0x66, 0x18)

        // 7-byte: 68 18 01 <totalEffect> <mode> <av> <level>
        // totalEffect: 0=off, 1=NC, 2=ambient
        override fun ancOn(level: Int) = byteArrayOf(
            0x68, 0x18, 0x01, 0x01, 0x00, 0x00, level.coerceIn(1, 20).toByte(),
        )
        override fun ancOff(level: Int) = byteArrayOf(
            0x68, 0x18, 0x01, 0x00, 0x00, 0x00, level.coerceIn(1, 20).toByte(),
        )
        override fun ambient(level: Int, voice: Boolean, noiseAdaptive: Boolean) = byteArrayOf(
            0x68, 0x18, 0x01,
            0x02,               // totalEffect = ambient
            if (voice) 0x01 else 0x00,  // mode (effectively av for XM5)
            0x00,               // av
            level.coerceIn(1, 20).toByte(),
        )
        override fun describe(payload: ByteArray): String {
            if (payload.size < 7 || (payload[0].toInt() and 0xFF) != 0x68) return "?"
            val totalEffect = payload[3].toInt() and 0xFF
            val level = payload[6].toInt() and 0xFF
            return when (totalEffect) {
                0 -> "Off"
                1 -> "NC"
                2 -> "Ambient $level"
                else -> "?"
            }
        }
    }

    // ---- Factory ----

    companion object {
        private const val TAG = "Profile"

        /** Detect model from the Bluetooth device name. Default to XM6. */
        fun detect(name: String): HeadphoneProfile {
            if (name.contains("XM6", ignoreCase = true)) return Xm6
            if (name.contains("XM5", ignoreCase = true)) return Xm5
            Log.w(TAG, "Unknown model: $name — using XM6 protocol as fallback")
            return Xm6
        }

        /** All known service UUIDs, tried in order during RFCOMM connection. */
        val allUuids = listOf(Xm6.serviceUuid, Xm5.serviceUuid)
    }
}