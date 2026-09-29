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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_POWER_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_PRESET
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_EQ_BANDS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_PERIPHERAL_OK
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_LIST
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_MACS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_ACTIVE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_SPEAK_TO_CHAT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_PAUSE_TAKEN_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DSEE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_BGM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_UPMIX
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_AUTO_POWER_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_TARGET_MAC
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_REFRESH_DEVICES
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SOURCE_SWITCH
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_SPEAK_TO_CHAT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_PAUSE_TAKEN_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_DSEE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_BGM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_UPMIX
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_AUTO_POWER
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_VOICE_GUIDANCE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ENTER_PAIRING_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_FIX_PLAYBACK
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_FIX_PLAYBACK
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_RELOAD_AUTOMATION
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_VOICE_GUIDANCE_VOLUME
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

    // Headphone feature state (mirrored from the service broadcasts)
    private var peripheralSupported = false
    private var speakToChat = false
    private var pauseWhenTakenOff = false
    private var dseeExtreme = false
    private var bgmMode = false
    private var upmixCinema = false
    private var autoPowerOffMode = 0
    /** True = "Fix Playback": multipoint will not hand audio to another device. */
    private var playbackFixed = false
    private var multiNames: Array<String> = emptyArray()
    private var multiMacs: Array<String> = emptyArray()
    private var multiActive: BooleanArray = BooleanArray(0)

    /** Guards switch/spinner listeners while we push service state into the UI. */
    private var updatingUi = false
    private var autoPowerSpinnerReady = false

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

            // Multipoint + headphone feature state
            if (intent?.hasExtra(EXTRA_PERIPHERAL_OK) == true) {
                peripheralSupported = intent.getBooleanExtra(EXTRA_PERIPHERAL_OK, false)
                if (intent.hasExtra(EXTRA_DEVICE_MACS)) {
                    multiNames = intent.getStringArrayExtra(EXTRA_DEVICE_LIST) ?: emptyArray()
                    multiMacs = intent.getStringArrayExtra(EXTRA_DEVICE_MACS) ?: emptyArray()
                    multiActive = intent.getBooleanArrayExtra(EXTRA_DEVICE_ACTIVE) ?: BooleanArray(0)
                }
                speakToChat = intent.getBooleanExtra(EXTRA_SPEAK_TO_CHAT, false)
                pauseWhenTakenOff = intent.getBooleanExtra(EXTRA_PAUSE_TAKEN_OFF, false)
                dseeExtreme = intent.getBooleanExtra(EXTRA_DSEE, false)
                bgmMode = intent.getBooleanExtra(EXTRA_BGM, false)
                upmixCinema = intent.getBooleanExtra(EXTRA_UPMIX, false)
                autoPowerOffMode = intent.getIntExtra(EXTRA_AUTO_POWER_MODE, 0)
                playbackFixed = intent.getBooleanExtra(EXTRA_FIX_PLAYBACK, false)
                binding.btnFixPlayback.text =
                    if (playbackFixed) "🔒 Playback locked" else "Lock playback to this device"
                binding.textFixPlaybackState.text = when {
                    !peripheralSupported ->
                        "Peripheral/multipoint family not supported on this device"
                    multiMacs.isEmpty() ->
                        "Enable multipoint in the Sony app, connect a second device, then refresh."
                    playbackFixed ->
                        "Playback is pinned here — it will not switch to another device."
                    else -> "Playback can move between paired devices."
                }
                // The wire value is signed -2..+2, so -1/-2 are legitimate values and
                // cannot double as the "absent" sentinel.
                val vg = intent.getIntExtra(EXTRA_VOICE_GUIDANCE_VOLUME, Int.MAX_VALUE)
                if (vg in -2..2) {
                    updatingUi = true
                    binding.seekVoiceGuidance.progress = vg + 2
                    binding.textVoiceGuidanceValue?.text = "Level $vg"
                    updatingUi = false
                }
                renderMultiPoint()
                renderFeatureSwitches()
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
        binding.tabHome.setOnClickListener { switchTab(0) }
        binding.tabAudio.setOnClickListener { switchTab(1) }
        binding.tabDevices.setOnClickListener { switchTab(2) }
        binding.tabRoutines.setOnClickListener { switchTab(3) }
        binding.tabSettings.setOnClickListener { switchTab(4) }

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

        // Power Off
        binding.btnPowerOff.setOnClickListener {
            if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            sendToService(ACTION_POWER_OFF) {}
            Toast.makeText(this, "Powering off…", Toast.LENGTH_SHORT).show()
        }

        // ---- Multipoint ----
        binding.btnRefreshDevices.setOnClickListener {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
            binding.textMultiPointStatus.text = "Refreshing…"
            sendToService(ACTION_REFRESH_DEVICES) {}
        }

        // ---- Multipoint: lock playback + pairing mode ----
        binding.btnFixPlayback.setOnClickListener {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
            val next = !playbackFixed
            sendToService(ACTION_SET_FIX_PLAYBACK) { putExtra("fix", next) }
            toast(if (next) "Locking playback to this device" else "Playback lock released")
        }
        binding.btnPairingMode.setOnClickListener {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
            sendToService(ACTION_ENTER_PAIRING_MODE) { putExtra("enter", true) }
            toast("Pairing mode — hold to exit")
        }

        // ---- Headphone feature switches (Table 1 params) ----
        binding.switchSpeakToChat.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendToService(ACTION_SET_SPEAK_TO_CHAT) { putExtra("on", c) }
        }
        binding.switchPauseOff.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendToService(ACTION_SET_PAUSE_TAKEN_OFF) { putExtra("on", c) }
        }
        binding.switchDsee.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendToService(ACTION_SET_DSEE) { putExtra("on", c) }
        }
        binding.switchBgm.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendToService(ACTION_SET_BGM) { putExtra("on", c) }
        }
        binding.switchUpmix.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            sendToService(ACTION_SET_UPMIX) { putExtra("on", c) }
        }

        // Auto power-off: XM6 firmware only accepts the two wearing-detection values.
        val powerValues = intArrayOf(SonyMdrV2.AutoPowerOff.WHEN_TAKEN_OFF, SonyMdrV2.AutoPowerOff.NEVER)
        val powerLabels = powerValues.map { SonyMdrV2.AutoPowerOff.label(it) }.toTypedArray()
        binding.spinnerAutoPower.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, powerLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerAutoPower.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!autoPowerSpinnerReady || updatingUi) return
                sendToService(ACTION_SET_AUTO_POWER) { putExtra("mode", powerValues[pos]) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        autoPowerSpinnerReady = true

        // ---- Automations ----
        loadAutomation()
        binding.btnAddAutomation.setOnClickListener { showRuleEditor(null) }
        binding.btnResetAutomation.setOnClickListener {
            automationRules = Automation.defaultRules()
            saveAutomation()
            toast("Rules reset to defaults")
        }

        // ---- Volume (read back from the headphones, set through the PLAY family) ----
        binding.seekVoiceGuidance.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                // Slider is 0..4; the wire value is the signed scale -2..+2.
                sendToService(ACTION_SET_VOICE_GUIDANCE) { putExtra("value", p - 2) }
                binding.textVoiceGuidanceValue?.text = "Level ${p - 2}"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

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
        val panes = listOf(
            binding.homeContent, binding.audioContent, binding.devicesContent,
            binding.routinesContent, binding.settingsContent,
        )
        panes.forEachIndexed { i, v -> v.visibility = if (i == tab) View.VISIBLE else View.GONE }

        val labels = listOf(
            binding.tabHome, binding.tabAudio, binding.tabDevices,
            binding.tabRoutines, binding.tabSettings,
        )
        val underlines = listOf(
            binding.underlineHome, binding.underlineAudio, binding.underlineDevices,
            binding.underlineRoutines, binding.underlineSettings,
        )
        val active = Color.rgb(240, 240, 240)
        val muted = Color.rgb(138, 138, 138)
        val accent = Color.rgb(120, 200, 170)
        labels.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) active else muted)
            t.setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            t.textSize = if (i == tab) 14f else 13f
        }
        underlines.forEachIndexed { i, u ->
            u.setBackgroundColor(if (i == tab) accent else Color.TRANSPARENT)
        }
        if (tab == 1) {
            binding.eqGraph.bandValues = eqBandValues
            if (BluetoothAncService.isRunning) sendToService(ACTION_GET_STATUS) {}
        }
    }

    // ---- Automations + volume ----

    private var automationRules: List<Automation.Rule> = emptyList()

    private fun loadAutomation() {
        automationRules = Automation.load(this)
        renderAutomation()
    }

    private fun saveAutomation() {
        Automation.save(this, automationRules)
        sendToService(ACTION_RELOAD_AUTOMATION) {}
        renderAutomation()
    }

    private fun renderAutomation() {
        val box = binding.automationList
        box.removeAllViews()
        if (automationRules.isEmpty()) {
            box.addView(android.widget.TextView(this).apply {
                text = "No rules yet — nothing will change automatically."
                textSize = 13f
                setTextColor(Color.rgb(150, 150, 150))
                setPadding(0, 8, 0, 8)
            })
            return
        }
        automationRules.forEachIndexed { index, rule ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
                isClickable = true
                setBackgroundColor(if (rule.enabled) 0x22334466 else 0x00000000)
            }
            val label = android.widget.TextView(this).apply {
                text = "When ${rule.trigger.label.lowercase()}\n→ ${Automation.describe(rule)}"
                textSize = 13f
                setTextColor(if (rule.enabled) Color.rgb(220, 220, 220) else Color.rgb(120, 120, 120))
                setPadding(12, 0, 8, 0)
            }
            row.addView(label, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val toggle = android.widget.Switch(this).apply {
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked ->
                    automationRules = automationRules.toMutableList().also {
                        it[index] = it[index].copy(enabled = checked)
                    }
                    saveAutomation()
                }
            }
            row.addView(toggle)
            row.setOnClickListener { showRuleEditor(index) }
            row.setOnLongClickListener {
                automationRules = automationRules.filterIndexed { i, _ -> i != index }
                saveAutomation()
                true
            }
            box.addView(row)
        }
    }

    /**
     * Rule editor: three spinners (when / do / value) in one dialog. Long-pressing a
     * rule in the list deletes it, which keeps this to a single interaction surface.
     */
    private fun showRuleEditor(existingIndex: Int?) {
        val triggers = Automation.Trigger.values()
        val actionTypes = listOf(
            Automation.ActionType.NONE,
            Automation.ActionType.SET_MODE,
            Automation.ActionType.SET_AMBIENT_LEVEL,
            Automation.ActionType.SET_EQ_PRESET,
        )
        val base = existingIndex?.let { automationRules[it] }
            ?: Automation.Rule("rule-${System.currentTimeMillis()}", Automation.Trigger.PLAYBACK_START, Automation.Action())

        val linear = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        fun <T> spinner(items: List<T>, selected: Int): android.widget.Spinner {
            val sp = android.widget.Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            sp.setSelection(selected)
            linear.addView(sp)
            return sp
        }

        val whenSp = spinner(triggers.map { it.label }, triggers.indexOf(base.trigger))
        val doSp = spinner(actionTypes.map { it.label }, actionTypes.indexOf(base.action.type).coerceAtLeast(0))
        val valueSp = android.widget.Spinner(this).also { linear.addView(it) }

        val levelOptions = listOf(5, 10, 15, 20)
        val eqIds = listOf(0x00, 0x30, 0x31, 0x32, 0x33, 0xA0, 0xA1, 0xA2)

        // The value list has to follow the chosen action. It used to be a fixed
        // 4-item list that SET_MODE and SET_EQ_PRESET silently ignored, so those
        // two actions could only ever produce "noise cancelling" and "Heavy".
        fun fillValues(type: Automation.ActionType, select: Int) {
            val items: List<String> = when (type) {
                Automation.ActionType.SET_MODE -> Automation.Mode.values().map { it.label }
                Automation.ActionType.SET_AMBIENT_LEVEL,
                Automation.ActionType.SET_VOLUME -> levelOptions.map { "$it" }
                Automation.ActionType.SET_EQ_PRESET -> eqIds.map { Automation.presetName(it) }
                else -> emptyList()
            }
            valueSp.visibility =
                if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            valueSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            valueSp.setSelection(select.coerceIn(0, maxOf(0, items.size - 1)))
        }

        val initialType = actionTypes[doSp.selectedItemPosition]
        fillValues(initialType, when (initialType) {
            Automation.ActionType.SET_MODE ->
                Automation.Mode.values().indexOf(base.action.mode).coerceAtLeast(0)
            Automation.ActionType.SET_EQ_PRESET ->
                eqIds.indexOf(base.action.presetId).let { if (it < 0) 1 else it }
            Automation.ActionType.SET_AMBIENT_LEVEL, Automation.ActionType.SET_VOLUME ->
                levelOptions.indexOf(base.action.value).let { if (it < 0) 1 else it }
            else -> 0
        })

        doSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                fillValues(actionTypes[pos], 1)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existingIndex == null) "New rule" else "Edit rule")
            .setView(linear)
            .setPositiveButton("Save") { _, _ ->
                val type = actionTypes[doSp.selectedItemPosition]
                val pos = valueSp.selectedItemPosition
                val action = when (type) {
                    Automation.ActionType.SET_MODE -> Automation.Action(
                        type = type,
                        mode = Automation.Mode.values().getOrElse(pos) { Automation.Mode.NC },
                    )
                    Automation.ActionType.SET_AMBIENT_LEVEL ->
                        Automation.Action(type = type, value = levelOptions.getOrElse(pos) { 10 })
                    Automation.ActionType.SET_VOLUME ->
                        Automation.Action(type = type, value = levelOptions.getOrElse(pos) { 10 })
                    Automation.ActionType.SET_EQ_PRESET ->
                        Automation.Action(type = type, presetId = eqIds.getOrElse(pos) { 0x30 })
                    else -> Automation.Action(type = type)
                }
                val rule = base.copy(trigger = triggers[whenSp.selectedItemPosition], action = action)
                automationRules = if (existingIndex == null) {
                    automationRules + rule
                } else {
                    automationRules.toMutableList().also { it[existingIndex] = rule }
                }
                saveAutomation()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    // ---- Multipoint + feature state rendering ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun renderMultiPoint() {
        val box = binding.multiPointList
        box.removeAllViews()
        if (multiMacs.isEmpty()) {
            binding.textMultiPointStatus.text = if (peripheralSupported)
                "No paired devices reported (enable multipoint in the Sony app)"
            else "Peripheral/multipoint family not supported on this device"
            return
        }
        for (i in multiMacs.indices) {
            val name = multiNames.getOrElse(i) { multiMacs[i] }
            val isActive = multiActive.getOrElse(i) { false }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
                isClickable = true
                if (isActive) {
                    setBackgroundColor(0x2233AA88)
                    isEnabled = false
                }
            }
            val label = android.widget.TextView(this).apply {
                text = (if (isActive) "▶ " else "") + name
                textSize = 14f
                setTextColor(if (isActive) Color.rgb(120, 230, 180) else Color.rgb(210, 210, 210))
            }
            val macLabel = android.widget.TextView(this).apply {
                text = multiMacs[i]
                textSize = 11f
                setTextColor(Color.rgb(130, 130, 130))
                gravity = android.view.Gravity.END
            }
            row.addView(label, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(macLabel)
            if (!isActive) {
                row.setOnClickListener {
                    if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                    sendToService(ACTION_SOURCE_SWITCH) { putExtra(EXTRA_TARGET_MAC, multiMacs[i]) }
                    binding.textMultiPointStatus.text = "Switching to $name…"
                }
            }
            box.addView(row)
        }
        val activeName = multiMacs.indices.firstOrNull { multiActive.getOrElse(it) { false } }
            ?.let { multiNames.getOrNull(it) }
        binding.textMultiPointStatus.text = if (activeName != null)
            "Playing from: $activeName — tap another to switch"
        else "Tap a device to switch audio source"
    }

    private fun renderFeatureSwitches() {
        updatingUi = true
        binding.switchSpeakToChat.isChecked = speakToChat
        binding.switchPauseOff.isChecked = pauseWhenTakenOff
        binding.switchDsee.isChecked = dseeExtreme
        binding.switchBgm.isChecked = bgmMode
        binding.switchUpmix.isChecked = upmixCinema
        if (autoPowerOffMode != 0) {
            val idx = listOf(SonyMdrV2.AutoPowerOff.WHEN_TAKEN_OFF, SonyMdrV2.AutoPowerOff.NEVER)
                .indexOf(autoPowerOffMode)
            if (idx >= 0) binding.spinnerAutoPower.setSelection(idx)
        }
        updatingUi = false
        renderFeatureStatus()
    }

    /** Compact "what is switched on right now" line under the headphone card. */
    private fun renderFeatureStatus() {
        val on = buildList {
            if (dseeExtreme) add("DSEE")
            if (bgmMode) add("BGM")
            if (upmixCinema) add("Upmix")
            if (speakToChat) add("Speak-to-chat")
        }
        if (on.isEmpty()) {
            binding.textFeatureStatus.visibility = android.view.View.GONE
        } else {
            binding.textFeatureStatus.visibility = android.view.View.VISIBLE
            binding.textFeatureStatus.text = "On: " + on.joinToString(" · ")
        }
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
        sendToService(ACTION_SET_EQ_CUSTOM) { 
            putExtra(EXTRA_EQ_BANDS, binding.eqGraph.bandValues)
            putExtra(EXTRA_EQ_PRESET, selectedEQProfile)
        }
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
        // Show friendly names, not com.spotify.music
        val labels = s.map { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
        }.sorted()
        binding.textSelectedApps.text = when {
            !prefs.getBoolean("allowlist_enabled", false) -> "App filtering disabled"
            s.isEmpty() -> "No apps — nothing triggers"
            else -> labels.joinToString("\n")
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