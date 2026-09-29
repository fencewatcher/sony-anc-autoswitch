package com.fencewatcher.sonyanc

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Customisable "what should happen when…" rules.
 *
 * The app originally hard-coded one behaviour: media playing → noise cancelling,
 * media stopped → ambient. That is only one possible policy, and it is not
 * necessarily the one you want (many people want ANC *always* on, and use the
 * headphones' own ambient mode when they need to hear the world).
 *
 * A [Rule] binds a [Trigger] to a [RuleAction]. Rules are evaluated in order and
 * the first enabled match wins, so ordering is meaningful and a user can put a
 * specific rule above a general one.
 */
object Automation {

    private const val TAG = "Automation"
    const val PREFS = "anc_automation"

    // ---- Triggers ----

    enum class Trigger(val id: String, val label: String) {
        PLAYBACK_START("playback_start", "Playback starts"),
        PLAYBACK_STOP("playback_stop", "Playback stops"),
        DEVICE_CONNECTED("device_connected", "Headphones connect"),
        DEVICE_DISCONNECTED("device_disconnected", "Headphones disconnect"),
        BATTERY_LOW("battery_low", "Battery drops below 20%"),
        HEADPHONES_ON("headphones_on", "Headphones put on"),
        HEADPHONES_OFF("headphones_off", "Headphones taken off");

        companion object {
            fun from(id: String) = values().firstOrNull { it.id == id } ?: PLAYBACK_START
        }
    }

    // ---- Actions ----

    enum class ActionType(val id: String, val label: String) {
        NONE("none", "Do nothing"),
        SET_MODE("set_mode", "Set noise control"),
        SET_AMBIENT_LEVEL("set_ambient_level", "Set ambient level"),
        SET_VOLUME("set_volume", "Set volume"),
        SET_EQ_PRESET("set_eq_preset", "Set EQ preset"),
        // The remaining headphone features, so a routine can reach everything the
        // Audio tab can. Each reuses the frame builder the UI already uses.
        SET_SPEAK_TO_CHAT("speak_to_chat", "Speak-to-chat"),
        SET_PAUSE_TAKEN_OFF("pause_taken_off", "Pause when taken off"),
        SET_DSEE("dsee", "DSEE Extreme"),
        SET_BGM("bgm", "BGM (music player)"),
        SET_UPMIX("upmix", "Upmix / Cinema"),
        SET_VOICE_PASSTHROUGH("voice_passthrough", "Voice passthrough"),
        SET_AUTO_AMBIENT("auto_ambient", "Auto ambient (adaptive)"),
        SET_AUTO_POWER("auto_power", "Auto power off"),
        SET_VOICE_GUIDANCE("voice_guidance", "Voice guidance volume");

        companion object {
            fun from(id: String) = values().firstOrNull { it.id == id } ?: NONE

            /** Actions whose value is a simple on/off flag. */
            val TOGGLES = setOf(
                SET_SPEAK_TO_CHAT, SET_PAUSE_TAKEN_OFF, SET_DSEE, SET_BGM,
                SET_UPMIX, SET_VOICE_PASSTHROUGH, SET_AUTO_AMBIENT,
            )
        }
    }

    /** Auto power-off target, for [ActionType.SET_AUTO_POWER]. */
    enum class AutoPower(val id: String, val label: String) {
        NEVER("never", "Never turn off"),
        WHEN_TAKEN_OFF("taken_off", "When taken off");

        companion object {
            fun from(id: String) = values().firstOrNull { it.id == id } ?: WHEN_TAKEN_OFF
        }
    }

    /** Noise-control target for [ActionType.SET_MODE]. */
    enum class Mode(val id: String, val label: String) {
        NC("nc", "Noise cancelling"),
        AMBIENT("ambient", "Ambient"),
        OFF("off", "Off");

        companion object {
            fun from(id: String) = values().firstOrNull { it.id == id } ?: NC
        }
    }

    data class Action(
        val type: ActionType = ActionType.NONE,
        val mode: Mode = Mode.NC,
        /** Ambient level 0–20, volume 0–20, or 0/1 for the on/off actions. */
        val value: Int = 0,
        /** EQ preset id (0x00–0x33 built-in, 0xA0/0xA1/0xA2 user slots). */
        val presetId: Int = 0,
        /** Target for [ActionType.SET_AUTO_POWER]. */
        val power: AutoPower = AutoPower.WHEN_TAKEN_OFF,
    )

    data class Rule(
        val id: String,
        val trigger: Trigger,
        val action: Action,
        val enabled: Boolean = true,
    )

    // ---- Storage ----

    fun load(context: Context): List<Rule> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rules", null)
        if (raw.isNullOrBlank()) return defaultRules()
        return runCatching { parse(JSONArray(raw)) }
            .getOrElse {
                Log.w(TAG, "Rule parse failed, using defaults: ${it.message}")
                defaultRules()
            }
    }

    fun save(context: Context, rules: List<Rule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("rules", encode(rules).toString()).apply()
    }

    /** The factory default reproduces the original hard-coded behaviour. */
    fun defaultRules(): List<Rule> = listOf(
        Rule("start", Trigger.PLAYBACK_START, Action(ActionType.SET_MODE, Mode.NC), true),
        Rule("stop", Trigger.PLAYBACK_STOP, Action(ActionType.SET_MODE, Mode.AMBIENT), true),
    )

    // ---- Serialisation ----

    fun encode(rules: List<Rule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id)
                put("trigger", r.trigger.id)
                put("enabled", r.enabled)
                put("action", JSONObject().apply {
                    put("type", r.action.type.id)
                    put("mode", r.action.mode.id)
                    put("value", r.action.value)
                    put("presetId", r.action.presetId)
                    put("power", r.action.power.id)
                })
            })
        }
    }

    private fun parse(array: JSONArray): List<Rule> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val a = o.optJSONObject("action")
        Rule(
            id = o.optString("id", "rule$i"),
            trigger = Trigger.from(o.optString("trigger")),
            enabled = o.optBoolean("enabled", true),
            action = Action(
                type = ActionType.from(a?.optString("type") ?: "none"),
                mode = Mode.from(a?.optString("mode") ?: "nc"),
                value = a?.optInt("value", 0) ?: 0,
                presetId = a?.optInt("presetId", 0) ?: 0,
                power = AutoPower.from(a?.optString("power") ?: "taken_off"),
            ),
        )
    }

    /** First enabled rule matching [trigger]; `null` means "do nothing". */
    fun resolve(rules: List<Rule>, trigger: Trigger): Rule? =
        rules.firstOrNull { it.enabled && it.trigger == trigger }

    fun describe(rule: Rule): String {
        val t = rule.action.type
        if (t in ActionType.TOGGLES) return "${t.label}: ${if (rule.action.value != 0) "on" else "off"}"
        return when (t) {
            ActionType.NONE -> "Do nothing"
            ActionType.SET_MODE -> "Set ${rule.action.mode.label.lowercase()}"
            ActionType.SET_AMBIENT_LEVEL -> "Set ambient level to ${rule.action.value}"
            ActionType.SET_VOLUME -> "Set volume to ${rule.action.value}"
            ActionType.SET_EQ_PRESET -> "Set EQ to ${presetName(rule.action.presetId)}"
            ActionType.SET_AUTO_POWER -> "Auto power off: ${rule.action.power.label.lowercase()}"
            ActionType.SET_VOICE_GUIDANCE -> "Voice guidance volume ${rule.action.value}"
            else -> t.label
        }
    }

    fun presetName(id: Int): String = when (id) {
        0x00 -> "Off"
        0x30 -> "Heavy"
        0x31 -> "Clear"
        0x32 -> "Hard"
        0x33 -> "Soft"
        0xA0 -> "Custom"
        0xA1 -> "User 1"
        0xA2 -> "User 2"
        else -> "0x%02x".format(id)
    }
}
