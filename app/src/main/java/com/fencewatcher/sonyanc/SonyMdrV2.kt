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
    const val CMD_CONNECT_RET_SUPPORT_FUNCTION = 0x07

    /** FunctionType */
    const val FUNC_SOURCE_SWITCH_CONTROL = 0x01

    /**
     * Parse the advertised T2 support-function list.
     *
     * `ConnectRetSupportFunction` is [command, inquiredType, SupportFunction...] and
     * each `SupportFunction` is `{functionType, priority}` — a flat two-byte POD
     * array, so the elements just run back to back from offset 2.
     *
     * This list arrives on **connect** (`CONNECT_RET_SUPPORT_FUNCTION`), not on the
     * peripheral capability query. Parsing `PERI_RET_CAPABILITY` instead — which
     * carries only max paired/connected counts — can never reveal whether a given
     * function is supported, which is why a support check on the wrong command
     * silently did nothing.
     */
    fun parseSupportFunctions(p: ByteArray): Set<Int> {
        val out = LinkedHashSet<Int>()
        var i = 2
        while (i + 1 < p.size) {
            out.add(p[i].toInt() and 0xFF)
            i += 2
        }
        return out
    }

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

    // ---- Active Bluetooth codec (COMMON group) ----
    //
    // Not in the AUDIO group, which is why every e2 02 probe came back silent:
    // the codec was never reported there.
    const val CMD_COMMON_GET_STATUS = 0x12
    const val CMD_COMMON_RET_STATUS = 0x13
    const val COMMON_TYPE_AUDIO_CODEC = 0x02

    // ---- Wear events (unsolicited) ----
    //
    // SYSTEM_NTFY_STATUS type 0x10. The reference calls this
    // HEAD_GESTURE_TRAINING, but on the XM6 it fires unsolicited on every
    // don/doff and lands ~120ms before the matching NCASM mode change, so it is
    // the wear event. A poll (f2 06 / f6 06) returns a frame that never varies
    // even straight across a wear transition, so the push is the only source.
    const val CMD_SYSTEM_NTFY_STATUS = 0xF5
    const val SYS_TYPE_HEAD_GESTURE_TRAINING = 0x10

    // ---- Wear status (T2) — the authoritative source ----
    //
    // T2 carries its own SystemInquiredType enum, disjoint from T1's. WEARING_STATUS_
    // CHECKER = 0x00 and the gesture-judgement type 0x08 exist only here, which is
    // why every T1 wearing probe went unanswered all day.
    const val CMD_SYSTEM_GET_STATUS_T2 = 0xF2
    const val CMD_SYSTEM_RET_STATUS_T2 = 0xF3
    const val T2_TYPE_WEARING_STATUS_CHECKER = 0x00

    const val WEAR_NORMAL = 0x00
    const val WEAR_ILLEGAL = 0x01
    const val WEAR_LEFT_NOT_WORN = 0x02
    const val WEAR_RIGHT_NOT_WORN = 0x03
    const val WEAR_BOTH_NOT_WORN = 0x04

    /** Ask for current wear state. Must be sent on the T2 table. */
    fun buildWearingStatusGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_STATUS_T2.toByte(), T2_TYPE_WEARING_STATUS_CHECKER.toByte())

    /**
     * The XM6 reports all-or-nothing in practice — LEFT/RIGHT_NOT_WEAR never came
     * back — so the per-ear codes are decoded for completeness but only NORMAL and
     * BOTH_NOT_WORN are treated as a wear change.
     */
    fun wearStatusName(v: Int): String = when (v) {
        WEAR_NORMAL -> "both worn"
        WEAR_ILLEGAL -> "illegal"
        WEAR_LEFT_NOT_WORN -> "left not worn"
        WEAR_RIGHT_NOT_WORN -> "right not worn"
        WEAR_BOTH_NOT_WORN -> "off head"
        else -> "unknown ($v)"
    }

    fun isWorn(v: Int): Boolean = v == WEAR_NORMAL

    // ---- Quick Access (T1, type 0x0D) ----
    //
    // Traced from the decompiled app. Layout is [F8, 0D, count, fn...] with an
    // unsigned count and no key byte; the write is whole-array.
    //
    // The two slots are QUICK_ACCESS1 and QUICK_ACCESS2 in AssignableSettingsFunction,
    // which is a list of *actions* alongside VOLUME_UP, PLAY_PAUSE and
    // QUICK_ATTENTION — not a left/right earcup pair. A prior trace labelled them
    // L and R on no evidence; the XM6 has one ANC button, a power button and a
    // touch panel, so that reading cannot be right.
    //
    // Caution: these are the values on the wire. The app also has a `Function`
    // enum in a different numeric space (sptf = 66, not 1) that nothing converts
    // between — do not mix the two.
    const val QUICK_ACCESS_NONE = 0x00
    const val QUICK_ACCESS_SPTF = 0x01
    const val QUICK_ACCESS_XIAO = 0x04
    const val QUICK_ACCESS_QMSC_DIRECT = 0x07

    fun buildQuickAccessEnableGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_STATUS_T1.toByte(), SYS_TYPE_QUICK_ACCESS.toByte())

    fun buildQuickAccessFunctionGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_PARAM.toByte(), SYS_TYPE_QUICK_ACCESS.toByte())

    fun buildQuickAccessFunctionSet(functions: IntArray): ByteArray {
        val out = ByteArray(3 + functions.size)
        out[0] = CMD_SYSTEM_SET_PARAM.toByte()
        out[1] = SYS_TYPE_QUICK_ACCESS.toByte()
        out[2] = (functions.size and 0xFF).toByte()
        for (i in functions.indices) out[3 + i] = (functions[i] and 0xFF).toByte()
        return out
    }

    /**
     * Only sptf → Spotify is a confident identification, though the Sony app
     * labels that entry "Spotify Tap". `xiao` and `qMscDirect` are Tencent —
     * the app binds them to one preset, TENCENT_XIAOWEI_Q_MSC — so they are
     * China-region services and will do nothing on a German account.
     *
     * The service list is region-dependent: the Sony app offers Amazon Music,
     * Endel, Spotify Tap and YouTube Music to European accounts, but only these
     * four values are ever constructed in the code we decompiled, so the wire
     * values for the others are not known here.
     */
    fun quickAccessName(v: Int): String = when (v) {
        QUICK_ACCESS_NONE -> "None"
        QUICK_ACCESS_SPTF -> "Spotify Tap"
        QUICK_ACCESS_XIAO -> "Xiaowei (Tencent)"
        QUICK_ACCESS_QMSC_DIRECT -> "Tencent Q Music"
        else -> "0x%02x".format(v)
    }

    // ---- Upscaling / DSEE indicator (T1, COMMON group type 0x03) ----
    //
    // Sibling of the codec read, which is 12 02 on the same command. Confirmed
    // there is NO disable-reason field for this feature: StatusDisableReason in
    // the app belongs to the party-speaker and heart-rate-sensor features only.
    const val COMMON_TYPE_UPSCALING_EFFECT = 0x03
    const val UPSCALING_STATUS_OFF = 0x00
    const val UPSCALING_STATUS_VALID = 0x01
    const val UPSCALING_STATUS_INVALID = 0x02

    fun buildUpscalingStatusGet(): ByteArray =
        byteArrayOf(CMD_COMMON_GET_STATUS.toByte(), COMMON_TYPE_UPSCALING_EFFECT.toByte())

    fun upscalingStatusName(v: Int): String = when (v) {
        UPSCALING_STATUS_OFF -> "off"
        UPSCALING_STATUS_VALID -> "valid"
        UPSCALING_STATUS_INVALID -> "invalid"
        else -> "0x%02x".format(v)
    }

    /**
     * Which upscaling variant the headset has selected. Traced from the app's
     * UpscalingEffectType enum.
     */
    fun upscalingEffectName(v: Int): String = when (v) {
        0x00 -> "DSEE HX"
        0x01 -> "DSEE"
        0x02 -> "DSEE HX AI"
        0x03 -> "DSEE Ultimate"
        else -> "0x%02x".format(v)
    }

    // ---- LE Audio transport (T1) ----
    //
    // Distinct from connection mode (0x02) despite sharing the Audio group.
    // Both values are independent EnableDisable flags, so the payload is four
    // bytes, not three. Confirmed from the decompiled app: the reader side lives
    // in .../tandem/features/connectionmode.
    const val AUDIO_TYPE_CONNECTION_MODE_LE_AUDIO = 0x05
    const val CMD_SYSTEM_GET_STATUS_T1 = 0xF2

    fun buildLeAudioStatusGet(): ByteArray =
        byteArrayOf(CMD_SYSTEM_GET_STATUS_T1.toByte(), AUDIO_TYPE_CONNECTION_MODE_LE_AUDIO.toByte())

    /**
     * Switching transport forces the headphones to drop the Bluetooth link, so
     * the caller should expect a reconnect.
     *
     * Which flag means what is inferred from the reader: one flag for LE Audio,
     * one for Classic Audio, mutually exclusive. The readback is the authority —
     * if this sends the pair the wrong way round, the displayed state still comes
     * from the device rather than from our guess.
     */
    fun buildLeAudioSet(leAudioOn: Boolean, classicAudioOn: Boolean): ByteArray = byteArrayOf(
        CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_CONNECTION_MODE_LE_AUDIO.toByte(),
        (if (leAudioOn) 0x00 else 0x01).toByte(),
        (if (classicAudioOn) 0x00 else 0x01).toByte(),
    )

    /** [buildAudioCodecGet] — the codec actually in use right now. */
    fun buildAudioCodecGet(): ByteArray =
        byteArrayOf(CMD_COMMON_GET_STATUS.toByte(), COMMON_TYPE_AUDIO_CODEC.toByte())

    /** Every value AudioCodec can take, so an unknown one is still readable. */
    fun codecName(v: Int): String = when (v) {
        0x00 -> "Unsettled"
        0x01 -> "SBC"
        0x02 -> "AAC"
        0x10 -> "LDAC"
        0x20 -> "aptX"
        0x21 -> "aptX HD"
        0x30 -> "LC3"
        0xFE -> "Out of range"
        0xFF -> "Other"
        else -> "0x%02x".format(v)
    }

    /** Distinct badge colour per codec, so the chip reads at a glance. */
    fun codecColor(v: Int): Int = when (v) {
        0x10 -> 0xFF2ECC71.toInt() // LDAC — green
        0x20, 0x21 -> 0xFF3498DB.toInt() // aptX family — blue
        0x30 -> 0xFF9B59B6.toInt() // LC3 — purple
        0x02 -> 0xFFE67E22.toInt() // AAC — orange
        0x01 -> 0xFF7F8C8D.toInt() // SBC — grey
        else -> 0xFF95A5A6.toInt()
    }

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
    // Connection mode. XM6 and XM5 disagree on the type byte.
    //
    // The XM6 type is CONNECTION_MODE_WITH_LDAC_STATUS (0x02), and it is the
    // same byte for set and readback. The earlier attempt used
    // CONNECTION_MODE_CLASSIC_AUDIO_LE_AUDIO (0x05), which is the LE-Audio
    // transport switch rather than the quality priority — the headphones
    // accepted the frame and then always answered 0x00, so the value never
    // changed and the toggle looked pinned.
    const val AUDIO_TYPE_CONNECTION_MODE_XM6 = 0x02 // set and read
    const val AUDIO_TYPE_CONNECTION_MODE_XM5 = 0x01 // set and read
    /**
     * The headphones answer a set with a *notify* under this type, not a readback
     * — e.g. `e8 02 01` is answered `e9 05 01 00`, roughly 100ms before the
     * `e7 02 ..` readback. Decoding it is what makes the selector feel current
     * instead of trailing the round trip.
     */
    const val AUDIO_TYPE_CONNECTION_NOTIFY = 0x05
    const val CONNECTION_SETTING_SOUND_CONNECTION = 0x00 // XM5 only
    const val PRIOR_SOUND_QUALITY = 0x00
    const val PRIOR_CONNECTION_QUALITY = 0x01

    const val CMD_AUDIO_GET_STATUS = 0xE2
    const val CMD_AUDIO_RET_STATUS = 0xE3

    /** EnableDisable is inverted: 0x00 = enabled, 0x01 = disabled. */
    fun decodeInvertedEnable(b: Int): Boolean? = when (b) {
        0x00 -> true
        0x01 -> false
        else -> null
    }

    /**
     * LDAC activity, as reported by [buildLdacStatusGet]. This is the codec
     * readout the protocol does allow: the active codec cannot be selected, but
     * whether LDAC is actually in use can be observed.
     */
    fun buildLdacStatusGet(): ByteArray =
        byteArrayOf(CMD_AUDIO_GET_STATUS.toByte(), AUDIO_TYPE_CONNECTION_MODE_XM6.toByte())

    // ---- SENSE (wearing / adaptive control) — observed, not yet driven ----

    const val CMD_SENSE_GET_CAPABILITY = 0x70
    const val CMD_SENSE_RET_CAPABILITY = 0x71
    const val CMD_SENSE_NTFY_STATUS = 0x75
    const val CMD_SENSE_NTFY_PARAM = 0x79
    const val CMD_SENSE_GET_EXT_INFO = 0x7A
    const val CMD_SENSE_RET_EXT_INFO = 0x7B
    const val SENSE_TYPE_ADAPTIVE_CONTROL = 0x00

    /** Enable byte for the listening-mode params is **inverted** on the wire. */
    fun inverted(on: Boolean) = if (on) 0x00 else 0x01

    fun buildUpscalingGet(): ByteArray = byteArrayOf(CMD_AUDIO_GET_PARAM.toByte(), AUDIO_TYPE_UPSCALING.toByte())

    /**
     * DSEE Extreme enable byte is **not** inverted — 0x01 means on.
     *
     * The upside/downmix and ambient/speak-to-chat params really are inverted, so
     * this one is a genuine exception; treating it like the others made the toggle
     * display the opposite of the real state.
     */
    fun buildUpscalingSet(on: Boolean): ByteArray = byteArrayOf(
        CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_UPSCALING.toByte(), if (on) 0x01 else 0x00,
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

    // ---- Connection mode (sound quality vs connection stability) ----

    /**
     * Note this chooses *priority*, not a codec. The protocol has no command to
     * force LDAC/aptX; the active codec is negotiated with the phone and only
     * readable, not selectable. The reference confirms the two are separate:
     * [PriorMode] here is the same pair Sony's app calls sound-quality-priority
     * vs connection-priority.
     */
    fun buildConnectionModeGet(xm5: Boolean): ByteArray = byteArrayOf(
        CMD_AUDIO_GET_PARAM.toByte(),
        (if (xm5) AUDIO_TYPE_CONNECTION_MODE_XM5 else AUDIO_TYPE_CONNECTION_MODE_XM6).toByte(),
    )

    fun buildConnectionModeSet(xm5: Boolean, soundQualityPrior: Boolean): ByteArray {
        val prior = (if (soundQualityPrior) PRIOR_SOUND_QUALITY else PRIOR_CONNECTION_QUALITY).toByte()
        // XM5: [cmd, type, settingType, prior] — it really does carry the extra byte.
        if (xm5) {
            return byteArrayOf(
                CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_CONNECTION_MODE_XM5.toByte(),
                CONNECTION_SETTING_SOUND_CONNECTION.toByte(), prior,
            )
        }
        // XM6: [cmd, type, prior]
        return byteArrayOf(
            CMD_AUDIO_SET_PARAM.toByte(), AUDIO_TYPE_CONNECTION_MODE_XM6.toByte(), prior,
        )
    }

    /**
     * Read-only SENSE probes. The XM6 SENSE semantics (ADAPTIVE_CONTROL) are not
     * understood well enough to drive, but the command exists and the notification
     * frames are the only way to learn the wearing-detection format on real hardware.
     */
    fun buildSenseCapabilityGet(type: Int = SENSE_TYPE_ADAPTIVE_CONTROL): ByteArray =
        byteArrayOf(CMD_SENSE_GET_CAPABILITY.toByte(), type.toByte())

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

    /** PeripheralBluetoothMode */
    const val PERI_BT_MODE_NORMAL = 0x00
    const val PERI_BT_MODE_INQUIRY_SCAN = 0x01

    /** ConnectivityActionType */
    const val CONN_ACTION_DISCONNECT = 0x00
    const val CONN_ACTION_CONNECT = 0x01
    const val CONN_ACTION_UNPAIR = 0x02

    /**
     * Disconnect, reconnect, or unpair a paired device.
     *
     * `PeripheralSetExtendedParamParingDeviceManagementCommon` is
     * `[PERI_SET_EXTENDED_PARAM, inquiredType, connectivityActionType, btDeviceAddress]`,
     * with the address as 17 ASCII characters, e.g. "AA:BB:CC:DD:EE:FF".
     *
     * Note the enum's API constants (`MDR_PAIRED_DEVICE_UNPAIR` = 4) are *not* the
     * wire values — the wire `ConnectivityActionType::UNPAIR` is 0x02.
     */
    fun buildPairedDeviceActionSet(action: Int, mac: String): ByteArray? {
        val addr = mac.trim()
        // Exactly 17 characters: six octets plus five colons.
        if (addr.length != 17) return null
        val out = ByteArray(20)
        out[0] = CMD_PERI_SET_EXT_PARAM.toByte()
        out[1] = PERI_TYPE_DEVICE_MANAGEMENT_COD.toByte()
        out[2] = action.toByte()
        for (i in addr.indices) out[3 + i] = addr[i].code.toByte()
        return out
    }

    /**
     * Enter or leave Bluetooth pairing mode.
     *
     * `PeripheralSetStatusParingDeviceManagementCommon` is
     * [PERI_SET_STATUS, inquiredType, btMode, enableDisableStatus], and the
     * reference confirms pairing mode is exactly
     * `btMode == INQUIRY_SCAN_MODE && enableDisableStatus == ENABLE` —
     * read back the same way from the notify/ret reply.
     *
     * The `inquiredType` is the **WITH_BLUETOOTH_CLASS_OF_DEVICE** variant
     * (0x02), not the CLASSIC_BT one the struct defaults to; the reference
     * sets it explicitly.
     */
    fun buildPairingModeSet(enter: Boolean): ByteArray = byteArrayOf(
        CMD_PERI_SET_STATUS.toByte(), PERI_TYPE_DEVICE_MANAGEMENT_COD.toByte(),
        (if (enter) PERI_BT_MODE_INQUIRY_SCAN else PERI_BT_MODE_NORMAL).toByte(),
        0x01.toByte(), // enableDisableStatus = ENABLE
    )

    /** Decode the pairing-mode state from a PERI notify/ret status reply. */
    fun decodePairingMode(p: ByteArray): Boolean? {
        if (p.size < 4) return null
        if ((p[1].toInt() and 0xFF) != PERI_TYPE_DEVICE_MANAGEMENT_COD) return null
        val enabled = (p[3].toInt() and 0xFF) == 0x01
        val inquiry = (p[2].toInt() and 0xFF) == PERI_BT_MODE_INQUIRY_SCAN
        return enabled && inquiry
    }

    /**
     * Lock / unlock automatic source switching — the Sony app calls this
     * "Fix Playback". Disabling source-switch control pins playback to whichever
     * device is already playing, so multipoint will not hand the audio over.
     *
     * Wire format is `PERI_SET_PARAM` + `SOURCE_SWITCH_CONTROL` + one value byte;
     * the query reuses the same two-byte form and is answered by
     * `PERI_RET_PARAM` + `SOURCE_SWITCH_CONTROL` + value.
     *
     * The reference's naming is inverted (`playbackFixed = !switchControlEnabled`),
     * so the flag written here is "fix playback": true means locked.
     */
    fun buildSourceSwitchControlGet(): ByteArray = byteArrayOf(
        CMD_PERI_GET_PARAM.toByte(), PERI_TYPE_SOURCE_SWITCH.toByte(),
    )

    /**
     * @param fixPlayback true = lock playback to the current device.
     *   The wire value is "source switch control ENABLED", which is the NEGATION of
     *   playback-fixed — the reference client says so outright: "Sound Connect's
     *   'Fixing playback device' is the negation of source switch control" (Client.cpp).
     *   So locking writes 0x00. Sending 0x01 here still returns result=SUCCESS while
     *   doing the opposite of what was asked, which is why this went unnoticed.
     */
    fun buildSourceSwitchControlSet(fixPlayback: Boolean): ByteArray = byteArrayOf(
        CMD_PERI_SET_PARAM.toByte(), PERI_TYPE_SOURCE_SWITCH.toByte(),
        if (fixPlayback) 0x00 else 0x01,
    )

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
     * Query the current pairing-mode state.
     *
     * Pairing mode is reported through the STATUS family
     * (`PeripheralNotifyStatusParingDeviceManagementCommon`), not the PARAM
     * family used for the device list, so this asks PERI_GET_STATUS rather than
     * PERI_GET_PARAM. The answer arrives as PERI_RET_STATUS / PERI_NTFY_STATUS.
     */
    fun buildPairingModeGet(): ByteArray =
        byteArrayOf(CMD_PERI_GET_STATUS.toByte(), PERI_TYPE_DEVICE_MANAGEMENT_COD.toByte())

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

    /**
     * PriorMode is an enum, not a boolean flag. Decoding it with a plain on/off
     * flag helper reports 0x00 (SOUND_QUALITY_PRIOR) as "off", which is what
     * made the connection-mode toggle appear stuck on connection priority: the
     * frame round-tripped fine, the answer was simply read backwards.
     */
    fun decodePriorMode(p: ByteArray, valueIndex: Int = 2): Boolean? =
        when (p.getOrNull(valueIndex)?.toInt()?.and(0xFF)) {
            PRIOR_SOUND_QUALITY -> true
            PRIOR_CONNECTION_QUALITY -> false
            else -> null
        }

    /** For params whose enable byte is plain: 0x01 = on. See [buildUpscalingSet]. */
    fun decodePlainFlag(p: ByteArray, subtype: Int, valueIndex: Int = 2): Boolean? {
        if (p.size <= valueIndex) return null
        if ((p[1].toInt() and 0xFF) != subtype) return null
        return when (p[valueIndex].toInt() and 0xFF) {
            0x01 -> true
            0x00 -> false
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

    /**
     * Voice guidance volume is a **signed** five-step scale: -2, -1, 0, +1, +2.
     *
     * `VoiceGuidanceSetParamVolume` declares `Int8 volumeValue` with range -2..2.
     * Reading that byte as unsigned turns -1 into 255, and clamping the slider to
     * a 0..5 range then pins it at the maximum — which looked exactly like a
     * broken slider. Values outside -2..2 are rejected by the device and the
     * position springs back, which is why only the first few steps ever stuck.
     */
    const val VOICE_GUIDANCE_MIN = -2
    const val VOICE_GUIDANCE_MAX = 2

    fun buildVoiceGuidanceVolumeGet(): ByteArray =
        byteArrayOf(CMD_VOICE_GUIDANCE_GET_PARAM.toByte(), VOICE_TYPE_VOLUME.toByte())

    fun buildVoiceGuidanceVolumeSet(volume: Int): ByteArray = byteArrayOf(
        CMD_VOICE_GUIDANCE_SET_PARAM.toByte(), VOICE_TYPE_VOLUME.toByte(),
        volume.coerceIn(VOICE_GUIDANCE_MIN, VOICE_GUIDANCE_MAX).toByte(), 0x00,
    )

    /** Decode a voice-guidance volume reply as the signed Int8 it actually is. */
    fun decodeVoiceGuidanceVolume(p: ByteArray, subtype: Int = VOICE_TYPE_VOLUME): Int? {
        if (p.size < 3) return null
        if ((p[1].toInt() and 0xFF) != subtype) return null
        val v = p[2].toByte().toInt()
        return if (v in VOICE_GUIDANCE_MIN..VOICE_GUIDANCE_MAX) v else null
    }

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
