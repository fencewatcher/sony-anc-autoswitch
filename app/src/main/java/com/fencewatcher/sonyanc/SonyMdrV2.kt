package com.fencewatcher.sonyanc

import java.io.ByteArrayOutputStream

/**
 * Sony MDR-v2 protocol layer.
 *
 * The v2 transport has **two independent command tables**, selected by the frame
 * type byte, and the *same* command byte means different things in each:
 *
 *  - [Table.T1] frame type 0x0C — main feature table (ANC, EQ, power, system, audio)
 *  - [Table.T2] frame type 0x0E — peripheral/extended table (multipoint, voice guidance,
 *    party mode, safe listening, LE Audio)
 *
 * Sending a T2 command over a T1 frame (or vice-versa) silently fails on real
 * hardware, which is why capability probing and correct table selection matter.
 *
 * Every constant below is read from the open-source protocol definitions:
 *  - mos9527/SonyHeadphonesClient (libmdr, ProtocolV2T1.hpp / ProtocolV2T2.hpp)
 *  - ruimartins23/xm6-control (live WH-1000XM6 capture)
 *  - Gadgetbridge SonyProtocolImplV2
 */
object SonyMdrV2 {

    const val SOF = 0x3E
    const val EOF = 0x3C
    const val ESC = 0x3D

    /** Frame type byte → which command table the payload belongs to. */
    enum class Table(val frameType: Int) {
        /** Main table: ambient/NC, EQ, power, system, audio params. */
        T1(0x0C),

        /** Peripheral table: multipoint device list, source switch, voice guidance. */
        T2(0x0E);

        companion object {
            fun of(frameType: Int): Table = if (frameType == 0x0E) T2 else T1
        }
    }

    // ---- Connection / capability discovery (T1) ----

    const val CMD_CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CMD_CONNECT_GET_SUPPORT_FUNCTION = 0x06

    // ---- Power family (identical in both tables, 0x20..0x29) ----

    const val CMD_POWER_GET_STATUS = 0x22
    const val CMD_POWER_RET_STATUS = 0x23
    const val CMD_POWER_SET_STATUS = 0x24
    const val CMD_POWER_NTFY_STATUS = 0x25
    const val CMD_POWER_GET_PARAM = 0x26
    const val CMD_POWER_RET_PARAM = 0x27
    const val CMD_POWER_SET_PARAM = 0x28
    const val CMD_POWER_NTFY_PARAM = 0x29

    /** PowerInquiredType */
    const val POWER_TYPE_BATTERY = 0x00
    const val POWER_TYPE_POWER_OFF = 0x03
    const val POWER_TYPE_AUTO_POWER_OFF = 0x04
    const val POWER_TYPE_AUTO_POWER_OFF_WEARING = 0x05

    /** PowerOffSettingValue — immediately shut the headphones down. */
    const val POWER_SETTING_USER_POWER_OFF = 0x01

    /**
     * AutoPowerOffWearingDetectionElements (POWER subtype 0x05).
     * XM6 firmware only accepts the two wearing-detection values; the timed
     * options of older 1000X models are rejected and answered with a NTFY
     * re-announcing the previous value.
     */
    object AutoPowerOff {
        const val FIVE_MIN = 0x00
        const val THIRTY_MIN = 0x01
        const val SIXTY_MIN = 0x02
        const val ONE_EIGHTY_MIN = 0x03
        const val FIFTEEN_MIN = 0x04
        const val WHEN_TAKEN_OFF = 0x10
        const val NEVER = 0x11

        val XM6_SUPPORTED = listOf(WHEN_TAKEN_OFF, NEVER)
        fun label(v: Int) = when (v) {
            FIVE_MIN -> "5 min"
            FIFTEEN_MIN -> "15 min"
            THIRTY_MIN -> "30 min"
            SIXTY_MIN -> "60 min"
            ONE_EIGHTY_MIN -> "3 hours"
            WHEN_TAKEN_OFF -> "When taken off"
            NEVER -> "Never"
            else -> "Unknown (0x%02x)".format(v)
        }
    }

    /** Immediately power the headphones off (POWER_SET_STATUS / POWER_OFF / USER_POWER_OFF). */
    fun buildPowerOff(): ByteArray = byteArrayOf(
        CMD_POWER_SET_STATUS.toByte(), POWER_TYPE_POWER_OFF.toByte(), POWER_SETTING_USER_POWER_OFF.toByte(),
    )

    fun buildAutoPowerOffGet(): ByteArray = byteArrayOf(CMD_POWER_GET_PARAM.toByte(), POWER_TYPE_AUTO_POWER_OFF_WEARING.toByte())
    fun buildAutoPowerOffSet(mode: Int): ByteArray = byteArrayOf(
        CMD_POWER_SET_PARAM.toByte(), POWER_TYPE_AUTO_POWER_OFF_WEARING.toByte(), mode.toByte(), 0x00,
    )

    // ---- Ambient sound control / ANC (T1, 0x60..0x69) ----

    const val CMD_NCASM_GET_PARAM = 0x66
    const val CMD_NCASM_RET_PARAM = 0x67
    const val CMD_NCASM_SET_PARAM = 0x68
    const val CMD_NCASM_NTFY_PARAM = 0x69

    /** The XM6 coordinator does not declare ANC-2/wind hardware; 0x15 is the correct subtype. */
    const val NCASM_SUBTYPE_STANDARD = 0x15

    // ---- Equalizer (T1, 0x50..0x5B) ----

    const val CMD_EQ_GET_PARAM = 0x56
    const val CMD_EQ_RET_PARAM = 0x57
    const val CMD_EQ_SET_PARAM = 0x58
    const val CMD_EQ_NTFY_PARAM = 0x59

    /**
     * EQEBB subtype. The XM6 only answers subtype 0x04 (PRESET_EQ_AND_ERRORCODE);
     * the classic 0x00 query is silently ignored, and writes echo back on 0x04.
     */
    const val EQ_SUBTYPE_PRESET_AND_ERROR = 0x04
    const val EQ_BAND_COUNT = 10
    const val EQ_BAND_OFFSET = 6
    val EQ_BAND_RANGE = -6..6

    // ---- System family (T1, 0xF0..0xFD) — shared by several features ----

    const val CMD_SYSTEM_GET_PARAM = 0xF6
    const val CMD_SYSTEM_RET_PARAM = 0xF7
    const val CMD_SYSTEM_SET_PARAM = 0xF8
    const val CMD_SYSTEM_NTFY_PARAM = 0xF9
    const val CMD_SYSTEM_GET_EXT_PARAM = 0xFA
    const val CMD_SYSTEM_RET_EXT_PARAM = 0xFB
    const val CMD_SYSTEM_SET_EXT_PARAM = 0xFC
    const val CMD_SYSTEM_NTFY_EXT_PARAM = 0xFD

    /** SystemInquiredType */
    const val SYS_TYPE_PLAYBACK_CONTROL_BY_WEARING = 0x01
    const val SYS_TYPE_ASSIGNABLE_SETTINGS = 0x03
    const val SYS_TYPE_VOICE_ASSISTANT = 0x04
    const val SYS_TYPE_WEARING_STATUS = 0x06
    const val SYS_TYPE_SMART_TALKING = 0x0C
    const val SYS_TYPE_QUICK_ACCESS = 0x0D

    // ---- Audio params (T1, 0xE0..0xE9) — DSEE / BGM / upmix ----

    const val CMD_AUDIO_GET_PARAM = 0xE6
    const val CMD_AUDIO_RET_PARAM = 0xE7
    const val CMD_AUDIO_SET_PARAM = 0xE8
    const val CMD_AUDIO_NTFY_PARAM = 0xE9

    /** AudioInquiredType */
    const val AUDIO_TYPE_UPSCALING = 0x01 // DSEE Extreme
    const val AUDIO_TYPE_BGM_MODE = 0x03
    const val AUDIO_TYPE_UPMIX_CINEMA = 0x04
    const val AUDIO_TYPE_BGM_AND_ERRORCODE = 0x09

    /** Enable byte for the listening-mode params is **inverted** on the wire. */
    fun inverted(on: Boolean) = if (on) 0x00 else 0x01

    fun buildUpscalingGet(): ByteArray = byteArrayOf(CMD_AUDIO_GET_PARAM.toByte(), AUDIO_TYPE_UPSCALING.toByte())
    fun buildUpscalingSet(on: Boolean): ByteArray = byteArrayOf(
        CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_UPSCALING.toByte(), inverted(on).toByte(),
    )

    fun buildBgmGet(): ByteArray = byteArrayOf(CMD_AUDIO_GET_PARAM.toByte(), AUDIO_TYPE_BGM_AND_ERRORCODE.toByte())
    fun buildBgmSet(on: Boolean, roomSize: Int = 0x04): ByteArray = byteArrayOf(
        CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_BGM_AND_ERRORCODE.toByte(),
        inverted(on).toByte(), roomSize.toByte(),
    )

    fun buildUpmixGet(): ByteArray = byteArrayOf(CMD_AUDIO_GET_PARAM.toByte(), AUDIO_TYPE_UPMIX_CINEMA.toByte())
    fun buildUpmixSet(on: Boolean): ByteArray = byteArrayOf(
        CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_UPMIX_CINEMA.toByte(), inverted(on).toByte(),
    )

    // ---- Speak-to-chat (T1, SYSTEM + SYSTEM_EXT) ----

    fun buildSpeakToChatGet(): ByteArray = byteArrayOf(CMD_SYSTEM_GET_PARAM.toByte(), SYS_TYPE_SMART_TALKING.toByte())

    /** Speak-to-chat enable is inverted on v2: 0x00 = enabled, 0x01 = disabled. */
    fun buildSpeakToChatSet(on: Boolean): ByteArray = byteArrayOf(
        CMD_SYSTEM_SET_PARAM.toByte(), SYS_TYPE_SMART_TALKING.toByte(), inverted(on).toByte(), 0x01,
    )

    fun buildSpeakToChatConfigGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_EXT_PARAM.toByte(), SYS_TYPE_SMART_TALKING.toByte())

    fun buildSpeakToChatConfigSet(sensitivity: Int, timeout: Int): ByteArray = byteArrayOf(
        CMD_SYSTEM_SET_EXT_PARAM.toByte(), SYS_TYPE_SMART_TALKING.toByte(),
        sensitivity.toByte(), timeout.toByte(),
    )

    // ---- Pause when taken off (T1) — GET and SET ride *different* families ----

    fun buildPauseWhenTakenOffGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_PARAM.toByte(), SYS_TYPE_PLAYBACK_CONTROL_BY_WEARING.toByte())

    fun buildPauseWhenTakenOffSet(on: Boolean): ByteArray = byteArrayOf(
        CMD_SYSTEM_SET_PARAM.toByte(), SYS_TYPE_PLAYBACK_CONTROL_BY_WEARING.toByte(), inverted(on).toByte(),
    )

    // =====================================================================
    // Peripheral / multipoint family — **Table 2 only** (frame type 0x0E)
    // =====================================================================

    const val CMD_PERI_GET_CAPABILITY = 0x30
    const val CMD_PERI_RET_CAPABILITY = 0x31
    const val CMD_PERI_GET_STATUS = 0x32
    const val CMD_PERI_RET_STATUS = 0x33
    const val CMD_PERI_SET_STATUS = 0x34
    const val CMD_PERI_NTFY_STATUS = 0x35
    const val CMD_PERI_GET_PARAM = 0x36
    const val CMD_PERI_RET_PARAM = 0x37
    const val CMD_PERI_SET_PARAM = 0x38
    const val CMD_PERI_NTFY_PARAM = 0x39
    const val CMD_PERI_SET_EXT_PARAM = 0x3C
    const val CMD_PERI_NTFY_EXT_PARAM = 0x3D

    /** PeripheralInquiredType */
    const val PERI_TYPE_DEVICE_MANAGEMENT = 0x00
    const val PERI_TYPE_SOURCE_SWITCH = 0x01
    const val PERI_TYPE_DEVICE_MANAGEMENT_COD = 0x02
    const val PERI_TYPE_MUSIC_HAND_OVER = 0x03

    /** SourceSwitchControlResult */
    const val SOURCE_SWITCH_SUCCESS = 0x00

    /**
     * Query the multipoint device list (T2).
     *
     * Subtype 0x02 (…WITH_BLUETOOTH_CLASS_OF_DEVICE) is the one confirmed against
     * a live XM6; subtype 0x00 is the classic-BT variant whose entries are 3 bytes
     * shorter (no class-of-device).
     */
    fun buildDeviceListGet(withClassOfDevice: Boolean = true): ByteArray = byteArrayOf(
        CMD_PERI_GET_PARAM.toByte(),
        (if (withClassOfDevice) PERI_TYPE_DEVICE_MANAGEMENT_COD else PERI_TYPE_DEVICE_MANAGEMENT).toByte(),
    )

    /** Probe whether the peripheral family answers at all, before showing any UI. */
    fun buildPeripheralCapabilityGet(): ByteArray =
        byteArrayOf(CMD_PERI_GET_CAPABILITY.toByte(), PERI_TYPE_DEVICE_MANAGEMENT_COD.toByte())

    /**
     * Switch the active playback source to [mac] (T2).
     * The address is 17 ASCII characters, e.g. "AA:BB:CC:DD:EE:FF".
     */
    fun buildSourceSwitchSet(mac: String): ByteArray? {
        val b = mac.trim().uppercase().encodeToByteArray()
        if (b.size != 17) return null
        return byteArrayOf(CMD_PERI_SET_EXT_PARAM.toByte(), PERI_TYPE_SOURCE_SWITCH.toByte()) + b
    }

    fun buildMusicHandOverGet(): ByteArray =
        byteArrayOf(CMD_PERI_GET_PARAM.toByte(), PERI_TYPE_MUSIC_HAND_OVER.toByte())

    /**
     * Parse a device-list reply ([CMD_PERI_RET_PARAM] / [CMD_PERI_NTFY_PARAM]).
     *
     * Layout: `[cmd, subtype, count, entries…, playbackStatus]`
     * entry: 17B MAC ASCII, 1B connectedStatus, [3B class-of-device], 1B nameLen, name.
     * The active playback device is the entry whose connectedStatus equals the
     * trailing playbackStatus byte.
     */
    fun decodeDeviceList(p: ByteArray): List<MultipointDevice>? {
        if (p.size < 4) return null
        val subtype = p[1].toInt() and 0xFF
        if (subtype != PERI_TYPE_DEVICE_MANAGEMENT_COD && subtype != PERI_TYPE_DEVICE_MANAGEMENT) return null
        val hasCod = subtype == PERI_TYPE_DEVICE_MANAGEMENT_COD
        val count = p[2].toInt() and 0xFF
        if (count == 0 || count > 15) return emptyList()
        val playbackStatus = p[p.size - 1].toInt() and 0xFF

        val out = ArrayList<MultipointDevice>(count)
        var i = 3
        repeat(count) {
            if (i + 17 > p.size) return null
            val mac = String(p.copyOfRange(i, i + 17), Charsets.US_ASCII)
            i += 17
            if (i >= p.size) return null
            val status = p[i].toInt() and 0xFF
            i += 1
            var cod = 0
            if (hasCod) {
                if (i + 3 > p.size) return null
                cod = ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
                i += 3
            }
            if (i >= p.size) return null
            val nameLen = p[i].toInt() and 0xFF
            i += 1
            if (i + nameLen > p.size) return null
            val name = String(p.copyOfRange(i, i + nameLen), Charsets.UTF_8)
            i += nameLen
            out.add(
                MultipointDevice(
                    mac = mac,
                    name = name.ifBlank { mac },
                    connectedStatus = status,
                    classOfDevice = cod,
                    isActive = status > 0 && status == playbackStatus,
                ),
            )
        }
        return out
    }

    /** Decode a source-switch result notify: `[cmd, 0x01, result, 17B MAC]`. */
    fun decodeSourceSwitchResult(p: ByteArray): Pair<Int, String>? {
        if (p.size < 4) return null
        if ((p[1].toInt() and 0xFF) != PERI_TYPE_SOURCE_SWITCH) return null
        val result = p[2].toInt() and 0xFF
        val mac = if (p.size >= 21) String(p.copyOfRange(3, 20), Charsets.US_ASCII) else ""
        return result to mac
    }

    // =====================================================================
    // Generic boolean "is this NTFY telling me feature X is on/off"
    // =====================================================================

    fun decodeInvertedFlag(p: ByteArray, subtype: Int, valueIndex: Int = 2): Boolean? {
        if (p.size <= valueIndex) return null
        if ((p[1].toInt() and 0xFF) != subtype) return null
        return when (p[valueIndex].toInt() and 0xFF) {
            0x00 -> true   // inverted: 0x00 = enabled
            0x01 -> false
            else -> null
        }
    }

    // ---- Play family (T1, 0xA0..0xA9) — media volume ----

    const val CMD_PLAY_GET_STATUS = 0xA2
    const val CMD_PLAY_RET_STATUS = 0xA3
    const val CMD_PLAY_SET_STATUS = 0xA4
    const val CMD_PLAY_NTFY_STATUS = 0xA5
    const val CMD_PLAY_GET_PARAM = 0xA6
    const val CMD_PLAY_RET_PARAM = 0xA7
    const val CMD_PLAY_SET_PARAM = 0xA8
    const val CMD_PLAY_NTFY_PARAM = 0xA9

    /** PlayInquiredType */
    const val PLAY_TYPE_PLAYBACK_CONTROL = 0x03
    const val PLAY_TYPE_MUSIC_VOLUME = 0x20
    const val PLAY_TYPE_CALL_VOLUME = 0x21

    fun buildMusicVolumeGet(): ByteArray = byteArrayOf(CMD_PLAY_GET_STATUS.toByte(), PLAY_TYPE_MUSIC_VOLUME.toByte())
    fun buildMusicVolumeSet(volume: Int): ByteArray = byteArrayOf(
        CMD_PLAY_SET_STATUS.toByte(), PLAY_TYPE_MUSIC_VOLUME.toByte(), volume.coerceIn(0, 20).toByte(), 0x00,
    )

    // ---- Voice guidance (T2 only! 0x40..0x48) ----
    //
    // On table 1 these bytes mean LE Audio, which is why voice guidance has to be
    // driven over the peripheral frame or it lands on the wrong feature entirely.

    const val CMD_VOICE_GUIDANCE_GET_PARAM = 0x46
    const val CMD_VOICE_GUIDANCE_RET_PARAM = 0x47
    const val CMD_VOICE_GUIDANCE_SET_PARAM = 0x48

    /** VoiceGuidanceInquiredType */
    const val VOICE_TYPE_ON_OFF = 0x03
    const val VOICE_TYPE_VOLUME = 0x20

    fun buildVoiceGuidanceVolumeGet(): ByteArray =
        byteArrayOf(CMD_VOICE_GUIDANCE_GET_PARAM.toByte(), VOICE_TYPE_VOLUME.toByte())
    fun buildVoiceGuidanceVolumeSet(volume: Int): ByteArray = byteArrayOf(
        CMD_VOICE_GUIDANCE_SET_PARAM.toByte(), VOICE_TYPE_VOLUME.toByte(), volume.coerceIn(0, 15).toByte(), 0x00,
    )

    // ---- Assignable button / sensor + call capture (T1 SYSTEM) ----

    const val SYS_TYPE_CALL_SETTINGS = 0x08

    fun buildAssignableSettingsGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_PARAM.toByte(), SYS_TYPE_ASSIGNABLE_SETTINGS.toByte())
    fun buildAssignableSettingsSet(bytes: ByteArray): ByteArray =
        byteArrayOf(CMD_SYSTEM_SET_PARAM.toByte(), SYS_TYPE_ASSIGNABLE_SETTINGS.toByte()) + bytes

    fun buildCallSettingsGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_PARAM.toByte(), SYS_TYPE_CALL_SETTINGS.toByte())
    fun buildCallSettingsSet(captureVoice: Boolean): ByteArray = byteArrayOf(
        CMD_SYSTEM_SET_PARAM.toByte(), SYS_TYPE_CALL_SETTINGS.toByte(), inverted(captureVoice).toByte(),
    )

    // ---- Framing ----

    /** `SOF(0x3E) | TYPE SEQ LEN(4, BE) PAYLOAD CHECKSUM | EOF(0x3C)`, body+checksum escaped. */
    fun buildFrame(seq: Int, payload: ByteArray, table: Table): ByteArray {
        val size = payload.size
        val body = ByteArrayOutputStream(size + 6).apply {
            write(table.frameType)
            write(seq and 0xFF)
            write((size shr 24) and 0xFF)
            write((size shr 16) and 0xFF)
            write((size shr 8) and 0xFF)
            write(size and 0xFF)
            write(payload)
        }.toByteArray()
        val checksum = (body.sumOf { it.toInt() and 0xFF } % 256).toByte()
        return byteArrayOf(SOF.toByte()) + escape(body + checksum) + byteArrayOf(EOF.toByte())
    }

    private fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        for (b in data) {
            when (val v = b.toInt() and 0xFF) {
                SOF -> { out.write(ESC); out.write(0x2E) }
                EOF -> { out.write(ESC); out.write(0x2C) }
                ESC -> { out.write(ESC); out.write(0x2D) }
                else -> out.write(v)
            }
        }
        return out.toByteArray()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}

/** One entry of the headphones' multipoint connection list. */
data class MultipointDevice(
    val mac: String,
    val name: String,
    val connectedStatus: Int,
    val classOfDevice: Int,
    val isActive: Boolean,
) {
    val isConnected: Boolean get() = connectedStatus != 0
}
