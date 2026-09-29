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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_PROFILE_OVERRIDE
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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_APPLY_AMBIENT
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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_CONNECTION_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_AUTO_POWER_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_TARGET_MAC
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_REFRESH_DEVICES
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SOURCE_SWITCH
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_SPEAK_TO_CHAT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_PAUSE_TAKEN_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_DSEE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_BGM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_UPMIX
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_CONNECTION_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_AUTO_POWER
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_VOICE_GUIDANCE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SET_FIX_PLAYBACK
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_PAIRED_DEVICE_ACTION
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_ENTER_PAIRING_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_PAIRING_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_SWITCH_CONTROL_SUPPORTED
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_FIX_PLAYBACK
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_AUTO_PAUSED
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_TOGGLE_AUTO
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

    /** null until the headphones answer the connection-mode inquiry. */
    private var connectionSoundQuality: Boolean? = null
    private var connectionSpinnerReady = false
    private var autoPowerOffMode = 0
    /** True = "Fix Playback": multipoint will not hand audio to another device. */
    private var playbackFixed = false
    /** True while the headphones are in Bluetooth pairing mode. */
    private var pairingModeActive = false
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
                if (intent.hasExtra(EXTRA_CONNECTION_MODE)) {
                    connectionSoundQuality = intent.getBooleanExtra(EXTRA_CONNECTION_MODE, true)
                }
                autoPowerOffMode = intent.getIntExtra(EXTRA_AUTO_POWER_MODE, 0)
                playbackFixed = intent.getBooleanExtra(EXTRA_FIX_PLAYBACK, false)
                if (intent.hasExtra(EXTRA_AUTO_PAUSED)) {
                    autoPaused = intent.getBooleanExtra(EXTRA_AUTO_PAUSED, false)
                    updatePauseUi()
                }
                val swSupported = intent.getBooleanExtra(EXTRA_SWITCH_CONTROL_SUPPORTED, true)
                // Advisory only. A support-flag parse mistake previously disabled the
                // lock outright, which turns "the parse is wrong" into "the feature is
                // dead" with no way for the user to tell the difference. Keep the
                // button usable and surface the hint instead of gating on it.
                binding.btnFixPlayback.isEnabled = true
                binding.btnFixPlayback.text = when {
                    playbackFixed -> "Playback locked"
                    !swSupported -> "Fix playback (headset didn't advertise support)"
                    else -> "Fix playback"
                }
                pairingModeActive = intent.getBooleanExtra(EXTRA_PAIRING_MODE, false)
                binding.btnPairingMode.text =
                    if (pairingModeActive) "Leave pairing mode" else "Enter pairing mode"
                binding.btnFixPlayback.text =
                    if (playbackFixed) "Playback locked" else "Lock playback to this device"
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
        // Every pane starts `gone` in the layout and switchTab() was only ever
        // called from a click, so the app opened on a blank screen. Show Home.
        switchTab(0)

        // Dashboard
        binding.btnToggle.setOnClickListener { onToggleClicked() }
        // ---- Ambient sound control: the three modes live in one card, and the active
        // one is highlighted. The old quick buttons gave no feedback about which mode
        // was actually selected, so you had to read the notification to know.
        binding.btnModeNC.setOnClickListener { sendCommand(ACTION_ANC_ON) }
        binding.btnModeAmbient.setOnClickListener { sendCommand(ACTION_AMBIENT) }
        binding.btnModeOff.setOnClickListener { sendCommand(ACTION_ANC_OFF) }

        // Settings
        binding.btnRefresh.setOnClickListener { onRefreshClicked() }
        binding.seekLevel.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.textLevelValue.text = (v + 1).toString()
                if (fromUser) saveSetting("ambient_level", v + 1)
            }
            // Pushed on release, not per tick: the bar has 20 steps and every
            // intermediate value would put a frame on the socket.
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) { applyAmbientNow() }
        })
        binding.switchVoice.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            saveSetting("voice_passthrough", c); applyAmbientNow()
        }
        binding.switchAutoAmbient.setOnCheckedChangeListener { _, c ->
            if (updatingUi) return@setOnCheckedChangeListener
            saveSetting("auto_ambient", c); applyAmbientNow()
        }
        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { loadDeviceSettings() }
            override fun onNothingSelected(p: AdapterView<*>?) {} }

        // Protocol override. Auto-detect falls back to XM6 for any name it does not
        // recognise, which is the wrong thing to be stuck with on hardware you cannot
        // test — so the model can be pinned explicitly. Applies on next start.
        val profileValues = arrayOf("auto", "xm6", "xm5")
        val profileAdapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        profileAdapter.addAll("Auto-detect", "WH-1000XM6", "WH-1000XM5")
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProfile.adapter = profileAdapter
        var profileSpinnerReady = false
        binding.spinnerProfile.setSelection(
            profileValues.indexOf(profilePreference()).coerceAtLeast(0),
        )
        profileSpinnerReady = true
        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!profileSpinnerReady) return
                val value = profileValues.getOrElse(pos) { "auto" }
                getSharedPreferences("anc_settings", MODE_PRIVATE).edit()
                    .putString("profile_override", value).apply()
                updateProfileLabel()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateProfileLabel()

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

        // ---- Multipoint ----
        binding.btnRefreshDevices.setOnClickListener {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
            binding.textMultiPointStatus.text = "Refreshing…"
            sendToService(ACTION_REFRESH_DEVICES) {}
        }

        // Pause auto-switching. Lives as a switch on one row: the banner + button it
        // replaced were two full-width blocks for one bit of state, which is what made
        // Home feel crowded.
        binding.switchAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSwitch) return@setOnCheckedChangeListener
            if (!BluetoothAncService.isRunning) {
                suppressAutoSwitch = true
                binding.switchAuto.isChecked = !checked
                suppressAutoSwitch = false
                toast("Start service first")
                return@setOnCheckedChangeListener
            }
            // The switch reads "automations running", which is the NEGATION of
            // autoPaused. Assigning autoPaused = checked here inverted the state, so
            // updatePauseUi() wrote the switch straight back to where it started —
            // which is why the first tap looked like it did nothing and only the
            // second one took.
            if (checked == !autoPaused) return@setOnCheckedChangeListener
            sendToService(ACTION_TOGGLE_AUTO) {}
            autoPaused = !checked
            updatePauseUi()
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
            val enter = !pairingModeActive
            sendToService(ACTION_ENTER_PAIRING_MODE) { putExtra("enter", enter) }
            toast(if (enter) "Pairing mode — pair your new device, then press again to exit"
                  else "Leaving pairing mode")
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

        // Connection mode: sound quality vs connection stability. This picks a
        // *priority*, not a codec — the active codec is negotiated with the phone
        // and the protocol has no command to force LDAC/aptX.
        val connAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        connAdapter.addAll("Sound quality", "Connection priority")
        connAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerConnectionMode.adapter = connAdapter
        binding.spinnerConnectionMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!connectionSpinnerReady || updatingUi) return
                sendToService(ACTION_SET_CONNECTION_MODE) { putExtra("sound_quality", pos == 0) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        connectionSpinnerReady = true

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
            binding.routinesContent,
        )
        panes.forEachIndexed { i, v -> v.visibility = if (i == tab) View.VISIBLE else View.GONE }

        val labels = listOf(
            binding.tabHome, binding.tabAudio, binding.tabDevices,
            binding.tabRoutines,
        )
        val underlines = listOf(
            binding.underlineHome, binding.underlineAudio, binding.underlineDevices,
            binding.underlineRoutines,
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
        if (tab == 2 && BluetoothAncService.isRunning && multiMacs.isEmpty()) {
            // Entering Devices with an empty list used to show "no paired devices"
            // until the user found and pressed Refresh. Only when empty, so
            // re-entering the tab does not re-query on every visit.
            sendToService(ACTION_REFRESH_DEVICES) {}
        }
    }

    // ---- Automations + volume ----

    private var automationRules: List<Automation.Rule> = emptyList()

    /** Mirrors the service's autoPaused so Home and Routines agree with the notification. */
    private var autoPaused = false

    /** Set while writing state into switchAuto, so the listener does not fire a command. */
    private var suppressAutoSwitch = false

    private fun updatePauseUi() {
        suppressAutoSwitch = true
        binding.switchAuto.isChecked = !autoPaused
        suppressAutoSwitch = false
        binding.textAutoSwitch.text = if (autoPaused) "Automations paused" else "Automations"
        binding.textAutoSwitch.setTextColor(
            if (autoPaused) Color.rgb(255, 198, 92) else Color.rgb(224, 224, 224)
        )
        binding.cardAutoSwitch.setCardBackgroundColor(
            if (autoPaused) Color.rgb(48, 40, 24) else Color.rgb(45, 45, 45)
        )
        updateAutomationSummary()
    }

    /** Header on the Routines tab: how many rules are live, and whether pause is blocking them. */
    private fun updateAutomationSummary() {
        val active = automationRules.count { it.enabled }
        val total = automationRules.size
        when {
            autoPaused -> {
                binding.textAutomationSummary.visibility = View.VISIBLE
                binding.textAutomationSummary.text = "Paused — $active of $total rules will not fire"
                binding.textAutomationSummary.setTextColor(Color.rgb(255, 198, 92))
            }
            total > 0 -> {
                binding.textAutomationSummary.visibility = View.VISIBLE
                binding.textAutomationSummary.text = "$active of $total rules active · first match wins"
                binding.textAutomationSummary.setTextColor(Color.rgb(127, 212, 168))
            }
            else -> binding.textAutomationSummary.visibility = View.GONE
        }
    }

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
        updateAutomationSummary()
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
                setPadding(0, 10, 0, 10)
                isClickable = true
                setBackgroundColor(if (rule.enabled) 0x22334466 else 0x00000000)
            }
            // Order is the rule's behaviour, not decoration — the first match wins.
            row.addView(android.widget.TextView(this).apply {
                text = "${index + 1}"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(if (rule.enabled) Color.rgb(127, 212, 168) else Color.rgb(110, 110, 110))
                setPadding(0, 0, 10, 0)
            })
            val labels = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            labels.addView(android.widget.TextView(this).apply {
                text = rule.trigger.label.lowercase().replaceFirstChar { it.uppercase() }
                textSize = 11f
                setTextColor(if (rule.enabled) Color.rgb(150, 150, 150) else Color.rgb(100, 100, 100))
            })
            labels.addView(android.widget.TextView(this).apply {
                text = Automation.describe(rule)
                textSize = 14f
                setTextColor(if (rule.enabled) Color.rgb(230, 230, 230) else Color.rgb(120, 120, 120))
            })
            row.addView(labels, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

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
            box.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Same fix as the multipoint rows: these were stacked flush, so the
                // rule rows and the hint text ran together.
                if (index > 0) topMargin = dp(8)
            })
        }
        if (automationRules.isNotEmpty()) {
            box.addView(android.widget.TextView(this).apply {
                text = "Tap a rule to edit it · long-press to delete · order matters, first match wins"
                textSize = 11f
                setTextColor(Color.rgb(110, 110, 110))
                setPadding(4, 12, 0, 0)
            })
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
            Automation.ActionType.SET_SPEAK_TO_CHAT,
            Automation.ActionType.SET_PAUSE_TAKEN_OFF,
            Automation.ActionType.SET_DSEE,
            Automation.ActionType.SET_BGM,
            Automation.ActionType.SET_UPMIX,
            Automation.ActionType.SET_VOICE_PASSTHROUGH,
            Automation.ActionType.SET_AUTO_AMBIENT,
            Automation.ActionType.SET_AUTO_POWER,
            Automation.ActionType.SET_VOICE_GUIDANCE,
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
            val items: List<String> = when {
                type in Automation.ActionType.TOGGLES -> listOf("On", "Off")
                type == Automation.ActionType.SET_AUTO_POWER ->
                    Automation.AutoPower.values().map { it.label }
                type == Automation.ActionType.SET_VOICE_GUIDANCE -> levelOptions.map { "$it" }
                type == Automation.ActionType.SET_MODE -> Automation.Mode.values().map { it.label }
                type == Automation.ActionType.SET_AMBIENT_LEVEL -> levelOptions.map { "$it" }
                type == Automation.ActionType.SET_EQ_PRESET -> eqIds.map { Automation.presetName(it) }
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
            Automation.ActionType.SET_AUTO_POWER ->
                Automation.AutoPower.values().indexOf(base.action.power).coerceAtLeast(0)
            Automation.ActionType.SET_AMBIENT_LEVEL,
            Automation.ActionType.SET_VOICE_GUIDANCE ->
                levelOptions.indexOf(base.action.value).let { if (it < 0) 1 else it }
            in Automation.ActionType.TOGGLES -> if (base.action.value != 0) 0 else 1
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
                    Automation.ActionType.SET_VOICE_GUIDANCE ->
                        Automation.Action(type = type, value = levelOptions.getOrElse(pos) { 10 })
                    Automation.ActionType.SET_EQ_PRESET ->
                        Automation.Action(type = type, presetId = eqIds.getOrElse(pos) { 0x30 })
                    Automation.ActionType.SET_AUTO_POWER -> Automation.Action(
                        type = type,
                        power = Automation.AutoPower.values().getOrElse(pos) { Automation.AutoPower.WHEN_TAKEN_OFF },
                    )
                    in Automation.ActionType.TOGGLES ->
                        Automation.Action(type = type, value = if (pos == 0) 1 else 0)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

            // Was a single row crammed with name + MAC + a full Button, which
            // rendered as one long strip with a grey block hanging off the end.
            // Now: device identity on the first line, the quiet detail on the
            // second, and the destructive action as a borderless text action
            // rather than a filled button competing with the row itself.
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isActive) R.drawable.bg_device_row_active else R.drawable.bg_device_row
                )
                isClickable = !isActive
            }

            val top = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            top.addView(android.widget.TextView(this).apply {
                text = name
                textSize = 15f
                setTextColor(if (isActive) Color.rgb(140, 225, 190) else Color.rgb(230, 230, 230))
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Unpair removes this device from the headset's paired list entirely.
            top.addView(android.widget.TextView(this).apply {
                text = "Unpair"
                textSize = 12f
                setTextColor(Color.rgb(226, 128, 128))
                setPadding(dp(16), dp(6), dp(8), dp(6))
                setOnClickListener {
                    if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                    sendToService(ACTION_PAIRED_DEVICE_ACTION) {
                        putExtra("mac", multiMacs[i])
                        putExtra("action", SonyMdrV2.CONN_ACTION_UNPAIR)
                    }
                    binding.textMultiPointStatus.text = "Unpairing $name…"
                }
            })
            row.addView(top)

            val bottom = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            bottom.addView(android.widget.TextView(this).apply {
                text = multiMacs[i]
                textSize = 11f
                setTextColor(Color.rgb(120, 120, 120))
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bottom.addView(android.widget.TextView(this).apply {
                text = if (isActive) "Active" else "Tap to switch"
                textSize = 11f
                setTextColor(if (isActive) Color.rgb(140, 225, 190) else Color.rgb(110, 110, 110))
            })
            row.addView(bottom)

            if (!isActive) {
                row.setOnClickListener {
                    if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                    sendToService(ACTION_SOURCE_SWITCH) { putExtra(EXTRA_TARGET_MAC, multiMacs[i]) }
                    binding.textMultiPointStatus.text = "Switching to $name…"
                }
            }
            box.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Rows were stacked with no gap, so adjacent devices shared a
                // border and read as one block.
                if (i > 0) topMargin = dp(8)
            })
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
        connectionSoundQuality?.let { sq ->
            binding.spinnerConnectionMode.setSelection(if (sq) 0 else 1)
            binding.textConnectionStatus.text = if (sq) {
                "Sound quality — the headphones may use LDAC or aptX when the phone offers them."
            } else {
                "Connection priority — favours stability and range over audio quality."
            }
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
            binding.textEQStatus.text = preset.displayName
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
            "${preset.displayName} [${bands.joinToString(" ") { "%+d".format(it) }}]"
        } else {
            preset.displayName
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
        val name = pairedDevices.find { it.address == addr }?.name ?: ""
        updateProfileLabel(name)
    }


    // App filtering removed: it needed notification-listener access, and the
    // dependency was invisible when missing. Triggering now keys off whether
    // audio is playing at all.

    /**
     * Re-sends the ambient payload so a change to level / voice passthrough /
     * auto-ambient reaches the headphones now. These three controls only ever
     * called saveSetting, so a new value was not heard until the next
     * connection — the five feature switches below already pushed immediately.
     */
    private fun applyAmbientNow() {
        if (BluetoothAncService.isRunning) sendToService(ACTION_APPLY_AMBIENT) {}
    }

    private fun profilePreference(): String =
        getSharedPreferences("anc_settings", MODE_PRIVATE).getString("profile_override", "auto") ?: "auto"

    /**
     * Mirrors the protocol the service will actually use. On auto it names the model
     * detect() resolved to, so the silent XM6 fallback is visible rather than a
     * surprise at connect time.
     */
    private fun updateProfileLabel(deviceName: String = "") {
        val pinned = profilePreference()
        binding.textProfile.text = when (pinned) {
            "xm5" -> "Protocol: WH-1000XM5 (pinned)"
            "xm6" -> "Protocol: WH-1000XM6 (pinned)"
            else -> {
                val guess = HeadphoneProfile.detect(deviceName).modelName
                val warn = if (deviceName.contains("XM", ignoreCase = true)) "" else "  ·  name not recognised"
                "Protocol: $guess$warn"
            }
        }
    }

    private fun saveSetting(key: String, value: Any) {
        val addr = selectedAddress ?: return
        val e = getSharedPreferences("anc_settings", MODE_PRIVATE).edit()
        when (value) { is Int -> e.putInt("${key}_$addr", value); is Boolean -> e.putBoolean("${key}_$addr", value) }
        e.apply()
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
            updateCardStatus("Bluetooth off"); binding.btnToggle.isEnabled = false; return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) { updateCardStatus("Permission needed"); binding.btnToggle.isEnabled = false; return }

        for (d in (adapter.bondedDevices ?: emptySet())) {
            val name = d.name ?: ""
            if (name.contains("WH-1000XM", ignoreCase = true)) pairedDevices.add(DeviceInfo(name, d.address))
        }
        if (pairedDevices.isEmpty()) {
            binding.textModel.text = "No headphones"; updateCardStatus("Pair in Settings → Bluetooth"); binding.btnToggle.isEnabled = false
            updateModelArt("")
        } else {
            val first = pairedDevices.first().name
            binding.textModel.text = first; updateCardStatus("${pairedDevices.size} device(s)"); binding.btnToggle.isEnabled = true
            updateModelArt(first)
        }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = listAdapter
        loadDeviceSettings()
    }

    // ---- Model artwork ----

    /**
     * Swap the hero image for the connected model, falling back to the vector icon
     * for anything without artwork yet (XM5, and unrecognised names). Product shots
     * live in drawable-nodpi so they are not rescaled per screen density.
     */
    private fun updateModelArt(name: String) {
        binding.imageModel.setImageResource(
            when {
                name.contains("XM6", ignoreCase = true) -> R.drawable.model_xm6
                name.contains("XM5", ignoreCase = true) -> R.drawable.model_xm5
                else -> R.drawable.ic_headphones_big
            }
        )
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
            action = ACTION_START
            putExtra(EXTRA_ADDRESS, device.address)
            putExtra(EXTRA_PROFILE_OVERRIDE, profilePreference())
        }.also { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it) }
        updateServiceRunning(true); updateCardStatus("Starting…")
    }

    private fun stopService() {
        sendToService(ACTION_STOP) {}
        updateServiceRunning(false); updateCardStatus("⏹ Stopped")
    }

    // ---- Dashboard card ----

    private fun updateServiceRunning(running: Boolean) {
        serviceRunning = running
        binding.btnToggle.text = if (running) "Stop" else "Start"
        // Tint rather than setBackgroundColor. The latter overrode the rounded
        // shape drawable, which is why this rendered as a hard square slab. The
        // old holo_green_dark is also a very saturated green; muted to sit with
        // the rest of the palette.
        binding.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (running) Color.rgb(0x6A, 0x32, 0x32) else Color.rgb(0x3A, 0x5C, 0x46)
        )
    }

    private fun updateStatusDisplay(status: BluetoothAncService.Status, msg: String? = null) {
        // A single geometric dot carrying the state in its colour, rather than a
        // per-status emoji. Same affordance, and it tints with the palette.
        val tint = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> Color.rgb(138, 138, 138)
            BluetoothAncService.Status.CONNECTING -> Color.rgb(232, 196, 120)
            BluetoothAncService.Status.CONNECTED -> Color.rgb(127, 212, 168)
            BluetoothAncService.Status.ERROR -> Color.rgb(226, 128, 128)
        }
        val label = status.name.lowercase().replaceFirstChar { it.uppercase() }
        // The broadcast message usually restates the status and sometimes adds
        // detail ("Connecting…", "Disconnected, reconnecting…"). Comparing for
        // equality was not enough — an ellipsis or trailing word both defeat it,
        // which is why CONNECTING still rendered "CONNECTING — Connecting…".
        // If the message already begins with the label, show the message alone:
        // no duplication, and nothing is lost.
        val text = when {
            msg == null -> label
            msg.trim().lowercase().startsWith(label.lowercase()) -> msg
            else -> "$label — $msg"
        }
        updateCardStatus(text)
        binding.textStatus.setTextColor(tint)
    }

    private fun updateCardStatus(text: String) { binding.textStatus.text = text }

    private fun updateCardStats() {
        binding.textBattery.text = battery?.let { "$it%" } ?: "—"
        binding.textMode.text = currentMode
        updateModeHighlight()
    }

    /**
     * Sony-style feedback: the selected mode is the filled circle, the rest are
     * outlines. The old quick-mode buttons were static colours, so nothing on the
     * screen said which mode was actually active.
     */
    private fun updateModeHighlight() {
        val m = currentMode
        setModeState(binding.btnModeNC, binding.labelModeNC, m == "NC")
        setModeState(binding.btnModeAmbient, binding.labelModeAmbient, m.startsWith("Ambient"))
        setModeState(binding.btnModeOff, binding.labelModeOff, m == "Off" || m == "—")
    }

    private fun setModeState(
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        active: Boolean,
    ) {
        // Drives the bg_mode_circle selector: selected = filled.
        icon.isSelected = active
        // The circle fills with near-white when selected, so the glyph has to
        // invert with it or it disappears into the background.
        icon.setColorFilter(if (active) Color.rgb(38, 38, 38) else Color.rgb(150, 150, 150))
        label.setTextColor(if (active) Color.rgb(224, 224, 224) else Color.rgb(138, 138, 138))
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.actionPowerOff) {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return true }
            sendToService(ACTION_POWER_OFF) {}
            Toast.makeText(this, "Powering off…", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}