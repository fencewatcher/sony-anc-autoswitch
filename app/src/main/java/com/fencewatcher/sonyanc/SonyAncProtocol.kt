package com.fencewatcher.sonyanc

/**
 * Backwards-compatible shim for the original single-table framing helper.
 *
 * The real protocol work now lives in [SonyMdrV2], which models the two
 * independent v2 command tables (T1 frame 0x0C / T2 frame 0x0E) and the full
 * verified command map. This file only remains so older call sites keep
 * compiling; new code should use [SonyMdrV2] directly.
 */
object SonyAncProtocol {

    /** Builds a frame on the main command table (T1, frame type 0x0C). */
    fun buildFrame(seq: Int, payload: ByteArray, type: Int = 0x0C): ByteArray =
        SonyMdrV2.buildFrame(seq, payload, SonyMdrV2.Table.of(type))

    fun describePayload(payload: ByteArray): String = SonyMdrV2.hex(payload)
}
