package com.fencewatcher.sonyanc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_BATTERY
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_START
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_STOP
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_GET_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ANC_ON
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_AMBIENT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ANC_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_EQ_CUSTOM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_PRESET
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_BANDS
import com.fencewatcher.sonyanc.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private data class DeviceInfo(val name: String, val address: String) {
        override fun toString() = name
    }

    private val pairedDevices = mutableListOf<DeviceInfo>()
    private var serviceRunning = false
    private var battery: Int? = null
    private var currentMode = "—"
    private var selectedAddress: String? = null
    private var showingTab = 0 // 0=Dashboard, 1=Settings, 2=EQ

    // Custom EQ state (10 bands, range -6..+6, 0 = neutral)
    private val eqBandLabels = arrayOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    private val eqBandValues = IntArray(10) { 0 }
    private var slidersInitialized = false

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasAllPermissions()) scanDevices()
        else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(EXTRA_STATUS)
            if (status != null) {
                val s = BluetoothAncService.Status.valueOf(status)
                val msg = intent.getStringExtra(EXTRA_MESSAGE)
                updateStatusDisplay(s, msg)
                updateServiceRunning(s == BluetoothAncService.Status.CONNECTING ||
                    s == BluetoothAncService.Status.CONNECTED)
                if (intent.hasExtra(EXTRA_BATTERY)) battery = intent.getIntExtra(EXTRA_BATTERY, 0)
                if (intent.hasExtra(EXTRA_MODE)) currentMode = intent.getStringExtra(EXTRA_MODE) ?: "—"
                updateCardStats()
            }
        }
    }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(statusReceiver, IntentFilter(STATUS_BROADCAST),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        )

        // Tab listeners
        binding.tabDashboard.setOnClickListener { switchTab(0) }
        binding.tabSettings.setOnClickListener { switchTab(1) }
        binding.tabEQ.setOnClickListener { switchTab(2) }

        // Dashboard
        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnQuickNC.setOnClickListener { sendQuickAction(ACTION_ANC_ON) }
        binding.btnQuickAmbient.setOnClickListener { sendQuickAction(ACTION_AMBIENT) }
        binding.btnQuickOff.setOnClickListener { sendQuickAction(ACTION_ANC_OFF) }

        // Settings
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.btnChooseApps.setOnClickListener { showAppPicker() }
        binding.btnNotifAccess.setOnClickListener { openNotifAccessSettings() }
        binding.seekLevel.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.textLevelValue.text = (v + 1).toString()
                if (fromUser) saveSetting("ambient_level", v + 1)
            }
            override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
        })
        binding.switchVoice.setOnCheckedChangeListener { _, checked -> saveSetting("voice_passthrough", checked) }
        binding.switchAutoAmbient.setOnCheckedChangeListener { _, checked -> saveSetting("auto_ambient", checked) }
        binding.switchAllowlist.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("anc_settings", MODE_PRIVATE).edit().putBoolean("allowlist_enabled", checked).apply()
            updateSelectedAppsText()
        }
        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { loadDeviceSettings() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // EQ
        binding.btnEQOff.setOnClickListener { sendEQ(0x00) }
        binding.btnEQHeavy.setOnClickListener { sendEQ(0x30) }
        binding.btnEQClear.setOnClickListener { sendEQ(0x31) }
        binding.btnEQHard.setOnClickListener { sendEQ(0x32) }
        binding.btnEQSoft.setOnClickListener { sendEQ(0x33) }
        binding.btnEQCustom.setOnClickListener { onClickEQCustom() }
        binding.btnEQUser1.setOnClickListener { sendEQ(0xA1) }
        binding.btnEQUser2.setOnClickListener { sendEQ(0xA2) }
        binding.btnApplyCustomEQ.setOnClickListener { applyCustomEQ() }

        binding.textVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_HASH})"
        updateServiceRunning(BluetoothAncService.isRunning)

        if (hasAllPermissions()) scanDevices()
        else permissionLauncher.launch(requiredPermissions)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    // ---- 3-tab switching ----

    private fun switchTab(tab: Int) {
        showingTab = tab
        binding.dashboardContent.visibility = if (tab == 0) View.VISIBLE else View.GONE
        binding.settingsContent.visibility = if (tab == 1) View.VISIBLE else View.GONE
        binding.eqContent.visibility = if (tab == 2) View.VISIBLE else View.GONE

        val active = Color.rgb(224, 224, 224)
        val muted = Color.rgb(136, 136, 136)
        val tabs = listOf(binding.tabDashboard, binding.tabSettings, binding.tabEQ)
        for (i in tabs.indices) {
            tabs[i].setTextColor(if (i == tab) active else muted)
            tabs[i].setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ---- Per-device settings ----

    private fun loadDeviceSettings() {
        val addr = selectedAddress ?: pairedDevices.getOrNull(binding.spinnerDevice.selectedItemPosition)?.address
        if (addr == null) return
        selectedAddress = addr
        val prefs = getSharedPreferences("anc_settings", MODE_PRIVATE)
        val level = prefs.getInt("ambient_level_$addr", 20).coerceIn(1, 20)
        binding.seekLevel.progress = level - 1
        binding.textLevelValue.text = level.toString()
        binding.switchVoice.isChecked = prefs.getBoolean("voice_passthrough_$addr", false)
        binding.switchAutoAmbient.isChecked = prefs.getBoolean("auto_ambient_$addr", false)
        binding.switchAllowlist.isChecked = prefs.getBoolean("allowlist_enabled", false)
        updateSelectedAppsText()
        val name = pairedDevices.find { it.address == addr }?.name ?: ""
        binding.textProfile.text = "Protocol: ${HeadphoneProfile.detect(name).modelName}"
    }

    private fun saveSetting(key: String, value: Any) {
        val addr = selectedAddress ?: return
        val e = getSharedPreferences("anc_settings", MODE_PRIVATE).edit()
        when (value) {
            is Int -> e.putInt("${key}_$addr", value)
            is Boolean -> e.putBoolean("${key}_$addr", value)
        }
        e.apply()
    }

    // ---- App allowlist ----

    private fun allApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (!seen.add(pkg)) return@mapNotNull null
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg }
            label to pkg
        }.sortedBy { it.first.lowercase() }
    }

    private fun showAppPicker() {
        val apps = allApps()
        val labels = apps.map { it.first }.toTypedArray()
        val prefs = getSharedPreferences("anc_settings", MODE_PRIVATE)
        val current = prefs.getStringSet("allowlist_apps", emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].second in current }
        AlertDialog.Builder(this)
            .setTitle("Apps that trigger ANC")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> checked[i] }.map { it.second }.toSet()
                prefs.edit().putStringSet("allowlist_apps", selected).apply()
                updateSelectedAppsText()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateSelectedAppsText() {
        val prefs = getSharedPreferences("anc_settings", MODE_PRIVATE)
        val selected = prefs.getStringSet("allowlist_apps", emptySet()) ?: emptySet()
        val enabled = prefs.getBoolean("allowlist_enabled", false)
        binding.textSelectedApps.text = when {
            !enabled -> "App filtering disabled"
            selected.isEmpty() -> "No apps — nothing triggers"
            else -> selected.joinToString("\n")
        }
    }

    private fun openNotifAccessSettings() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        catch (e: Exception) { Toast.makeText(this, "Couldn't open notification settings", Toast.LENGTH_SHORT).show() }
    }

    // ---- Quick mode + EQ ----

    private fun sendQuickAction(action: String) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show(); return }
        Intent(this, BluetoothAncService::class.java).apply { this.action = action }.also { startService(it) }
    }

    private fun sendEQ(presetId: Int) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show(); return }
        val preset = EQPreset.fromId(presetId)
        binding.textEQStatus.text = "Applying ${preset.displayName}…"
        Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_SET_EQ
            putExtra(EXTRA_EQ_PRESET, presetId)
        }.also { startService(it) }
        Toast.makeText(this, "EQ: ${preset.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun onClickEQCustom() {
        sendEQ(0xA0) // select Custom preset on headphones first
        binding.customEQSection.visibility = View.VISIBLE
        if (!slidersInitialized) populateCustomEQSliders()
    }

    private fun populateCustomEQSliders() {
        slidersInitialized = true
        val container = binding.customEQSliders
        container.removeAllViews()
        for (i in eqBandLabels.indices) {
            val label = eqBandLabels[i]
            val row = LinearLayout(this).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            // Band label
            val labelView = TextView(this).apply {
                text = "${label}Hz"
                setTextColor(Color.rgb(224, 224, 224))
                textSize = 13f
                minWidth = 80
            }
            // SeekBar in range 0..12 (represents -6..+6)
            val seekBar = SeekBar(this).apply {
                max = 12
                progress = 6 // 0 offset = neutral
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                        eqBandValues[i] = v - 6
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            }
            // Value text
            val valueView = TextView(this).apply {
                text = "0"
                setTextColor(Color.rgb(160, 160, 160))
                textSize = 12f
                minWidth = 30
                gravity = android.view.Gravity.CENTER
            }
            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    eqBandValues[i] = v - 6
                    valueView.text = (v - 6).toString()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
            row.addView(labelView)
            row.addView(seekBar)
            row.addView(valueView)
            container.addView(row)
        }
    }

    private fun applyCustomEQ() {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show(); return }
        binding.textEQStatus.text = "Applying custom EQ…"
        Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_SET_EQ_CUSTOM
            putExtra(EXTRA_EQ_BANDS, eqBandValues)
        }.also { startService(it) }
        Toast.makeText(this, "Custom EQ applied", Toast.LENGTH_SHORT).show()
    }

    // ---- Permissions ----

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { perm -> ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED }

    // ---- Device scanning ----

    private fun scanDevices() {
        pairedDevices.clear()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            updateCardStatus("⚠️ Bluetooth off")
            binding.btnToggle.isEnabled = false
            return
        }
        val btPermitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!btPermitted) { updateCardStatus("⚠️ Permission needed"); binding.btnToggle.isEnabled = false; return }

        val bonded = adapter.bondedDevices ?: emptySet()
        for (device in bonded) {
            val name = device.name ?: ""
            if (name.contains("WH-1000XM", ignoreCase = true)) pairedDevices.add(DeviceInfo(name, device.address))
        }
        if (pairedDevices.isEmpty()) {
            binding.textModel.text = "No headphones"
            updateCardStatus("🔍 Pair in Settings → Bluetooth")
            binding.btnToggle.isEnabled = false
        } else {
            binding.textModel.text = pairedDevices.first().name
            updateCardStatus("${pairedDevices.size} device(s)")
            binding.btnToggle.isEnabled = true
        }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = listAdapter
        loadDeviceSettings()
    }

    // ---- Service toggle ----

    private fun onToggleClicked() {
        if (serviceRunning) stopService() else startService()
    }

    private fun onRefreshClicked() {
        if (!hasAllPermissions()) { permissionLauncher.launch(requiredPermissions); return }
        scanDevices()
        updateServiceRunning(BluetoothAncService.isRunning)
        if (BluetoothAncService.isRunning) {
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_GET_STATUS }.also { startService(it) }
        }
    }

    private fun startService() {
        val pos = binding.spinnerDevice.selectedItemPosition
        if (pos < 0 || pos >= pairedDevices.size) { Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show(); return }
        if (!hasAllPermissions()) { permissionLauncher.launch(requiredPermissions); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { permissionLauncher.launch(requiredPermissions); return }

        val device = pairedDevices[pos]
        Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ADDRESS, device.address)
        }.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
        }
        updateServiceRunning(true)
        updateCardStatus("🔄 Starting…")
    }

    private fun stopService() {
        Intent(this, BluetoothAncService::class.java).apply { action = ACTION_STOP }.also { startService(it) }
        updateServiceRunning(false)
        updateCardStatus("⏹ Stopped")
    }

    // ---- Dashboard card ----

    private fun updateServiceRunning(running: Boolean) {
        serviceRunning = running
        binding.btnToggle.text = if (running) "Stop Service" else "Start Service"
        binding.btnToggle.setBackgroundColor(ContextCompat.getColor(this,
            if (running) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
    }

    private fun updateStatusDisplay(status: BluetoothAncService.Status, message: String? = null) {
        val icon = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> "⚪"
            BluetoothAncService.Status.CONNECTING -> "🔄"
            BluetoothAncService.Status.CONNECTED -> "🟢"
            BluetoothAncService.Status.ERROR -> "🔴"
        }
        updateCardStatus(if (message != null) "$icon $status — $message" else "$icon $status")
    }

    private fun updateCardStatus(text: String) { binding.textStatus.text = text }

    private fun updateCardStats() {
        binding.textBattery.text = battery?.let { "🔋$it%" } ?: "🔋—"
        binding.textMode.text = "🎧 $currentMode"
    }
}