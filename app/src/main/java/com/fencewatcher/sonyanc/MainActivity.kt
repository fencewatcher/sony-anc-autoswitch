package com.fencewatcher.sonyanc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.STATUS_BROADCAST
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_ADDRESS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_START
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_STOP
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_GET_STATUS
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
                updateStatusDisplay(BluetoothAncService.Status.valueOf(status))
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

        // Initial state
        updateServiceRunning(false)

        // Check permissions and scan
        if (hasAllPermissions()) {
            scanDevices()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        // Check if service is already running
        getServiceStatus()
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = adapter
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
            getServiceStatus()
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

    private fun updateStatusDisplay(status: BluetoothAncService.Status) {
        val icon = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> "⚪"
            BluetoothAncService.Status.CONNECTING -> "🔄"
            BluetoothAncService.Status.CONNECTED -> "🟢"
            BluetoothAncService.Status.ERROR -> "🔴"
        }
        binding.textStatus.text = "$icon $status"
    }

    private fun getServiceStatus() {
        Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_GET_STATUS
        }.also { startService(it) }
    }
}