package com.fencewatcher.sonyanc

/**
 * Small logging helper kept from the original single-table framing code.
 *
 * The real protocol work now lives in [SonyMdrV2], which models the two
 * independent v2 command tables (T1 frame 0x0C / T2 frame 0x0E) and the full
 * verified command map. The old `buildFrame` shim is gone — it had no callers
 * left once the service moved to `SonyMdrV2.buildFrame`, and keeping a second
 * framing path around is exactly the kind of thing that quietly drifts.
 */
object SonyAncProtocol {
    fun describePayload(payload: ByteArray): String = SonyMdrV2.hex(payload)
}
