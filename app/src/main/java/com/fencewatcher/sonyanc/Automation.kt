package com.fencewatcher.sonyanc

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Customisable "what should happen when…" rules.
 *
 * The app originally hard-coded one behaviour: media playing → noise cancelling,
 * media stopped → ambient. That is only one possible policy, and it is not
 * necessarily the one you want (many people want ANC *always* on, and use the
 * headphones' own ambient mode when they need to hear the world).
 *
 * A [Rule] binds a [Trigger] to an ordered list of [Action]s, optionally gated
 * by [Condition]s. Rules are evaluated in order and the first enabled match
 * wins, so ordering is meaningful and a user can put a specific rule above a
 * general one.
 *
 * One rule carrying several actions is what makes the feature worth having:
 * "when playback starts, set NC *and* EQ Clear *and* volume 12" is one thought,
 * and splitting it into three rules that share a trigger would have made the
 * first one win and silently ignore the other two.
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

    // ---- Conditions ----

    enum class ConditionType(val id: String, val label: String) {
        TIME_WINDOW("time_window", "Time window"),
        WORN("worn", "Worn state"),
        BATTERY_ABOVE("battery_above", "Battery above"),

        /** Common presets, so the editor does not need a seven-checkbox row. */
        ;

        companion object {
            fun from(id: String) = values().firstOrNull { it.id == id } ?: TIME_WINDOW
        }
    }

    data class Condition(
        val type: ConditionType = ConditionType.TIME_WINDOW,
        /** ISO days, 1 = Monday … 7 = Sunday. Empty means every day. */
        val days: Set<Int> = emptySet(),
        val startMinute: Int = 22 * 60,
        val endMinute: Int = 6 * 60,
        val worn: Boolean = true,
        val percent: Int = 50,
    )

    /** Runtime state a condition is evaluated against. */
    data class State(
        val worn: Boolean? = null,
        val batteryPercent: Int? = null,
        val now: Long = System.currentTimeMillis(),
    )

    data class Rule(
        val id: String,
        val trigger: Trigger,
        val actions: List<Action> = listOf(Action()),
        val conditions: List<Condition> = emptyList(),
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
        Rule("start", Trigger.PLAYBACK_START, listOf(Action(ActionType.SET_MODE, Mode.NC))),
        Rule("stop", Trigger.PLAYBACK_STOP, listOf(Action(ActionType.SET_MODE, Mode.AMBIENT))),
    )

    // ---- Evaluation ----

    /** First enabled rule for [trigger] whose conditions hold right now. */
    fun firstMatch(rules: List<Rule>, trigger: Trigger, state: State): Rule? =
        rules.firstOrNull { it.enabled && it.trigger == trigger && conditionsMet(it, state) }

    fun conditionsMet(rule: Rule, state: State): Boolean =
        rule.conditions.all { met(it, state) }

    private fun met(c: Condition, s: State): Boolean = when (c.type) {
        // An unknown wear state must not read as "not worn": that would fire the
        // wrong branch of a rule before the headphones have answered. Skipping
        // the rule is the safer failure.
        ConditionType.WORN -> s.worn != null && s.worn == c.worn
        ConditionType.BATTERY_ABOVE -> s.batteryPercent != null && s.batteryPercent >= c.percent
        ConditionType.TIME_WINDOW -> inWindow(c, s.now)
    }

    private fun inWindow(c: Condition, now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val iso = isoDay(cal.get(Calendar.DAY_OF_WEEK))
        val start = c.startMinute.coerceIn(0, 1439)
        val end = c.endMinute.coerceIn(0, 1439)
        fun dayOk(d: Int) = c.days.isEmpty() || d in c.days

        // start == end reads as "all day": a zero-width window would silently
        // disable a rule the user believes is active.
        if (start == end) return dayOk(iso)
        if (start < end) return dayOk(iso) && minute in start until end

        // Wraps midnight: the evening half belongs to the start day, the morning
        // half to the day after it.
        val previousIso = if (iso == 1) 7 else iso - 1
        return (dayOk(iso) && minute >= start) || (dayOk(previousIso) && minute < end)
    }

    /** Calendar day (Sunday = 1) to ISO day (Monday = 1). */
    private fun isoDay(calendarDay: Int): Int = if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1

    // ---- Serialisation ----

    fun encode(rules: List<Rule>): JSONArray = JSONArray().apply {
        rules.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id)
                put("trigger", r.trigger.id)
                put("enabled", r.enabled)
                put("actions", JSONArray().apply { r.actions.forEach { put(it.toJson()) } })
                put("conditions", JSONArray().apply { r.conditions.forEach { put(it.toJson()) } })
            })
        }
    }

    private fun Action.toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("mode", mode.id)
        put("value", value)
        put("presetId", presetId)
        put("power", power.id)
    }

    private fun Condition.toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("days", JSONArray().apply { days.sorted().forEach { put(it) } })
        put("startMinute", startMinute)
        put("endMinute", endMinute)
        put("worn", worn)
        put("percent", percent)
    }

    private fun parse(array: JSONArray): List<Rule> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null

        // Legacy shape: a single "action" object, no "actions" array. Reading it
        // keeps existing installs' rules alive across the upgrade instead of
        // silently resetting them to the factory pair.
        val actions = o.optJSONArray("actions")?.let { arr ->
            (0 until arr.length()).mapNotNull { j -> arr.optJSONObject(j)?.toAction() }
        } ?: listOfNotNull(o.optJSONObject("action")?.toAction())

        Rule(
            id = o.optString("id", "rule$i"),
            trigger = Trigger.from(o.optString("trigger")),
            actions = actions.ifEmpty { listOf(Action()) },
            conditions = o.optJSONArray("conditions")?.let { arr ->
                (0 until arr.length()).mapNotNull { j -> arr.optJSONObject(j)?.toCondition() }
            } ?: emptyList(),
            enabled = o.optBoolean("enabled", true),
        )
    }

    private fun JSONObject.toAction(): Action = Action(
        type = ActionType.from(optString("type", "none")),
        mode = Mode.from(optString("mode", "nc")),
        value = optInt("value", 0),
        presetId = optInt("presetId", 0),
        power = AutoPower.from(optString("power", "taken_off")),
    )

    private fun JSONObject.toCondition(): Condition {
        val days = mutableSetOf<Int>()
        optJSONArray("days")?.let { arr -> for (k in 0 until arr.length()) days.add(arr.optInt(k, 0)) }
        return Condition(
            type = ConditionType.from(optString("type", "time_window")),
            days = days.filter { it in 1..7 }.toSet(),
            startMinute = optInt("startMinute", 22 * 60),
            endMinute = optInt("endMinute", 6 * 60),
            worn = optBoolean("worn", true),
            percent = optInt("percent", 50),
        )
    }

    // ---- Presentation ----

    fun describe(rule: Rule): String {
        val body = rule.actions
            .filter { it.type != ActionType.NONE }
            .joinToString(" + ") { describeAction(it) }
            .ifEmpty { "Do nothing" }
        val cond = describeConditions(rule.conditions)
        return if (cond.isEmpty()) body else "$body, $cond"
    }

    fun describeAction(a: Action): String {
        if (a.type in ActionType.TOGGLES) return "${a.type.label}: ${if (a.value != 0) "on" else "off"}"
        return when (a.type) {
            ActionType.NONE -> "Do nothing"
            ActionType.SET_MODE -> "Set ${a.mode.label.lowercase()}"
            ActionType.SET_AMBIENT_LEVEL -> "Set ambient level to ${a.value}"
            ActionType.SET_VOLUME -> "Set volume to ${a.value}"
            ActionType.SET_EQ_PRESET -> "Set EQ to ${presetName(a.presetId)}"
            ActionType.SET_AUTO_POWER -> "Auto power off: ${a.power.label.lowercase()}"
            ActionType.SET_VOICE_GUIDANCE -> "Voice guidance volume ${a.value}"
            else -> a.type.label
        }
    }

    fun describeConditions(conditions: List<Condition>): String {
        if (conditions.isEmpty()) return ""
        val parts = conditions.map { c ->
            when (c.type) {
                ConditionType.WORN -> if (c.worn) "when worn" else "when taken off"
                ConditionType.BATTERY_ABOVE -> "above ${c.percent}%"
                ConditionType.TIME_WINDOW ->
                    "${clock(c.startMinute)}–${clock(c.endMinute)} on ${daysLabel(c.days)}"
            }
        }
        return "only " + parts.joinToString(" and ")
    }

    fun daysLabel(days: Set<Int>): String = when {
        days.isEmpty() || days.size == 7 -> "every day"
        days == setOf(1, 2, 3, 4, 5) -> "weekdays"
        days == setOf(6, 7) -> "weekends"
        else -> days.sorted().joinToString(", ") {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1]
        }
    }

    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun toMinute(hour: Int, minute: Int): Int = hour * 60 + minute

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
