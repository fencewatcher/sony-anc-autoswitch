package com.fencewatcher.sonyanc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.STATUS_BROADCAST
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_ADDRESS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_MESSAGE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_START
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_STOP
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_GET_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.PREFS_NAME
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.KEY_AMBIENT_LEVEL
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.KEY_VOICE_PASSTHROUGH
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.KEY_ALLOWLIST_ENABLED
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.KEY_ALLOWLIST
import com.fencewatcher.sonyanc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // List of (name, address) from paired Sony XM devices
    private data class DeviceInfo(val name: String, val address: String) {
        override fun toString() = name
    }

    private val pairedDevices = mutableListOf<DeviceInfo>()

    // Service running state
    private var serviceRunning = false

    // Permissions needed
    private val requiredPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // ---- Permission launcher ----

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasAllPermissions()) {
            scanDevices()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Status receiver ----

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(EXTRA_STATUS)
            if (status != null) {
                val msg = intent.getStringExtra(EXTRA_MESSAGE)
                val s = BluetoothAncService.Status.valueOf(status)
                updateStatusDisplay(s, msg)
                // Sync the toggle button with real service state
                updateServiceRunning(s == BluetoothAncService.Status.CONNECTING ||
                    s == BluetoothAncService.Status.CONNECTED)
            }
        }
    }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register status broadcast receiver
        registerReceiver(statusReceiver, IntentFilter(STATUS_BROADCAST),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
        )

        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.btnChooseApps.setOnClickListener { showAppPicker() }
        binding.btnNotifAccess.setOnClickListener { openNotifAccessSettings() }

        // Settings listeners
        binding.seekLevel.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.textLevelValue.text = (v + 1).toString()
                if (fromUser) {
                    prefs().edit().putInt(KEY_AMBIENT_LEVEL, v + 1).apply()
                }
            }
            override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
        })

        binding.switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_VOICE_PASSTHROUGH, checked).apply()
        }

        binding.switchAllowlist.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_ALLOWLIST_ENABLED, checked).apply()
            updateSelectedAppsText()
        }

        // Set build version
        binding.textVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_HASH})"

        // Initial state: real running state from the service object
        val running = BluetoothAncService.isRunning
        updateServiceRunning(running)

        // Check permissions and scan
        if (hasAllPermissions()) {
            scanDevices()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        loadSettings()
        updateSelectedAppsText()
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    // ---- Settings ----

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadSettings() {
        val level = prefs().getInt(KEY_AMBIENT_LEVEL, 20).coerceIn(1, 20)
        binding.seekLevel.progress = level - 1
        binding.textLevelValue.text = level.toString()
        binding.switchVoice.isChecked = prefs().getBoolean(KEY_VOICE_PASSTHROUGH, false)
        binding.switchAllowlist.isChecked = prefs().getBoolean(KEY_ALLOWLIST_ENABLED, false)
    }

    /** All installed apps with a launcher, for the allowlist picker. */
    private fun allApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (!seen.add(pkg)) return@mapNotNull null
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                label to pkg
            }
            .sortedBy { it.first.lowercase() }
    }

    private fun showAppPicker() {
        val apps = allApps()
        val labels = apps.map { it.first }.toTypedArray()
        val current = prefs().getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].second in current }

        AlertDialog.Builder(this)
            .setTitle("Apps that trigger ANC")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> checked[i] }.map { it.second }.toSet()
                prefs().edit().putStringSet(KEY_ALLOWLIST, selected).apply()
                updateSelectedAppsText()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSelectedAppsText() {
        val selected = prefs().getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
        val enabled = prefs().getBoolean(KEY_ALLOWLIST_ENABLED, false)
        binding.textSelectedApps.text = when {
            !enabled -> "Filtering disabled — any app triggers"
            selected.isEmpty() -> "No apps selected — nothing triggers (turn filter off to allow all)"
            else -> "Triggering apps:\n" + selected.joinToString("\n")
        }
    }

    private fun openNotifAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open notification settings", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Permissions ----

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    // ---- Device scanning ----

    private fun scanDevices() {
        pairedDevices.clear()

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            binding.textStatus.text = "❌ No Bluetooth hardware"
            return
        }
        if (!adapter.isEnabled) {
            binding.textStatus.text = "⚠️ Turn on Bluetooth"
            return
        }

        val btPermitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        if (!btPermitted) {
            binding.textStatus.text = "⚠️ Bluetooth permission needed"
            return
        }

        val bonded = adapter.bondedDevices ?: emptySet()
        for (device in bonded) {
            val name = device.name ?: ""
            if (name.contains("WH-1000XM", ignoreCase = true)) {
                pairedDevices.add(DeviceInfo(name, device.address))
            }
        }

        if (pairedDevices.isEmpty()) {
            binding.textStatus.text = "🔍 No WH-1000XM devices paired"
            binding.textDeviceList.text = "Pair your headphones in Settings → Bluetooth"
            binding.btnToggle.isEnabled = false
        } else {
            binding.textStatus.text = "${pairedDevices.size} device(s) found"
            binding.textDeviceList.text = pairedDevices.joinToString("\n") { d ->
                "• ${d.name}  (${d.address})"
            }
            binding.btnToggle.isEnabled = true
        }

        // Populate dropdown
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = listAdapter
    }

    // ---- Toggle ----

    private fun onToggleClicked() {
        if (serviceRunning) {
            stopService()
        } else {
            startService()
        }
    }

    private fun onRefreshClicked() {
        if (hasAllPermissions()) {
            scanDevices()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startService() {
        val pos = binding.spinnerDevice.selectedItemPosition
        if (pos < 0 || pos >= pairedDevices.size) {
            Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show()
            return
        }

        // Check BT permissions again (user might have revoked them)
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }

        // On Android 13+, POST_NOTIFICATIONS is required for foreground service.
        // If denied, startForeground() crashes the service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(requiredPermissions)
                return
            }
        }

        val device = pairedDevices[pos]
        val intent = Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ADDRESS, device.address)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateServiceRunning(true)
        binding.textStatus.text = "🔄 Starting…"
    }

    private fun stopService() {
        val intent = Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_STOP
        }
        startService(intent)
        updateServiceRunning(false)
        binding.textStatus.text = "⏹ Stopped"
    }

    // ---- Status display ----

    private fun updateServiceRunning(running: Boolean) {
        serviceRunning = running
        binding.btnToggle.text = if (running) "Stop Service" else "Start Service"
        binding.btnToggle.setBackgroundColor(
            ContextCompat.getColor(this,
                if (running) android.R.color.holo_red_dark
                else android.R.color.holo_green_dark
            )
        )
        binding.spinnerDevice.isEnabled = !running
        binding.btnRefresh.isEnabled = !running
    }

    private fun updateStatusDisplay(status: BluetoothAncService.Status, message: String? = null) {
        val icon = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> "⚪"
            BluetoothAncService.Status.CONNECTING -> "🔄"
            BluetoothAncService.Status.CONNECTED -> "🟢"
            BluetoothAncService.Status.ERROR -> "🔴"
        }
        binding.textStatus.text = if (message != null) {
            "$icon $status — $message"
        } else {
            "$icon $status"
        }
    }
}