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
import android.os.Handler
import android.os.Looper
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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_ACTIVE_PRESET
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_ACTIVE_BANDS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_START
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_STOP
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_GET_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ANC_ON
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_AMBIENT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ANC_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_EQ
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_EQ_CUSTOM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_PRESET
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_BANDS
import com.fencewatcher.sonyanc.databinding.ActivityMainBinding
import java.util.Locale

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
    private var showingTab = 0

    // EQ state
    private val eqBandValues = IntArray(10) { 0 }
    private var selectedEQProfile = 0xA0 // 0xA0=Custom, 0xA1=User1, 0xA2=User2
    private var eqActivePreset = 0
    private var eqActiveBands: IntArray? = null

    private val requiredPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN)
        } else { add(Manifest.permission.ACCESS_FINE_LOCATION) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
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
                updateServiceRunning(s == BluetoothAncService.Status.CONNECTING || s == BluetoothAncService.Status.CONNECTED)
                if (intent.hasExtra(EXTRA_BATTERY)) battery = intent.getIntExtra(EXTRA_BATTERY, 0)
                if (intent.hasExtra(EXTRA_MODE)) currentMode = intent.getStringExtra(EXTRA_MODE) ?: "—"
                updateCardStats()
            }
            // EQ state from service
            if (intent?.hasExtra(EXTRA_EQ_ACTIVE_PRESET) == true) {
                eqActivePreset = intent.getIntExtra(EXTRA_EQ_ACTIVE_PRESET, 0)
                eqActiveBands = intent.getIntArrayExtra(EXTRA_EQ_ACTIVE_BANDS)
                updateEQStatus()
                // Sync graph view
                val src = eqActiveBands
                if (src != null && src.size == 10) {
                    for (i in 0..9) eqBandValues[i] = src[i].coerceIn(-6, 6)
                    binding.eqGraph.bandValues = eqBandValues
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerReceiver(statusReceiver, IntentFilter(STATUS_BROADCAST),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)

        // Tabs
        binding.tabDashboard.setOnClickListener { switchTab(0) }
        binding.tabSettings.setOnClickListener { switchTab(1) }
        binding.tabEQ.setOnClickListener { switchTab(2) }

        // Dashboard
        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnQuickNC.setOnClickListener { sendCommand(ACTION_ANC_ON) }
        binding.btnQuickAmbient.setOnClickListener { sendCommand(ACTION_AMBIENT) }
        binding.btnQuickOff.setOnClickListener { sendCommand(ACTION_ANC_OFF) }

        // Settings
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.btnChooseApps.setOnClickListener { showAppPicker() }
        binding.btnNotifAccess.setOnClickListener { openNotifAccessSettings() }
        binding.seekLevel.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.textLevelValue.text = (v + 1).toString()
                if (fromUser) saveSetting("ambient_level", v + 1)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}; override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        binding.switchVoice.setOnCheckedChangeListener { _, c -> saveSetting("voice_passthrough", c) }
        binding.switchAutoAmbient.setOnCheckedChangeListener { _, c -> saveSetting("auto_ambient", c) }
        binding.switchAllowlist.setOnCheckedChangeListener { _, c ->
            getSharedPreferences("anc_settings", MODE_PRIVATE).edit().putBoolean("allowlist_enabled", c).apply()
            updateSelectedAppsText() }
        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { loadDeviceSettings() }
            override fun onNothingSelected(p: AdapterView<*>?) {} }

        // EQ
        binding.btnEQOff.setOnClickListener { selectPreset(0x00) }
        binding.btnEQHeavy.setOnClickListener { selectPreset(0x30) }
        binding.btnEQClear.setOnClickListener { selectPreset(0x31) }
        binding.btnEQHard.setOnClickListener { selectPreset(0x32) }
        binding.btnEQSoft.setOnClickListener { selectPreset(0x33) }
        binding.btnEQCustom.setOnClickListener { selectEditablePreset(0xA0, "Custom") }
        binding.btnEQUser1.setOnClickListener { selectEditablePreset(0xA1, "User 1") }
        binding.btnEQUser2.setOnClickListener { selectEditablePreset(0xA2, "User 2") }
        binding.btnApplyCustomEQ.setOnClickListener { applyCustomEQ() }

        // EQ graph callbacks
        binding.eqGraph.onBandChanged = { index, value ->
            eqBandValues[index] = value
        }

        binding.textVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_HASH})"
        updateServiceRunning(BluetoothAncService.isRunning)
        if (hasAllPermissions()) scanDevices()
        else permissionLauncher.launch(requiredPermissions)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    // ---- Tab ----

    private fun switchTab(tab: Int) {
        showingTab = tab
        binding.dashboardContent.visibility = if (tab == 0) View.VISIBLE else View.GONE
        binding.settingsContent.visibility = if (tab == 1) View.VISIBLE else View.GONE
        binding.eqContent.visibility = if (tab == 2) View.VISIBLE else View.GONE
        val active = Color.rgb(224, 224, 224)
        val muted = Color.rgb(136, 136, 136)
        for ((i, t) in listOf(binding.tabDashboard, binding.tabSettings, binding.tabEQ).withIndex()) {
            t.setTextColor(if (i == tab) active else muted)
            t.setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        if (tab == 2) binding.eqGraph.bandValues = eqBandValues
    }

    // ---- EQ ----

    private fun selectPreset(presetId: Int) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        val preset = EQPreset.fromId(presetId)
        binding.textEQStatus.text = "↻ ${preset.displayName}"
        selectedEQProfile = presetId
        sendToService(ACTION_SET_EQ) { putExtra(EXTRA_EQ_PRESET, presetId) }
        binding.eqGraph.interactive = false
        binding.btnApplyCustomEQ.visibility = View.GONE
        Handler(Looper.getMainLooper()).postDelayed({
            binding.textEQStatus.text = "✅ ${preset.displayName}"
        }, 2000)
    }

    private fun selectEditablePreset(profileId: Int, label: String) {
        selectedEQProfile = profileId
        // Sync current eqBandValues to graph
        binding.eqGraph.bandValues = eqBandValues
        if (BluetoothAncService.isRunning) {
            binding.textEQStatus.text = "↻ $label — drag curve then Write"
            sendToService(ACTION_SET_EQ) { putExtra(EXTRA_EQ_PRESET, profileId) }
        }
        binding.eqGraph.interactive = true
        binding.btnApplyCustomEQ.visibility = View.VISIBLE
        binding.btnApplyCustomEQ.text = "Write to $label"
    }

    private fun applyCustomEQ() {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        // Read values from graph view for Apply
        val presetName = EQPreset.fromId(selectedEQProfile).displayName
        binding.textEQStatus.text = "↻ Writing $presetName..."
        sendToService(ACTION_SET_EQ_CUSTOM) { putExtra(EXTRA_EQ_BANDS, binding.eqGraph.bandValues) }
        Handler(Looper.getMainLooper()).postDelayed({
            if (binding.textEQStatus.text.startsWith("↻")) updateEQStatus()
        }, 3000)
    }

    private fun updateEQStatus() {
        val preset = EQPreset.fromId(eqActivePreset)
        val bands = eqActiveBands
        binding.textEQStatus.text = if (bands != null && bands.size == 10) {
            "✅ ${preset.displayName} [${bands.joinToString(" ") { "%+d".format(it) }}]"
        } else {
            "✅ ${preset.displayName}"
        }
    }

    // ---- Per-device settings ----

    private fun loadDeviceSettings() {
        val addr = selectedAddress ?: pairedDevices.getOrNull(binding.spinnerDevice.selectedItemPosition)?.address ?: return
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
        when (value) { is Int -> e.putInt("${key}_$addr", value); is Boolean -> e.putBoolean("${key}_$addr", value) }
        e.apply()
    }

    // ---- App allowlist ----

    private fun allApps(): List<Pair<String, String>> {
        val pm = packageManager
        val seen = HashSet<String>()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName; if (!seen.add(pkg)) return@mapNotNull null
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                label to pkg
            }.sortedBy { it.first.lowercase() }
    }

    private fun showAppPicker() {
        val apps = allApps(); val labels = apps.map { it.first }.toTypedArray()
        val prefs = getSharedPreferences("anc_settings", MODE_PRIVATE)
        val current = prefs.getStringSet("allowlist_apps", emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].second in current }
        AlertDialog.Builder(this).setTitle("Apps that trigger ANC")
            .setMultiChoiceItems(labels, checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("allowlist_apps", apps.filterIndexed { i, _ -> checked[i] }.map { it.second }.toSet()).apply()
                updateSelectedAppsText()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun updateSelectedAppsText() {
        val prefs = getSharedPreferences("anc_settings", MODE_PRIVATE)
        val s = prefs.getStringSet("allowlist_apps", emptySet()) ?: emptySet()
        binding.textSelectedApps.text = when {
            !prefs.getBoolean("allowlist_enabled", false) -> "App filtering disabled"
            s.isEmpty() -> "No apps — nothing triggers"
            else -> s.joinToString("\n")
        }
    }

    private fun openNotifAccessSettings() {
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        catch (e: Exception) { Toast.makeText(this, "Couldn't open notification settings", Toast.LENGTH_SHORT).show() }
    }

    // ---- Helpers ----

    private fun sendCommand(action: String) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        sendToService(action) {}
    }

    private fun sendToService(action: String, extras: Intent.() -> Unit) {
        Intent(this, BluetoothAncService::class.java).apply {
            this.action = action; extras()
        }.also { startService(it) }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    // ---- Device scanning ----

    private fun scanDevices() {
        pairedDevices.clear()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            updateCardStatus("⚠️ Bluetooth off"); binding.btnToggle.isEnabled = false; return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) { updateCardStatus("⚠️ Permission needed"); binding.btnToggle.isEnabled = false; return }

        for (d in (adapter.bondedDevices ?: emptySet())) {
            val name = d.name ?: ""
            if (name.contains("WH-1000XM", ignoreCase = true)) pairedDevices.add(DeviceInfo(name, d.address))
        }
        if (pairedDevices.isEmpty()) {
            binding.textModel.text = "No headphones"; updateCardStatus("🔍 Pair in Settings → Bluetooth"); binding.btnToggle.isEnabled = false
        } else {
            binding.textModel.text = pairedDevices.first().name; updateCardStatus("${pairedDevices.size} device(s)"); binding.btnToggle.isEnabled = true
        }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = listAdapter
        loadDeviceSettings()
    }

    // ---- Toggle ----

    private fun onToggleClicked() { if (serviceRunning) stopService() else startService() }

    private fun onRefreshClicked() {
        if (!hasAllPermissions()) { permissionLauncher.launch(requiredPermissions); return }
        scanDevices()
        updateServiceRunning(BluetoothAncService.isRunning)
        if (BluetoothAncService.isRunning) sendToService(ACTION_GET_STATUS) {}
    }

    private fun startService() {
        val pos = binding.spinnerDevice.selectedItemPosition
        if (pos < 0 || pos >= pairedDevices.size) { Toast.makeText(this, "Select a device", Toast.LENGTH_SHORT).show(); return }
        if (!hasAllPermissions()) { permissionLauncher.launch(requiredPermissions); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { permissionLauncher.launch(requiredPermissions); return }
        val device = pairedDevices[pos]
        Intent(this, BluetoothAncService::class.java).apply {
            action = ACTION_START; putExtra(EXTRA_ADDRESS, device.address)
        }.also { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it) }
        updateServiceRunning(true); updateCardStatus("🔄 Starting…")
    }

    private fun stopService() {
        sendToService(ACTION_STOP) {}
        updateServiceRunning(false); updateCardStatus("⏹ Stopped")
    }

    // ---- Dashboard card ----

    private fun updateServiceRunning(running: Boolean) {
        serviceRunning = running
        binding.btnToggle.text = if (running) "⏹ Stop" else "▶ Start"
        binding.btnToggle.setBackgroundColor(ContextCompat.getColor(this, if (running) android.R.color.holo_red_dark else android.R.color.holo_green_dark))
    }

    private fun updateStatusDisplay(status: BluetoothAncService.Status, msg: String? = null) {
        val icon = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> "⚪"; BluetoothAncService.Status.CONNECTING -> "🔄"
            BluetoothAncService.Status.CONNECTED -> "🟢"; BluetoothAncService.Status.ERROR -> "🔴"
        }
        updateCardStatus(if (msg != null) "$icon $status — $msg" else "$icon $status")
    }

    private fun updateCardStatus(text: String) { binding.textStatus.text = text }

    private fun updateCardStats() {
        binding.textBattery.text = battery?.let { "🔋$it%" } ?: "🔋—"
        binding.textMode.text = "🎧 $currentMode"
    }
}