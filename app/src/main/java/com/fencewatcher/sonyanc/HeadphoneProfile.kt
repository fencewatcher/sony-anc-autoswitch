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

    // ---- XM5 (V1 protocol, 9-byte frame over a 7-byte NcAsmParam) ----

    // Verified against mos9527/SonyHeadphonesClient (libmdr, ProtocolV1T1.hpp):
    //
    //   * MDR_SERVICE_UUID_XM5 is "956C7B26-…", documented as "XM5s and newer".
    //     "96CC203E-…" is MDR_SERVICE_UUID_LEGACY, for XM4s and older. This
    //     profile previously used the legacy UUID, which is XM4-and-older and
    //     would not have been offered by an XM5 at all.
    //   * NCASM_SET_PARAM = 0x68, and the parameter block is laid out as
    //     type, ncAsmEffect, ncType, ncValue, asmType, asmId, asmValue — not
    //     the totalEffect/mode/av/level order that was assumed here before.
    object Xm5 : HeadphoneProfile("WH-1000XM5") {
        override val serviceUuid = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
        override val ancInquiry = byteArrayOf(0x66, 0x18)   // NCASM_GET_PARAM

        private const val CMD_SET = 0x68
        private const val CORR = 0x18
        private const val TYPE_NC_AND_ASM = 0x02            // NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE
        private const val EFFECT_OFF = 0x00
        private const val EFFECT_ON = 0x01
        private const val SETTING_ON_OFF = 0x00
        private const val SETTING_LEVEL = 0x01
        private const val ASM_ID_NORMAL = 0x00

        private fun payload(effect: Int, ncLevel: Int, asmLevel: Int) = byteArrayOf(
            CMD_SET.toByte(), CORR.toByte(),
            TYPE_NC_AND_ASM.toByte(),
            effect.toByte(),
            SETTING_LEVEL.toByte(), ncLevel.coerceIn(1, 20).toByte(),
            SETTING_LEVEL.toByte(), ASM_ID_NORMAL.toByte(), asmLevel.coerceIn(1, 20).toByte(),
        )

        override fun ancOn(level: Int) = payload(EFFECT_ON, level, level)
        override fun ancOff(level: Int) = payload(EFFECT_OFF, level, level)
        override fun ambient(level: Int, voice: Boolean, noiseAdaptive: Boolean) = payload(
            EFFECT_ON, 0, level,
        )

        override fun describe(payload: ByteArray): String {
            // command, correlation, then the 7-byte parameter block
            if (payload.size < 9 || (payload[0].toInt() and 0xFF) != CMD_SET) return "?"
            val effect = payload[3].toInt() and 0xFF
            val ncLevel = payload[5].toInt() and 0xFF
            val asmLevel = payload[8].toInt() and 0xFF
            return when (effect) {
                EFFECT_OFF -> "Off (NC $ncLevel, ambient $asmLevel)"
                EFFECT_ON -> "On (NC $ncLevel, ambient $asmLevel)"
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

        /** XM4 and older. Not claimed by any profile here; fallback only. */
        private const val LEGACY_UUID = "96cc203e-5068-46ad-b32d-e316f5e069ba"

        /**
         * Tried in order during RFCOMM connection. XM5 and newer share one UUID;
         * the legacy entry is XM4 and older, kept only as a last-ditch fallback.
         */
        val allUuids = listOf(Xm6.serviceUuid, Xm5.serviceUuid, LEGACY_UUID)
            .distinct()
    }
}