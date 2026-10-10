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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_RECONNECT_NOW
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
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_MULTI_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_ACTIVE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_CONNECTED
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DEVICE_NAME
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_SPEAK_TO_CHAT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_PAUSE_TAKEN_OFF
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_DSEE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_BGM
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_UPMIX
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_CONNECTION_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_SENSE_DEBUG
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_LDAC_ACTIVE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_ACTIVE_CODEC
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_HEADPHONES_WORN
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_LE_AUDIO
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_QUICK_ACCESS_FUNCTIONS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_UPSCALING_EFFECT
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_UPSCALING_STATUS
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_AUTO_POWER_MODE
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_TARGET_MAC
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_REFRESH_DEVICES
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SYNC_STATE
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

    // Pending EQ write: holds the status label on what is being applied until
    // the headset confirms it (matching preset, and matching bands for a
    // custom write) or the window expires. Without the hold, every unrelated
    // stats broadcast re-rendered the *old* preset in between and the label
    // appeared to jump back and forth.
    private var eqPendingId: Int? = null
    private var eqPendingBands: IntArray? = null
    private var eqPendingAt = 0L
    private val eqPendingTimeoutMs = 5_000L

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

    /**
     * Last raw SENSE frame from the service. Kept only for the log: the XM6
     * ignores every SENSE poll, so the wear state comes from the SYSTEM push.
     */
    private var senseDebug: String? = null

    /** null until the headphones report LDAC activity. */
    private var ldacActive: Boolean? = null

    /** Active Bluetooth codec id, or null until first report. */
    private var activeCodec: Int? = null

    /** true = on head, false = off head, null = unknown. */
    private var headphonesWorn: Boolean? = null

    /**
     * Spinner.setSelection fires onItemSelected asynchronously, so by the time
     * the listener runs the shared [updatingUi] window has already closed and a
     * programmatic update looks like a user action. This flag is cleared on the
     * next frame instead, and only guards this one spinner.
     */
    private var suppressConnectionCallback = false

    /** Same guard for the LE Audio switch, which also fires its listener async. */

    /** LE Audio transport active, or null until reported. */
    private var leAudio: Boolean? = null   // transport state, logged only; no UI yet

    /** Quick access functions as reported. Index 0 = left, 1 = right. */
    private var quickAccessFunctions: IntArray? = null

    /** DSEE/upscaling status, or null until reported. */
    private var upscalingStatus: Int? = null

    /** Which upscaling variant the headset has selected, or null until reported. */
    private var upscalingEffect: Int? = null

    /** The four wire values the app is known to send, in menu order. */
    private val qaValues = intArrayOf(
        SonyMdrV2.QUICK_ACCESS_NONE,
        SonyMdrV2.QUICK_ACCESS_SPTF,
        SonyMdrV2.QUICK_ACCESS_ENDEL,
        SonyMdrV2.QUICK_ACCESS_AMAZON_MUSIC,
        SonyMdrV2.QUICK_ACCESS_YOUTUBE_MUSIC,
    )

    private var suppressQaCallback = false

    /**
     * Slots whose current device value is not in [qaValues].
     *
     * The Quick Access write is whole-array, so a slot holding an assignment this
     * app cannot render used to be overwritten with whatever the spinner happened
     * to display — turning a real service into "None". That is how a YouTube
     * Music assignment (0x0c) set in the Sony app got silently reverted here
     * within a few seconds of being read. Such slots are now carried through
     * untouched instead.
     */
    private var qaUnmapped = booleanArrayOf(false, false)

    /** Last array actually written, so a repeated selection does not rewrite it. */
    private var lastSentQa: IntArray? = null

    private var renderScheduled = false

    /**
     * Coalesce a burst of status broadcasts into a single redraw.
     *
     * queryAllState fires around twenty queries at once and every reply used to
     * trigger a full synchronous re-render, so the new widgets appeared late and
     * all together. The data had not arrived slowly — the redraws were queued
     * behind each other.
     */
    private fun markRenderDirty() {
        if (renderScheduled) return
        renderScheduled = true
        binding.root.post {
            renderScheduled = false
            renderFeatureSwitches()
            renderFeatureStatus()
        }
    }

    /**
     * Show a reported function value without firing the write listener. An
     * unmapped value leaves the spinner alone rather than silently snapping to a
     * different assignment.
     */
    private fun bindQaSpinner(spinner: android.widget.Spinner, value: Int) {
        val idx = qaValues.indexOf(value)
        // Record whether this slot's device value is one we can actually show.
        val slot = if (spinner === binding.spinnerQaLeft) 0 else 1
        qaUnmapped[slot] = idx < 0
        if (idx >= 0 && spinner.selectedItemPosition != idx) {
            suppressQaCallback = true
            spinner.setSelection(idx, false)
            spinner.post { suppressQaCallback = false }
        }
        // A new value has arrived from the device, so the next user selection is a
        // real change rather than a replay of what is already stored.
        lastSentQa = null
    }
    private var autoPowerOffMode = 0
    /** True = "Fix Playback": multipoint will not hand audio to another device. */
    private var playbackFixed = false
    /** True while the headphones are in Bluetooth pairing mode. */
    private var pairingModeActive = false
    private var multiNames: Array<String> = emptyArray()
    private var multiMacs: Array<String> = emptyArray()
    private var multiActive: BooleanArray = BooleanArray(0)
    private var multiConnected: BooleanArray = BooleanArray(0)

    /** The service's live view of the connected device's Bluetooth name; fresher
     *  than the bonded cache, which only updates when the headphones reconnect. */
    private var serviceName: String? = null

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
                // The service now reports its own liveness. This matters for
                // WAITING — service up, headphones away — which previously fell
                // through to "not running" here and made the Home button offer
                // Start on a service that was in fact alive and waiting.
                updateServiceRunning(
                    if (intent.hasExtra(BluetoothAncService.EXTRA_SERVICE_RUNNING))
                        intent.getBooleanExtra(BluetoothAncService.EXTRA_SERVICE_RUNNING, false)
                    else s != BluetoothAncService.Status.DISCONNECTED && s != BluetoothAncService.Status.ERROR
                )
                updateRetryVisibility(s)
                // When the service reports it is not running, its readouts are
                // stale by definition — clear them instead of showing leftovers.
                // A stopped service also stops sending battery extras, so this is
                // the only place the old value would ever be dropped.
                if (intent.hasExtra(BluetoothAncService.EXTRA_SERVICE_RUNNING) &&
                    !intent.getBooleanExtra(BluetoothAncService.EXTRA_SERVICE_RUNNING, false)
                ) {
                    battery = null
                    currentMode = "—"
                    // The live name belonged to the (now stopped) connection;
                    // fall back to the bonded cache again.
                    serviceName = null
                }
                if (intent.hasExtra(EXTRA_BATTERY)) battery = intent.getIntExtra(EXTRA_BATTERY, 0)
                if (intent.hasExtra(EXTRA_MODE)) currentMode = intent.getStringExtra(EXTRA_MODE) ?: "—"
                updateCardStats()
                if (!serviceRunning) updateHeroTitle()
                // Link-derived readouts die with the link: codec/LDAC, DSEE and
                // the wear glyph describe a live connection only. Without this
                // they lingered across disconnects, since the service stops
                // sending their extras and nothing else ever cleared them.
                if (s != BluetoothAncService.Status.CONNECTED) {
                    activeCodec = null
                    ldacActive = null
                    dseeExtreme = false
                    headphonesWorn = null
                    markRenderDirty()
                }
            }
            // EQ state from service. Everything the display needs — editability,
            // curve sync, pending-write resolution — lives in updateEQStatus.
            if (intent?.hasExtra(EXTRA_EQ_ACTIVE_PRESET) == true) {
                eqActivePreset = intent.getIntExtra(EXTRA_EQ_ACTIVE_PRESET, 0)
                eqActiveBands = intent.getIntArrayExtra(EXTRA_EQ_ACTIVE_BANDS)
                updateEQStatus()
            }

            // The service's live view of the connected device's Bluetooth name —
            // fresher than the bonded cache, which only updates on a reconnect.
            if (intent?.hasExtra(EXTRA_DEVICE_NAME) == true) {
                serviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                updateHeroTitle()
            }

            // Codec and wear ride on their own broadcasts, unrelated to the
            // peripheral sweep below. Reading them inside that block meant they
            // were only seen when it happened to fire, which made the codec look
            // like it only appeared on a change.
            if (intent?.hasExtra(EXTRA_ACTIVE_CODEC) == true) {
                activeCodec = intent.getIntExtra(EXTRA_ACTIVE_CODEC, 0xFF)
                markRenderDirty()
            }
            if (intent?.hasExtra(EXTRA_HEADPHONES_WORN) == true) {
                headphonesWorn = intent.getBooleanExtra(EXTRA_HEADPHONES_WORN, false)
                markRenderDirty()
            }

            if (intent?.hasExtra(EXTRA_LE_AUDIO) == true) {
                leAudio = intent.getBooleanExtra(EXTRA_LE_AUDIO, false)
            }

            if (intent?.hasExtra(EXTRA_QUICK_ACCESS_FUNCTIONS) == true) {
                quickAccessFunctions = intent.getIntArrayExtra(EXTRA_QUICK_ACCESS_FUNCTIONS)
                markRenderDirty()
            }

            if (intent?.hasExtra(EXTRA_UPSCALING_STATUS) == true) {
                upscalingStatus = intent.getIntExtra(EXTRA_UPSCALING_STATUS, 0xFF)
                markRenderDirty()
            }

            if (intent?.hasExtra(EXTRA_UPSCALING_EFFECT) == true) {
                upscalingEffect = intent.getIntExtra(EXTRA_UPSCALING_EFFECT, 0xFF)
                markRenderDirty()
            }

            // Multipoint + headphone feature state
            if (intent?.hasExtra(EXTRA_PERIPHERAL_OK) == true) {
                peripheralSupported = intent.getBooleanExtra(EXTRA_PERIPHERAL_OK, false)
                if (intent.hasExtra(EXTRA_DEVICE_MACS)) {
                    multiNames = intent.getStringArrayExtra(EXTRA_DEVICE_LIST) ?: emptyArray()
                    multiMacs = intent.getStringArrayExtra(EXTRA_DEVICE_MACS) ?: emptyArray()
                    multiActive = intent.getBooleanArrayExtra(EXTRA_DEVICE_ACTIVE) ?: BooleanArray(0)
                    multiConnected = intent.getBooleanArrayExtra(EXTRA_DEVICE_CONNECTED) ?: BooleanArray(0)
                }
                speakToChat = intent.getBooleanExtra(EXTRA_SPEAK_TO_CHAT, false)
                pauseWhenTakenOff = intent.getBooleanExtra(EXTRA_PAUSE_TAKEN_OFF, false)
                dseeExtreme = intent.getBooleanExtra(EXTRA_DSEE, false)
                bgmMode = intent.getBooleanExtra(EXTRA_BGM, false)
                upmixCinema = intent.getBooleanExtra(EXTRA_UPMIX, false)
                if (intent.hasExtra(EXTRA_CONNECTION_MODE)) {
                    connectionSoundQuality = intent.getBooleanExtra(EXTRA_CONNECTION_MODE, true)
                }
                if (intent.hasExtra(EXTRA_SENSE_DEBUG)) {
                    senseDebug = intent.getStringExtra(EXTRA_SENSE_DEBUG)
                }
                if (intent.hasExtra(EXTRA_LDAC_ACTIVE)) {
                    ldacActive = intent.getBooleanExtra(EXTRA_LDAC_ACTIVE, false)
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
                // One assignment. A second, shorter one used to follow immediately
                // below and silently clobber this, so the "didn't advertise support"
                // hint could never appear.
                binding.btnFixPlayback.text = when {
                    playbackFixed -> "Playback locked to this device"
                    !swSupported -> "Lock playback (headset didn't advertise support)"
                    else -> "Lock playback to this device"
                }
                pairingModeActive = intent.getBooleanExtra(EXTRA_PAIRING_MODE, false)
                binding.btnPairingMode.text =
                    if (pairingModeActive) "Leave pairing mode" else "Enter pairing mode"
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

    /**
     * Re-query the headset whenever the app comes to the foreground.
     *
     * The connect sweep runs exactly once, at connect. If the service was already
     * connected when the app was opened — which is the normal case after the
     * first run — nothing had re-queried anything since, so every field sat stale
     * until the 60s poll happened to come round. That is what made the newer
     * widgets look slow: the data was old, not late.
     */
    override fun onStart() {
        super.onStart()
        sendToService(ACTION_SYNC_STATE) {}
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
        // Shown only while the service is up but the link is not — the service
        // already retries on its own; this skips the wait when the headphones
        // have just come back and the next scheduled attempt is still a minute
        // out.
        binding.btnRetryNow.setOnClickListener {
            if (!serviceRunning) {
                Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnRetryNow.isEnabled = false
            binding.btnRetryNow.text = "Retrying…"
            sendToService(ACTION_RECONNECT_NOW) {}
            // Re-enabled by the next status broadcast, which the connect attempt
            // produces almost immediately ("Connecting…" → connected or back to
            // waiting). A fallback keeps the button from getting stuck if no
            // broadcast arrives.
            Handler(Looper.getMainLooper()).postDelayed({
                binding.btnRetryNow.isEnabled = true
                if (binding.btnRetryNow.text == "Retrying…") binding.btnRetryNow.text = "Retry now"
            }, 4000)
        }
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

        updateProfileLabel()

        // EQ
        binding.btnEQOff.setOnClickListener { selectPreset(0x00) }
        binding.btnEQHeavy.setOnClickListener { selectPreset(0x30) }
        binding.btnEQClear.setOnClickListener { selectPreset(0x31) }
        binding.btnEQHard.setOnClickListener { selectPreset(0x32) }
        binding.btnEQSoft.setOnClickListener { selectPreset(0x33) }
        binding.btnEQCustom.setOnClickListener { selectEditablePreset(0xA0) }
        binding.btnEQUser1.setOnClickListener { selectEditablePreset(0xA1) }
        binding.btnEQUser2.setOnClickListener { selectEditablePreset(0xA2) }
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
        // The lock applies to whichever device is playing, so its control now
        // lives on that device's row (see renderMultiPoint). The large standalone
        // button is hidden rather than removed so the wiring stays in one place.
        binding.btnFixPlayback.setOnClickListener {
            if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
            val next = !playbackFixed
            sendToService(ACTION_SET_FIX_PLAYBACK) { putExtra("fix", next) }
            toast(if (next) "Locking playback to this device" else "Playback lock released")
        }
        binding.btnFixPlayback.visibility = android.view.View.GONE

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

        // Hidden debug menu: five taps on the headphone image.
        //
        // This is deliberately undiscoverable. The menu sends arbitrary payloads
        // at the headphones, which is fine for someone who knows they tapped into
        // it and a bad surprise for anyone who does not.
        var debugTaps = 0
        var lastDebugTap = 0L
        binding.imageModel.setOnClickListener {
            val now = System.currentTimeMillis()
            // A slow series of taps is not a series of taps.
            if (now - lastDebugTap > 1500L) debugTaps = 0
            lastDebugTap = now
            debugTaps++
            if (debugTaps >= 5) {
                debugTaps = 0
                startActivity(android.content.Intent(this, DebugActivity::class.java))
            }
        }

        binding.spinnerQaLeft.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            qaValues.map { SonyMdrV2.quickAccessName(it) },
        )
        binding.spinnerQaRight.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            qaValues.map { SonyMdrV2.quickAccessName(it) },
        )
        val qaListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long,
            ) {
                if (suppressQaCallback || !connectionSpinnerReady) return
                // The write is whole-array, so keep any entries beyond L and R intact.
                val base = quickAccessFunctions
                    ?: intArrayOf(SonyMdrV2.QUICK_ACCESS_NONE, SonyMdrV2.QUICK_ACCESS_NONE)
                val out = base.copyOf(maxOf(base.size, 2))
                // Preserve any slot the device holds a value for that this app
                // cannot represent, rather than substituting the spinner's value.
                if (!qaUnmapped[0])
                    out[0] = qaValues[binding.spinnerQaLeft.selectedItemPosition]
                if (!qaUnmapped[1])
                    out[1] = qaValues[binding.spinnerQaRight.selectedItemPosition]
                // Both spinners fire their initial callback, so the same array used to
                // be written twice back to back with consecutive sequence numbers.
                if (out.contentEquals(lastSentQa)) return
                lastSentQa = out
                sendToService(BluetoothAncService.ACTION_SET_QUICK_ACCESS) {
                    putExtra("functions", out)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.spinnerQaLeft.onItemSelectedListener = qaListener
        binding.spinnerQaRight.onItemSelectedListener = qaListener

        // Connection mode: sound quality vs connection stability. This picks a
        // *priority*, not a codec — the active codec is negotiated with the phone
        // and the protocol has no command to force LDAC/aptX.
        val connAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        connAdapter.addAll("Sound quality", "Connection priority")
        connAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerConnectionMode.adapter = connAdapter
        binding.spinnerConnectionMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!connectionSpinnerReady || suppressConnectionCallback) return
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
        // Rule id -> row, so a drag can move the live view rather than rebuilding
        // the list underneath itself. Re-rendering mid-drag destroys the view
        // being dragged and the gesture stalls.
        val rowsById = mutableMapOf<String, android.view.View>()

        automationRules.forEachIndexed { index, rule ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setBackgroundColor(if (rule.enabled) 0x22334466 else 0x00000000)
            }
            rowsById[rule.id] = row

            // Order is the rule's behaviour, not decoration — the first match wins —
            // so it needs a handle that can be grabbed, and a number that shows
            // where the rule currently sits.
            val handle = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(6), dp(6), dp(6))
                contentDescription = "Reorder rule ${index + 1}"
                setOnLongClickListener { v ->
                    val clip = android.content.ClipData.newPlainText("rule", rule.id)
                    v.startDragAndDrop(clip, android.view.View.DragShadowBuilder(v), rule.id, 0)
                    true
                }
            }
            handle.addView(android.widget.TextView(this).apply {
                text = "≡"
                textSize = 18f
                setTextColor(if (rule.enabled) Color.rgb(127, 212, 168) else Color.rgb(110, 110, 110))
            })
            handle.addView(android.widget.TextView(this).apply {
                text = "${index + 1}"
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(if (rule.enabled) Color.rgb(127, 212, 168) else Color.rgb(110, 110, 110))
            })
            row.addView(handle)

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

            // Delete is an explicit, labelled control. It used to be a long-press
            // on the row, which is the same gesture that starts a drag, so the
            // first attempt to reorder silently destroyed a rule. Nothing this
            // destructive should be reachable by accident.
            row.addView(android.widget.TextView(this).apply {
                text = "✕"
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.rgb(226, 128, 128))
                setPadding(dp(14), dp(8), dp(10), dp(8))
                contentDescription = "Delete rule ${index + 1}"
                setOnClickListener {
                    val name = rule.trigger.label
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage("Delete \"$name\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            automationRules = automationRules.filterNot { it.id == rule.id }
                            saveAutomation()
                            renderAutomation()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })

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

            // Reorder as the row is entered, so the list follows the finger, and
            // only the final position is written to storage.
            row.setOnDragListener { _, ev ->
                when (ev.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED ->
                        ev.clipDescription?.hasMimeType("text/plain") == true
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                        val id = ev.clipData?.getItemAt(0)?.text?.toString()
                        val from = automationRules.indexOfFirst { it.id == id }
                        if (id != null && from >= 0 && from != index) {
                            automationRules = automationRules.toMutableList().also {
                                it.add(index, it.removeAt(from))
                            }
                            rowsById[id]?.let { moved ->
                                box.removeView(moved)
                                box.addView(moved, index)
                            }
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DROP, android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        saveAutomation()
                        renderAutomation()
                        true
                    }
                    else -> true
                }
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
                text = "Tap to edit · hold ⠿ to reorder · order matters, first match wins"
                textSize = 11f
                setTextColor(Color.rgb(110, 110, 110))
                setPadding(dp(4), dp(12), 0, 0)
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
            Automation.ActionType.SET_VOLUME,
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
        fun <T> spinner(caption: String, items: List<T>, selected: Int): android.widget.Spinner {
            linear.addView(android.widget.TextView(this).apply {
                text = caption
                textSize = 11f
                setTextColor(Color.rgb(150, 150, 150))
            })
            val sp = android.widget.Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            sp.setSelection(selected)
            linear.addView(sp)
            return sp
        }

        val whenSp = spinner("When", triggers.map { it.label }, triggers.indexOf(base.trigger))
        val doSp = spinner("Do", actionTypes.map { it.label }, actionTypes.indexOf(base.action.type).coerceAtLeast(0))
        val valueSp = android.widget.Spinner(this).also {
            linear.addView(android.widget.TextView(this).apply {
                text = "Value"
                textSize = 11f
                setTextColor(Color.rgb(150, 150, 150))
            })
            linear.addView(it)
        }

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
                type == Automation.ActionType.SET_VOLUME -> levelOptions.map { "$it" }
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
            Automation.ActionType.SET_VOLUME,
            Automation.ActionType.SET_VOICE_GUIDANCE ->
                levelOptions.indexOf(base.action.value).let { if (it < 0) 1 else it }
            in Automation.ActionType.TOGGLES -> if (base.action.value != 0) 0 else 1
            else -> 0
        })

        /**
         * The action the three spinners currently describe.
         *
         * Used by both the live preview and the save handler. Keeping one builder
         * means the sentence shown while editing is the sentence that gets stored
         * -- a preview assembled by a second, near-identical `when` would drift
         * from the rule it claims to describe, which is exactly how the EQ screen
         * came to read a preset correctly while gating on a different one.
         */
        fun currentAction(): Automation.Action {
            val type = actionTypes[doSp.selectedItemPosition]
            val pos = valueSp.selectedItemPosition
            return when (type) {
                Automation.ActionType.SET_MODE -> Automation.Action(
                    type = type,
                    mode = Automation.Mode.values().getOrElse(pos) { Automation.Mode.NC },
                )
                Automation.ActionType.SET_AMBIENT_LEVEL,
                Automation.ActionType.SET_VOLUME,
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
        }

        val preview = android.widget.TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(127, 212, 168))
            setPadding(0, 16, 0, 4)
        }
        linear.addView(preview)
        fun refreshPreview() {
            val trigger = triggers[whenSp.selectedItemPosition]
            preview.text = "When ${trigger.label.lowercase()}, " +
                Automation.describe(
                    base.copy(trigger = trigger, action = currentAction()),
                )
        }
        doSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                fillValues(actionTypes[pos], 1)
                refreshPreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listOf(whenSp, valueSp).forEach { sp ->
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    refreshPreview()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        refreshPreview()

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existingIndex == null) "New rule" else "Edit rule")
            .setView(linear)
            .setPositiveButton("Save") { _, _ ->
                val rule = base.copy(
                    trigger = triggers[whenSp.selectedItemPosition],
                    action = currentAction(),
                )
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

        // Connected devices first, stable within the two groups. The original
        // indices are kept so row callbacks still address the source arrays.
        val order = multiMacs.indices
            .sortedByDescending { multiConnected.getOrElse(it) { false } }

        for ((visualIndex, i) in order.withIndex()) {
            val name = multiNames.getOrElse(i) { multiMacs[i] }
            val isActive = multiActive.getOrElse(i) { false }
            val isConnected = multiConnected.getOrElse(i) { false }

            // Device identity on the first line, the quiet detail on the
            // second, and the destructive action as a borderless text action
            // rather than a filled button competing with the row itself.
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isActive) R.drawable.bg_device_row_active else R.drawable.bg_device_row
                )
                // Switching to a device the headphones are not connected to is
                // refused by the headset; do not offer it as a tap target.
                isClickable = !isActive && isConnected
            }

            val top = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            // A state dot ahead of the name carries connected/active at a
            // glance, independent of the label on the right.
            top.addView(android.widget.TextView(this).apply {
                text = "●"
                textSize = 11f
                setTextColor(
                    when {
                        isActive -> Color.rgb(140, 225, 190)
                        isConnected -> Color.rgb(150, 190, 230)
                        else -> Color.rgb(95, 95, 95)
                    },
                )
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
            top.addView(android.widget.TextView(this).apply {
                text = name
                textSize = 15f
                setTextColor(if (isActive) Color.rgb(140, 225, 190) else Color.rgb(230, 230, 230))
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Status sits on the name line, opposite the name. It used to sit at
            // the bottom-right with Unpair immediately above it, and the two read
            // as one control rather than a state and an action.
            val statusText = when {
                isActive && playbackFixed -> "Active · locked"
                isActive -> "Active"
                isConnected -> "Connected · tap to switch"
                else -> "Not connected"
            }
            top.addView(android.widget.TextView(this).apply {
                text = statusText
                textSize = 11f
                setTextColor(
                    when {
                        isActive && playbackFixed -> Color.rgb(255, 205, 120)
                        isActive -> Color.rgb(140, 225, 190)
                        isConnected -> Color.rgb(150, 190, 230)
                        else -> Color.rgb(110, 110, 110)
                    },
                )
            })
            row.addView(top)

            val bottom = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            bottom.addView(android.widget.TextView(this).apply {
                text = multiMacs[i]
                textSize = 11f
                setTextColor(Color.rgb(120, 120, 120))
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Unpair moved down here so it no longer stacks against the status
            // label. It is a destructive action on a device that may be the one
            // currently playing, so it only appears on the inactive row.
            if (!isActive) {
                bottom.addView(android.widget.TextView(this).apply {
                    text = "Unpair"
                    textSize = 12f
                    setTextColor(Color.rgb(226, 128, 128))
                    setPadding(dp(16), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                        sendToService(ACTION_PAIRED_DEVICE_ACTION) {
                            putExtra("mac", multiMacs[i])
                            putExtra("action", SonyMdrV2.CONN_ACTION_UNPAIR)
                        }
                        binding.textMultiPointStatus.text = "Unpairing $name…"
                    }
                })
            } else {
                // The playback lock belongs to the device that is playing, so its
                // control sits on that device's row instead of in a large separate
                // button that could not say which device it would lock.
                bottom.addView(android.widget.TextView(this).apply {
                    text = if (playbackFixed) "🔒 Unlock" else "🔒 Lock here"
                    textSize = 12f
                    setTextColor(
                        if (playbackFixed) Color.rgb(255, 205, 120) else Color.rgb(150, 190, 230),
                    )
                    setPadding(dp(16), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                        val next = !playbackFixed
                        sendToService(ACTION_SET_FIX_PLAYBACK) { putExtra("fix", next) }
                        toast(
                            if (next) "Playback locked to $name"
                            else "Playback lock released",
                        )
                    }
                })
            }
            row.addView(bottom)

            if (!isActive && isConnected) {
                row.setOnClickListener {
                    if (!BluetoothAncService.isRunning) { toast("Start service first"); return@setOnClickListener }
                    sendToService(ACTION_SOURCE_SWITCH) { putExtra(EXTRA_TARGET_MAC, multiMacs[i]) }
                }
            }
            box.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Rows were stacked with no gap, so adjacent devices shared a
                // border and read as one block.
                if (visualIndex > 0) topMargin = dp(8)
            })
        }
        // No status line under the list. It spent its life as a switch message --
        // "Switching to …", then "Switched to …" -- which restated what the rows
        // already show, and whose "Switching to …" state had to be given a
        // deadline to stop outliving its own request. The rows carry the state.
        binding.textMultiPointStatus.text = ""
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
            val idx = if (sq) 0 else 1
            if (binding.spinnerConnectionMode.selectedItemPosition != idx) {
                suppressConnectionCallback = true
                binding.spinnerConnectionMode.setSelection(idx, false)
                binding.spinnerConnectionMode.post { suppressConnectionCallback = false }
            }
            val base = if (sq) {
                "Sound quality — the headphones may use LDAC or aptX when the phone offers them."
            } else {
                "Connection priority — favours stability and range over audio quality."
            }
            // Only shown when the headphones actually answer. The XM6 ignores the
            // LDAC status request outright, so a permanent "unknown" line would be
            // a readout that can never resolve.
            binding.textConnectionStatus.text = when (ldacActive) {
                true -> "$base\nLDAC: active"
                false -> "$base\nLDAC: not in use"
                null -> base
            }
        }
        // Quick Access. Two assignable function slots, QUICK_ACCESS1 and
        // QUICK_ACCESS2 — actions, not left/right earcups. The XM6 has one ANC
        // button, a power button and a touch panel; which physical control each
        // slot maps to is not established, so they are labelled neutrally rather
        // than claiming a mapping we cannot evidence.
        //
        // Hidden until the device answers: the model gate is negotiated at
        // runtime and could not be confirmed for the XM6 from the app.
        val fns = quickAccessFunctions
        if (fns == null) {
            binding.quickAccessGroup.visibility = android.view.View.GONE
        } else {
            binding.quickAccessGroup.visibility = android.view.View.VISIBLE
            bindQaSpinner(binding.spinnerQaLeft, fns.getOrNull(0) ?: SonyMdrV2.QUICK_ACCESS_NONE)
            bindQaSpinner(binding.spinnerQaRight, fns.getOrNull(1) ?: SonyMdrV2.QUICK_ACCESS_NONE)
        }

        // "invalid" was showing verbatim and meant nothing to read — it is a status
        // code, not a fault. The variant name is the part worth showing: it says
        // which upscaler the headset has actually selected.
        upscalingStatus?.let { st ->
            binding.textUpscaling.visibility = android.view.View.VISIBLE
            binding.textUpscaling.text = if (st == SonyMdrV2.UPSCALING_STATUS_OFF) {
                "DSEE is off"
            } else {
                "DSEE: " + SonyMdrV2.upscalingEffectName(upscalingEffect ?: 0xFF)
            }
        }

        // Wear glyph, bottom-right of the hero card. Green on head, yellow off.
        // Hidden until the first event, since this can only be learned from a push.
        when (headphonesWorn) {
            true -> {
                binding.imageWear.visibility = android.view.View.VISIBLE
                binding.imageWear.imageTintList = android.content.res.ColorStateList.valueOf(0xFF2ECC71.toInt())
            }
            false -> {
                binding.imageWear.visibility = android.view.View.VISIBLE
                binding.imageWear.imageTintList = android.content.res.ColorStateList.valueOf(0xFFF1C40F.toInt())
            }
            null -> binding.imageWear.visibility = android.view.View.GONE
        }

        // LE Audio: the control is gone until the payload can be built correctly.
        // The write needs four fields -- two EnableDisable flags, a
        // ConnModeSettingType and a QualityPriorValue -- and the two enum byte
        // codes are not recoverable, because jadx failed on QualityPriorValue
        // ("Init of enum field 'SOUND' uses external variables"). Sending the two
        // bytes we do know produces a frame the headset rejects, which is why
        // toggling it did nothing. The status query is still issued and the raw
        // reply logged, so the semantics can be pinned from a capture instead of
        // guessed at.
        updatingUi = false
        renderFeatureStatus()
    }

    /** Compact "what is switched on right now" line under the headphone card. */
    private fun renderFeatureStatus() {
        val on = buildList {
            // The codec is a state readout rather than an on/off toggle, but it
            // belongs with the other chips — it is the first thing worth knowing
            // about a link, and it also keeps this line visible on its own.
            activeCodec?.let { add(SonyMdrV2.codecName(it)) }
            if (dseeExtreme) add("DSEE")
            if (bgmMode) add("BGM")
            if (upmixCinema) add("Upmix")
            if (speakToChat) add("Speak-to-chat")
        }
        if (on.isEmpty()) {
            binding.textFeatureStatus.visibility = android.view.View.GONE
        } else {
            binding.textFeatureStatus.visibility = android.view.View.VISIBLE
            // No "On:" prefix — it described the toggles but read as nonsense once
            // the codec joined the line, since a codec is not switched on.
            binding.textFeatureStatus.text = on.joinToString(" · ")
        }
    }

    // ---- EQ ----

    private fun selectPreset(presetId: Int) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        selectedEQProfile = presetId
        eqPendingId = presetId
        eqPendingBands = null
        eqPendingAt = android.os.SystemClock.elapsedRealtime()
        sendToService(ACTION_SET_EQ) { putExtra(EXTRA_EQ_PRESET, presetId) }
        updateEQStatus()
    }

    private fun selectEditablePreset(profileId: Int) {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        selectedEQProfile = profileId
        eqPendingId = profileId
        eqPendingBands = null
        eqPendingAt = android.os.SystemClock.elapsedRealtime()
        sendToService(ACTION_SET_EQ) { putExtra(EXTRA_EQ_PRESET, profileId) }
        updateEQStatus()
    }

    private fun applyCustomEQ() {
        if (!BluetoothAncService.isRunning) { Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show(); return }
        eqPendingId = selectedEQProfile
        eqPendingBands = binding.eqGraph.bandValues.copyOf()
        eqPendingAt = android.os.SystemClock.elapsedRealtime()
        sendToService(ACTION_SET_EQ_CUSTOM) {
            putExtra(EXTRA_EQ_BANDS, binding.eqGraph.bandValues)
            putExtra(EXTRA_EQ_PRESET, selectedEQProfile)
        }
        updateEQStatus()
    }

    /**
     * Single renderer for the EQ status line, the curve and the preset-button
     * highlight. Everything is derived from the headset's actual reported
     * state, with a pending-write window so a label cannot be flipped back to
     * the old preset by an unrelated stats broadcast in mid-write.
     */
    private fun updateEQStatus() {
        val actual = eqActivePreset
        val bands = eqActiveBands

        // Resolve the pending write: the preset arrived, and for a custom
        // write the reported bands match what was sent — or the window gave
        // up and the actual state takes over again.
        val pending = eqPendingId
        val expired = pending != null &&
            android.os.SystemClock.elapsedRealtime() - eqPendingAt > eqPendingTimeoutMs
        val arrived = pending != null && pending == actual &&
            (eqPendingBands == null ||
                (bands != null && bands.size == 10 && bands.contentEquals(eqPendingBands)))
        if (expired || arrived) {
            eqPendingId = null
            eqPendingBands = null
        }

        // Editing is only possible while the headset has an editable slot
        // (Custom / User 1-5) selected. The curve still renders what the
        // headset reports — dimmed and non-interactive otherwise, so "what is
        // set" stays visible without inviting writes that cannot happen.
        val editable = EQPreset.isEditable(actual)
        binding.eqGraph.interactive = editable
        binding.btnApplyCustomEQ.visibility = if (editable) View.VISIBLE else View.GONE
        binding.btnApplyCustomEQ.text = "Write to ${eqLabel(actual)}"
        bands?.takeIf { it.size == 10 }?.let { src ->
            for (i in 0..9) eqBandValues[i] = src[i].coerceIn(-6, 6)
            binding.eqGraph.bandValues = eqBandValues
        }

        // The preset buttons highlight what the headset actually has — or,
        // inside the pending window, what it is about to have — not the last
        // button that happened to be tapped.
        val highlightId = eqPendingId ?: actual
        val buttons = mapOf(
            0x00 to binding.btnEQOff, 0x30 to binding.btnEQHeavy,
            0x31 to binding.btnEQClear, 0x32 to binding.btnEQHard,
            0x33 to binding.btnEQSoft, 0xA0 to binding.btnEQCustom,
            0xA1 to binding.btnEQUser1, 0xA2 to binding.btnEQUser2,
        )
        for ((id, button) in buttons) button.alpha = if (id == highlightId) 1f else 0.55f

        binding.textEQStatus.text = when {
            eqPendingId != null && eqPendingId != actual ->
                "↻ Applying ${eqLabel(eqPendingId!!)}…"
            eqPendingId != null ->
                "↻ Writing ${eqLabel(eqPendingId!!)}…"
            bands != null && bands.size == 10 ->
                "${eqLabel(actual)} [${bands.joinToString(" ") { "%+d".format(it) }}]"
            else -> eqLabel(actual)
        }
    }

    /** Label for a preset id without inventing a preset that is not one. */
    private fun eqLabel(id: Int): String =
        EQPreset.entries.firstOrNull { it.id == id }?.displayName
            ?: "Unknown (0x%02x)".format(id)

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
        updateProfileLabel()
        updateHeroTitle()
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

    /**
     * The hero title is the headphones' Bluetooth name — the user's own name
     * for them — with the model demoted to a small subtitle underneath: the
     * app speaks XM6 and nothing else, so the model alone says little.
     *
     * The name prefers the service's live view of the connection. The bonded
     * cache only refreshes when the headphones reconnect, so a rename that
     * happened while paired would otherwise keep showing the old model name.
     */
    private fun updateHeroTitle() {
        val name = serviceName?.takeIf { it.isNotBlank() }
            ?: pairedDevices.getOrNull(binding.spinnerDevice.selectedItemPosition)?.name?.takeIf { it.isNotBlank() }
            ?: pairedDevices.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: "WH-1000XM6"
        binding.textModel.text = name
        // Subtitle only when it adds information. The name usually mentions
        // the model already ("hbubli's WH-1000XM6"), so *containing* the model
        // string hides it — equality alone left the model printed twice. And
        // an empty TextView still occupies its line, hence the visibility
        // toggle rather than empty text.
        binding.textModelSub.text = "WH-1000XM6"
        binding.textModelSub.visibility =
            if (name.contains("WH-1000XM6", ignoreCase = true)) View.GONE else View.VISIBLE
    }

    /** The app speaks the XM6 protocol and nothing else. */
    private fun updateProfileLabel() {
        binding.textProfile.text = "Protocol: WH-1000XM6"
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
            // The alias — a local rename some Bluetooth settings offer — is what
            // the user actually sees in Settings; prefer it over the advertised
            // name.
            val display = HeadphoneProfile.bluetoothDisplayName(d) ?: d.name ?: ""
            // XM6 only. An XM5 shares the XM6 service UUID but speaks the wrong
            // protocol; listing it would start a service that half-works. The
            // service refuses an XM5 by name as a second gate.
            if ((display + " " + (d.name ?: "")).contains("WH-1000XM", ignoreCase = true) &&
                !display.contains("XM5", ignoreCase = true)
            ) pairedDevices.add(DeviceInfo(display, d.address))
        }
        if (pairedDevices.isEmpty()) {
            binding.textModel.text = "No headphones"; binding.textModelSub.text = ""
            updateCardStatus("Pair in Settings → Bluetooth"); binding.btnToggle.isEnabled = false
            updateModelArt(false)
        } else {
            updateCardStatus("${pairedDevices.size} device(s)"); binding.btnToggle.isEnabled = true
            updateHeroTitle()
            updateModelArt(true)
        }
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, pairedDevices)
        listAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = listAdapter
        loadDeviceSettings()
    }

    // ---- Model artwork ----

    /**
     * The hero artwork follows the *model*, not the Bluetooth name — the name
     * is the user's own and rarely contains the model string. The app speaks
     * XM6 and nothing else, so any paired device gets the XM6 artwork; the
     * generic icon only appears when there is nothing paired. Product shots
     * live in drawable-nodpi so they are not rescaled per screen density.
     */
    private fun updateModelArt(hasDevice: Boolean) {
        binding.imageModel.setImageResource(
            if (hasDevice) R.drawable.model_xm6 else R.drawable.ic_headphones_big
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
        }.also { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it) }
        updateServiceRunning(true); updateCardStatus("Starting…")
    }

    private fun stopService() {
        sendToService(ACTION_STOP) {}
        // Clear the readouts along with the service — they described a link
        // that no longer exists, and leftover values read as if data were
        // still current.
        battery = null
        currentMode = "—"
        updateServiceRunning(false)
        updateCardStats()
        updateCardStatus("⏹ Stopped")
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
        if (!running) binding.btnRetryNow.visibility = View.GONE
    }

    /**
     * "Retry now" only makes sense while the service is alive but the link is
     * not — in WAITING, or mid-burst in CONNECTING. Hidden while connected and
     * whenever the service reports it is not running.
     */
    private fun updateRetryVisibility(status: BluetoothAncService.Status) {
        binding.btnRetryNow.visibility =
            if (serviceRunning && status != BluetoothAncService.Status.CONNECTED) View.VISIBLE else View.GONE
    }

    private fun updateStatusDisplay(status: BluetoothAncService.Status, msg: String? = null) {
        // A single geometric dot carrying the state in its colour, rather than a
        // per-status emoji. Same affordance, and it tints with the palette.
        val tint = when (status) {
            BluetoothAncService.Status.DISCONNECTED -> Color.rgb(138, 138, 138)
            BluetoothAncService.Status.CONNECTING -> Color.rgb(232, 196, 120)
            BluetoothAncService.Status.CONNECTED -> Color.rgb(127, 212, 168)
            // Standing by: service alive, headphones away. Blue reads as
            // "waiting" and stays clearly apart from the connecting amber and
            // the error red.
            BluetoothAncService.Status.WAITING -> Color.rgb(120, 170, 220)
            BluetoothAncService.Status.ERROR -> Color.rgb(226, 128, 128)
        }
        val label = status.name.lowercase().replaceFirstChar { it.uppercase() }
        // The service's messages are written to be self-sufficient ("Connected",
        // "Waiting for headphones", "Reconnecting in 3s (attempt 2 of 5)"), so the
        // message alone is shown whenever one exists. The old prefix logic tried
        // to stitch "LABEL — message" together and kept producing duplicates or
        // stitched lines like "Waiting — Headphones off — waiting"; dropping the
        // label when a message is present avoids both.
        val text = msg ?: label
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