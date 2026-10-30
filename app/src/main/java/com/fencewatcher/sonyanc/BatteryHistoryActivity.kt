package com.fencewatcher.sonyanc

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.fencewatcher.sonyanc.databinding.ActivityBatteryHistoryBinding

/**
 * The battery history screen.
 *
 * Lives behind the battery card rather than on Home: Home answers "what is it
 * doing right now", and a multi-day graph there would compete with the one
 * number people actually came for.
 */
class BatteryHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatteryHistoryBinding
    private var address: String? = null
    private var days = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        address = intent.getStringExtra(EXTRA_ADDRESS)

        binding.btnRange3.setOnClickListener { days = 3; render() }
        binding.btnRange7.setOnClickListener { days = 7; render() }
        binding.btnRange14.setOnClickListener { days = 14; render() }
        render()
    }

    private fun render() {
        val addr = address
        if (addr.isNullOrBlank()) {
            binding.textSummary.text = "Open this from the battery card once the headphones have been connected."
            return
        }
        val now = System.currentTimeMillis()
        val start = now - days * 24L * 60 * 60 * 1000
        val samples = BatteryHistory.historyFor(addr, start)
        binding.graph.show(samples, start, now)

        select(binding.btnRange3, days == 3)
        select(binding.btnRange7, days == 7)
        select(binding.btnRange14, days == 14)

        val stats = BatteryHistory.drainByMode(addr, start)
        binding.textSummary.text = if (stats.isEmpty()) {
            if (samples.isEmpty()) "No samples yet. The app records one every minute while connected."
            else "Not enough discharge in each mode yet — a few hours per mode are needed before a rate is meaningful."
        } else {
            buildString {
                append("Measured drain, last $days days:\n\n")
                stats.forEach { s ->
                    val hours = (s.observedMs / 3_600_000.0 * 10).toInt() / 10.0
                    append("• ${BatteryHistory.modeLabel(s.mode)}: ")
                    append("%.1f%%/h".format(s.perHour))
                    append("  (${hours}h observed)\n")
                }
                append("\nA full charge would last about ")
                val best = stats.minByOrNull { it.perHour }
                if (best != null) append("%.0f h".format(100.0 / best.perHour))
                append(" in ${BatteryHistory.modeLabel(best?.mode ?: 1).lowercase()}.")
            }
        }
    }

    private fun select(button: Button, active: Boolean) {
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (active) android.graphics.Color.rgb(78, 117, 156) else android.graphics.Color.rgb(58, 58, 62),
        )
    }

    companion object {
        const val EXTRA_ADDRESS = "battery_history_address"
    }
}
