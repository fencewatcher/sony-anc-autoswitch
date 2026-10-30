package com.fencewatcher.sonyanc

/**
 * The WH-1000XM6 wire profile — the only model this app supports.
 *
 * XM5 support was scrapped in v1.83. The XM5 protocol paths were never
 * verified against real hardware: connections half-worked (power-off only),
 * the model was misreported on the Home screen, and there is no XM5 available
 * to fix anything against. "If you're certain it will work, or scrap it" —
 * nothing here is certain enough. XM6-only also removes the silent detect()
 * fallback that could pin the wrong protocol to unknown hardware.
 */
object HeadphoneProfile {

    const val modelName: String = "WH-1000XM6"

    /** RFCOMM service UUID for SDP lookup (MDR v2). */
    const val serviceUuid: String = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

    /** ANC inquiry command sent during handshake (66 19). */
    val ancInquiry: ByteArray = byteArrayOf(0x66, 0x19)

    // 9-byte: 68 19 01 <enable> <mode> <av> <level> <na> <naSens>

    /** Build an ANC ON (noise cancelling) payload. */
    fun ancOn(level: Int): ByteArray = byteArrayOf(
        0x68, 0x19, 0x01, 0x01, 0x00, 0x00, level.coerceIn(1, 20).toByte(), 0x00, 0x00,
    )

    /** Build an ANC OFF payload. */
    fun ancOff(level: Int): ByteArray = byteArrayOf(
        0x68, 0x19, 0x01, 0x00, 0x00, 0x00, level.coerceIn(1, 20).toByte(), 0x00, 0x00,
    )

    /** Build an ambient-sound payload with settings. */
    fun ambient(level: Int, voice: Boolean, noiseAdaptive: Boolean): ByteArray = byteArrayOf(
        0x68, 0x19, 0x01,
        0x01,
        0x01,
        if (voice) 0x01 else 0x00,
        level.coerceIn(1, 20).toByte(),
        if (noiseAdaptive) 0x01 else 0x00,
        0x00,
    )

    /** Human-readable description of an ANC payload. */
    fun describe(payload: ByteArray): String {
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

    /** XM4 and older. Not claimed by any profile here; fallback only. */
    private const val LEGACY_UUID = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    /**
     * Tried in order during RFCOMM connection. The MDR v2 UUID is claimed by
     * the XM6; the legacy entry is XM4 and older, kept only as a last-ditch
     * fallback. The XM5 shares the v2 UUID but is refused by name in the
     * service — it would open a socket and then speak the wrong protocol.
     */
    val allUuids = listOf(serviceUuid, LEGACY_UUID).distinct()

    /**
     * The user-visible name of a Bluetooth device: the local alias when one
     * was set — some phones' Bluetooth settings rename a paired device locally,
     * and the alias is exactly what Android Settings then shows — otherwise
     * the advertised name. Returns null when neither is known.
     *
     * getAlias is not in the public SDK on all versions, so it is read
     * reflectively and simply skipped when the platform does not have it.
     */
    fun bluetoothDisplayName(device: android.bluetooth.BluetoothDevice): String? {
        val alias = try {
            device.javaClass.getMethod("getAlias").invoke(device) as? String
        } catch (_: Exception) {
            null
        }
        return alias?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
    }
}
