package com.fencewatcher.sonyanc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent battery samples per device, and the time-to-empty estimator
 * built on top of them.
 *
 * Samples come from two sources: the MDR poll (precise, but only while the
 * RFCOMM link is up) and the Android Bluetooth stack, which receives battery
 * over the standard profiles whenever the headphones are connected at all —
 * including when the MDR link is down or the service is stopped.
 *
 * Estimation is deliberately conservative. A charge event — a rise that is
 * corroborated and seen within one source, not a jump between the 1%-granular
 * MDR poll and the ~10%-step stack feed — ends the current discharge segment;
 * only the segment since the last charge supports a slope. When that segment
 * is still too young (right after a charge or a reconnect), the newest earlier
 * segment supplies a historical rate instead, so the field degrades to a
 * coarse "≈ 5h" rather than blanking to "--". The result is tiered:
 *   - null            → not enough data / charging / stale   → shown as "--"
 *   - reliable=false  → computable but thin                  → "≈ 5h"
 *   - reliable=true   → sustained, recent, gap-free          → "≈ 4h 12m"
 */
object BatteryHistory {

    const val SOURCE_MDR = 0
    const val SOURCE_SYSTEM = 1

    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_SAMPLES_PER_DEVICE = 3000

    /** Identical level inside this window adds no information. */
    private const val DEDUPE_WINDOW_MS = 30_000L

    /** A same-source drop this steep inside [IMPLAUSIBLE_WINDOW_MS] is a
     *  misread, not a discharge: at any real drain rate the level cannot fall
     *  ten points in five minutes. Keeping such a sample poisons both the
     *  segment and the slope. */
    private const val IMPLAUSIBLE_DROP = 10
    private const val IMPLAUSIBLE_WINDOW_MS = 5L * 60 * 1000

    /** Minimum span of a discharge segment before a slope is believable. */
    private const val MIN_SEGMENT_SPAN_MS = 10L * 60 * 1000

    /** Span/drop thresholds for the "reliable" tier. */
    private const val RELIABLE_SPAN_MS = 45L * 60 * 1000
    private const val RELIABLE_MIN_DROP = 3

    /** Newest sample older than this makes any estimate "less accurate". */
    private const val FRESH_WINDOW_MS = 30L * 60 * 1000

    /** Newest sample older than this: too stale to extrapolate at all. */
    private const val STALE_WINDOW_MS = 6L * 60 * 60 * 1000

    /** A silent stretch this long inside a segment means the headphones were
     *  away; a slope across it underestimates the drain. */
    private const val MAX_GAP_MS = 2L * 60 * 60 * 1000

    /** A same-source rise this large is charging, not a noisy reading. */
    private const val CHARGE_MIN_JUMP = 2

    /** A rise this recent that the level has not fallen below means charging is
     *  still going on — the level is pinned at the wall, not draining. */
    private const val CHARGE_ACTIVE_MS = 15L * 60 * 1000

    /** While an MDR reading is this recent, its 1% level beats the stack's ~10% steps. */
    private const val MDR_LEVEL_WINDOW_MS = 15L * 60 * 1000

    data class Sample(val t: Long, val p: Int, val source: Int, val ctx: Int = 0)

    data class Estimate(val minutes: Int, val reliable: Boolean)

    /** Measured drain for one noise-control mode, over the sampled window. */
    data class DrainStat(val mode: Int, val perHour: Double, val observedMs: Long)

    private val lock = Any()
    private val byDevice = LinkedHashMap<String, MutableList<Sample>>()
    private var loaded = false

    fun record(context: Context, address: String, percent: Int, source: Int, ctx: Int = 0) {
        if (percent !in 1..100 || address.isBlank()) return
        synchronized(lock) {
            loadLocked(context)
            val list = byDevice.getOrPut(address) { mutableListOf() }
            val now = System.currentTimeMillis()
            if (list.isNotEmpty()) {
                val last = list.last()
                if (now - last.t < DEDUPE_WINDOW_MS && last.p == percent) return
                if (last.source == source && last.p - percent > IMPLAUSIBLE_DROP &&
                    now - last.t < IMPLAUSIBLE_WINDOW_MS
                ) return
            }
            list.add(Sample(now, percent, source, ctx))
            while (list.isNotEmpty() && now - list.first().t > MAX_AGE_MS) list.removeAt(0)
            while (list.size > MAX_SAMPLES_PER_DEVICE) list.removeAt(0)
            saveLocked(context)
        }
    }

    /**
     * Time-to-empty in minutes for [address], or null when it cannot be
     * provided.
     *
     * [currentContext] is the compact context marker the headphones are in
     * right now (see the service). When set, the drain rate is computed from
     * samples taken under the same context — playing-with-NC drains roughly
     * twice as fast as idle-with-ambient, so this is the biggest accuracy
     * lever — falling back to the blended segment rate when the matching set
     * is too thin. The *level* is always the freshest reading overall.
     */
    fun estimate(address: String, currentContext: Int = -1): Estimate? {
        synchronized(lock) {
            val list = byDevice[address] ?: return null
            if (list.size < 3) return null

            val lastLevel = currentLevel(list)
            val ageMs = System.currentTimeMillis() - list.last().t
            if (ageMs > STALE_WINDOW_MS) return null

            val events = chargeEvents(list)
            val lastEvent = events.lastOrNull()

            // The level is still at or above the last rise, and that rise is
            // recent: charging is in progress, so any projection would be
            // fiction. (A drop below it means the cable just came out.)
            if (lastEvent != null &&
                list.last().p >= list[lastEvent].p &&
                System.currentTimeMillis() - list[lastEvent].t <= CHARGE_ACTIVE_MS
            ) return null

            // The current discharge segment: everything since the last charge.
            // Trim a leading plateau — equal consecutive levels carry no slope,
            // and after a charge the level can sit pinned at 100 for a while,
            // which would dilute the drain rate.
            var start = if (lastEvent != null) lastEvent + 1 else 0
            while (start < list.size - 1 && list[start].p == list[start + 1].p) start++
            val segment = if (list.size - start >= 3) list.subList(start, list.size) else emptyList()

            // Preferred rate: the current segment, context-matched when possible.
            val current = if (segment.isEmpty()) null else rate(segment, currentContext)
            if (current != null && current.perHour > 0.0) {
                val minutes = (lastLevel / current.perHour * 60.0).toInt().coerceIn(0, 24 * 60)
                // Reliability is judged on the set that produced the rate (span
                // and drop) but on the raw segment's continuity — gaps between
                // *matching* samples are expected when the context alternates,
                // while a silent stretch in the raw stream means the headphones
                // were away and the slope across it lies.
                val reliable = current.spanMs >= RELIABLE_SPAN_MS &&
                    current.drop >= RELIABLE_MIN_DROP &&
                    ageMs <= FRESH_WINDOW_MS &&
                    maxGapMs(segment) <= MAX_GAP_MS
                return Estimate(minutes, reliable)
            }

            // The current segment is too young to hold a slope — exactly the
            // state right after a charge or a reconnect. Returning null here is
            // what made the estimate look reset; the newest earlier segment
            // still describes this battery, so use its rate and say it is rough.
            val fallback = earlierRate(list, events, currentContext) ?: return null
            val minutes = (lastLevel / fallback * 60.0).toInt().coerceIn(0, 24 * 60)
            return Estimate(minutes, false)
        }
    }

    /**
     * Indices where a discharge segment ends: a rise that is evidence of
     * charging. Two rules stop ordinary sampling from faking one:
     *  - both samples must come from the same source. The stack reports ~10%
     *    steps while MDR reports 1%, so a switch between them produces jumps in
     *    both directions that say nothing about the battery — this is what made
     *    the estimate reset every time the service restarted or the link
     *    reconnected with a coarse reading above the last MDR one.
     *  - a single-point rise needs corroboration: the next reading must hold
     *    the gain. A lone +1 that falls straight back is read noise, and
     *    splitting there would throw away hours of good slope.
     */
    private fun chargeEvents(list: List<Sample>): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 1 until list.size) {
            val prev = list[i - 1]
            val cur = list[i]
            if (cur.p <= prev.p || cur.source != prev.source) continue
            val corroborated = i + 1 < list.size &&
                list[i + 1].source == cur.source && list[i + 1].p >= cur.p
            if (cur.p - prev.p >= CHARGE_MIN_JUMP || corroborated) out.add(i)
        }
        return out
    }

    /** Freshest believable level: a recent MDR reading beats the coarser stack one. */
    private fun currentLevel(list: List<Sample>): Int {
        val last = list.last()
        val mdr = list.lastOrNull { it.source == SOURCE_MDR }
        return if (mdr != null && last.t - mdr.t <= MDR_LEVEL_WINDOW_MS) mdr.p else last.p
    }

    private fun maxGapMs(samples: List<Sample>): Long {
        var max = 0L
        for (i in 1 until samples.size) max = maxOf(max, samples[i].t - samples[i - 1].t)
        return max
    }

    private data class Rate(val perHour: Double, val spanMs: Long, val drop: Int)

    /** Least-squares rate for [samples], preferring the context-matched subset. */
    private fun rate(samples: List<Sample>, currentContext: Int): Rate? {
        if (samples.size < 3) return null
        val matched = if (currentContext >= 0) samples.filter { it.ctx == currentContext } else emptyList()
        val matchedViable = matched.size >= 3 &&
            (matched.last().t - matched.first().t) >= MIN_SEGMENT_SPAN_MS &&
            (matched.first().p - matched.last().p) >= 1
        val used = if (matchedViable) matched else samples
        val spanMs = used.last().t - used.first().t
        if (spanMs < MIN_SEGMENT_SPAN_MS) return null
        val drop = used.first().p - used.last().p
        if (drop < 1) return null   // flat (charging, or idle at a wall)
        return Rate(leastSquaresRatePerHour(used), spanMs, drop)
    }

    /**
     * Rate from the newest earlier discharge segment, for the window where the
     * current one is still too young to measure.
     */
    private fun earlierRate(list: List<Sample>, events: List<Int>, currentContext: Int): Double? {
        val starts = mutableListOf(0)
        for (e in events) starts.add(e + 1)
        val ends = events.toMutableList().also { it.add(list.size) }
        for (i in starts.indices.reversed()) {
            if (i == starts.lastIndex) continue          // the current segment
            val s = starts[i]
            val e = ends[i]
            if (e - s < 3) continue
            val r = rate(list.subList(s, e), currentContext) ?: continue
            if (r.perHour > 0.0) return r.perHour
        }
        return null
    }

    /**
     * Drain rate in %/hour from a least-squares fit — more robust than
     * endpoints when samples come from two sources with different
     * granularity. Positive while discharging, negative while charging.
     */
    private fun leastSquaresRatePerHour(samples: List<Sample>): Double {
        val n = samples.size
        if (n < 2) return 0.0
        val ts = DoubleArray(n)
        val ps = DoubleArray(n)
        for ((i, s) in samples.withIndex()) {
            ts[i] = (s.t - samples.first().t) / 1000.0
            ps[i] = s.p.toDouble()
        }
        val meanT = ts.average()
        val meanP = ps.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (ts[i] - meanT) * (ps[i] - meanP)
            den += (ts[i] - meanT) * (ts[i] - meanT)
        }
        if (den <= 0.0) return 0.0
        return -(num / den) * 3600.0
    }

    /** "≈ 4h 12m" when reliable; "≈ 5h" (no minutes) when the data is thin. */
    fun format(e: Estimate): String {
        val h = e.minutes / 60
        val m = e.minutes % 60
        return if (e.reliable) {
            if (h > 0) "≈ ${h}h ${m}m" else "≈ ${m}m"
        } else {
            "≈ ${kotlin.math.ceil(e.minutes / 60.0).toInt().coerceAtLeast(1)}h"
        }
    }

    // ---- History access for the graph screen ----

    /** A copy of the samples for [address], oldest first. */
    fun historyFor(address: String, since: Long = 0L): List<Sample> = synchronized(lock) {
        byDevice[address]?.filter { it.t >= since } ?: emptyList()
    }

    /**
     * Drain per noise-control mode, measured within discharge segments only.
     *
     * Charging stretches and long silent gaps are excluded for the same reason
     * the estimator excludes them: a slope taken across a charge or across an
     * absence describes nothing. This is the number behind the estimate, shown
     * so it can be argued with.
     */
    fun drainByMode(address: String, since: Long): List<DrainStat> = synchronized(lock) {
        val list = (byDevice[address] ?: return emptyList()).filter { it.t >= since }
        if (list.size < 4) return emptyList()
        val events = chargeEvents(list)
        val bounds = events.toMutableList().also { it.add(list.size) }
        val drop = mutableMapOf<Int, Int>()
        val span = mutableMapOf<Int, Long>()
        var from = 0
        for (end in bounds) {
            // Walk one discharge segment [from, end).
            for (i in from + 1 until end) {
                val prev = list[i - 1]
                val cur = list[i]
                val dt = cur.t - prev.t
                if (dt <= 0 || dt > MAX_GAP_MS) continue
                val mode = (prev.ctx shr 2) and 3
                if (mode == 0 || mode != ((cur.ctx shr 2) and 3)) continue
                val fall = prev.p - cur.p
                if (fall <= 0) continue
                drop[mode] = (drop[mode] ?: 0) + fall
                span[mode] = (span[mode] ?: 0L) + dt
            }
            from = end + 1
        }
        drop.mapNotNull { (mode, d) ->
            val ms = span[mode] ?: return@mapNotNull null
            if (ms < 20L * 60 * 1000) return@mapNotNull null
            DrainStat(mode, d.toDouble() / (ms / 3_600_000.0), ms)
        }.sortedBy { it.mode }
    }

    /** Mode bits (0 unknown, 1 NC, 2 ambient, 3 off) → label. */
    fun modeLabel(mode: Int): String = when (mode) {
        1 -> "Noise cancelling"
        2 -> "Ambient"
        3 -> "Off"
        else -> "Unknown"
    }

    private fun storeFile(context: Context) = File(context.filesDir, "battery_history.json")

    private fun loadLocked(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = storeFile(context)
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            for (key in obj.keys()) {
                val arr = obj.optJSONArray(key) ?: continue
                val list = mutableListOf<Sample>()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    list.add(Sample(s.optLong("t"), s.optInt("p"), s.optInt("s", SOURCE_MDR), s.optInt("c", 0)))
                }
                byDevice[key] = list
            }
        } catch (_: Exception) {
            // A broken history file costs the estimate, not the app.
        }
    }

    private fun saveLocked(context: Context) {
        try {
            val obj = JSONObject()
            for ((key, list) in byDevice) {
                val arr = JSONArray()
                for (s in list) {
                    arr.put(JSONObject().put("t", s.t).put("p", s.p).put("s", s.source).put("c", s.ctx))
                }
                obj.put(key, arr)
            }
            val f = storeFile(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
