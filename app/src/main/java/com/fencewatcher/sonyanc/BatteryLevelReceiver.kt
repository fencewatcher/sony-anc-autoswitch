package com.fencewatcher.sonyanc

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Records the battery level the *Android Bluetooth stack* reports for the
 * headphones. The stack receives it over the standard profiles (HFP/AVRCP),
 * so samples keep flowing whenever the headphones are connected to the phone
 * at all — including when the MDR link is down or the service is stopped —
 * and they are pushed on change rather than polled.
 *
 * The broadcast is a protected system broadcast: only the system can send it,
 * but any app may listen for it. It is coarser than the MDR poll (~10%
 * steps) and serves as a gap filler for the tracker, which otherwise goes
 * blind the moment the headphones stop talking MDR.
 */
class BatteryLevelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BATTERY_LEVEL_CHANGED) return
        val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level !in 1..100) return
        val device = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DEVICE)
        } catch (_: Exception) {
            null
        }
        val address = device?.address ?: return
        BatteryHistory.record(context, address, level, BatteryHistory.SOURCE_SYSTEM)
    }

    companion object {
        // Hidden in the SDK on some versions — referenced by string value.
        private const val ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
