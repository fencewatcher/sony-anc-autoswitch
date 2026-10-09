package com.fencewatcher.sonyanc

/** EQ presets for WH-1000XM6, from xm6-control reverse-engineering. */
enum class EQPreset(val id: Int, val displayName: String) {
    OFF(0x00, "Off (Flat)"),
    HEAVY(0x30, "Heavy"),
    CLEAR(0x31, "Clear"),
    HARD(0x32, "Hard"),
    SOFT(0x33, "Soft"),
    CUSTOM(0xA0, "Custom"),
    USER1(0xA1, "User 1"),
    USER2(0xA2, "User 2"),
    USER3(0xA3, "User 3"),
    USER4(0xA4, "User 4"),
    USER5(0xA5, "User 5");

    companion object {
        fun fromId(id: Int): EQPreset =
            entries.firstOrNull { it.id == id } ?: OFF

        /**
         * Only the Custom and User slots hold a curve that can be written back;
         * the Sony presets are fixed. The split is the 0xA0 boundary, which is
         * why it is expressed as a range rather than a list of names.
         */
        fun isEditable(id: Int): Boolean = id >= CUSTOM.id
    }
}