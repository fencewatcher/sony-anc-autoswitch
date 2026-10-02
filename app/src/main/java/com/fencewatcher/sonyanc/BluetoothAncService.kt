package com.fencewatcher.sonyanc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/**
 * Foreground service that connects to Sony WH-1000XM6 headphones
 * over Bluetooth RFCOMM and sends ANC commands based on media playback state.
 *
 * Protocol: MDR framing on RFCOMM, UUID 956c7b26-…
 * Incoming frames are ACKed (stop-and-wait) and parsed for battery/mode.
 */
class BluetoothAncService : Service() {

    private val tag = "BTAncSvc"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var refreshJob: Job? = null
    private var btSocket: BluetoothSocket? = null
    private var mediaMonitor: MediaPlaybackMonitor? = null
    /**
     * One outgoing sequence counter, shared across both tables.
     *
     * It was briefly split into one counter per table on the reasoning that a T1
     * frame could take the number a T2 frame had just used. The app's own sender
     * (ie0/c.java) keeps a single byte and passes it for every data type inside
     * one synchronized method, so the shared behaviour is the correct one -- the
     * observed alternation across tables is simply what it produces.
     */
    private var currentSeq = 0

    private class PendingRequest(
        val replyCommand: Int,
        val reply: CompletableDeferred<ByteArray>,
    )

    private val pendingLock = Any()
    private var pending: PendingRequest? = null

    /**
     * Send one query and wait for its reply.
     *
     * The link is stop and wait, which is why a long burst sent without waiting
     * lost everything past the first few replies. Every MDR command pair follows
     * reply = request + 1, so a reply can be matched to its request on the
     * command byte alone — no guessing at payload shapes, and an unsolicited
     * notification can never be mistaken for the answer.
     *
     * Returns the reply payload, or null if it never arrived. Times out rather
     * than blocking forever, and retries once, because the device retransmits
     * its own frames when our acknowledgement does not land.
     */
    private suspend fun request(
        payload: ByteArray,
        table: SonyMdrV2.Table = SonyMdrV2.Table.T1,
        attempts: Int = 2,
        timeoutMs: Long = 800L,
    ): ByteArray? {
        val replyCommand = ((payload[0].toInt() and 0xFF) + 1) and 0xFF
        for (attempt in 1..attempts) {
            val deferred = CompletableDeferred<ByteArray>()
            synchronized(pendingLock) { pending = PendingRequest(replyCommand, deferred) }
            sendFrame(payload, table)
            try {
                return withTimeout(timeoutMs) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                Log.d(tag, "No reply to ${SonyMdrV2.hex(payload)} (try $attempt of $attempts)")
                synchronized(pendingLock) { pending = null }
            }
        }
        return null
    }
    private var isMediaPlaying = false

    /** User-configurable behaviour rules; defaults reproduce the original hard-coded policy. */
    @Volatile
    private var automationRules: List<Automation.Rule> = emptyList()

    /**
     * Set when the user asks the app to power the headphones down. The device then
     * drops Bluetooth, which would otherwise look like a fault and send the
     * reconnect loop hammering an intentionally-off device 20 times.
     */
    @Volatile
    private var powerOffRequested = false
    private var deviceAddress: String? = null

    /**
     * Consecutive connect failures, deliberately a field rather than a local.
     *
     * It used to be local to connectBluetooth, so every triggerReconnect reset it
     * to zero and the backoff never accumulated across cycles. With the headphones
     * powered off the socket would open, hit EOF straight away and reconnect with
     * no delay at all, forever. It now only resets on a successful connect.
     */
    private var connectFailures = 0

    /**
     * True once a link has been up, cleared when the retry loop gives up.
     *
     * This separates "the link dropped" from "the headphones are gone". Both look
     * identical from the socket -- a write to a half-open connection succeeds, so
     * the only evidence is silence -- but they deserve opposite pacing. Treating
     * a dropped link like an absent device meant backing off 3s, 6s, 12s from a
     * pair that was sitting there answering the first retry, turning a sub-second
     * outage into a six-second one.
     */
    private var everConnected = false
    private var lastConnectAttemptAt = 0L

    /**
     * When the ACL receiver last triggered a reconnect.
     *
     * ACTION_ACL_CONNECTED is not one-per-device-connection. A link that is
     * flapping, or a phone reconnecting several profiles, delivers it more than
     * once -- and the receiver used to reset the retry counter on every one, so
     * each broadcast handed the app a brand new retry budget. That is how an
     * off pair could be retried indefinitely instead of settling.
     */
    private var lastAclReconnectAt = 0L

    /**
     * The once-a-minute poll that runs after the fast retry burst is spent.
     *
     * Exists to prevent a stranded app. ACTION_ACL_CONNECTED fires when the
     * link comes up, which may be *before* the app starts retrying -- so an
     * app that gives up while the headphones are already on and ACL-up would
     * sit there waiting for a broadcast that has already been and gone.
     */
    private var slowPollJob: Job? = null

    /**
     * True while a connect attempt is actually running.
     *
     * This exists because of a collision that made first attempts unreliable.
     * A connect begins by waiting for the Bluetooth profile to report the
     * device up -- and the broadcast that announces exactly that arrives during
     * the wait. The receiver treated it as "headphones are back, reconnect",
     * scheduled a second attempt, and three seconds later that attempt
     * cancelled the one still handshaking. The device was there the whole time;
     * the app cancelled itself.
     *
     * A live attempt already knows how to find the headphones, so a broadcast
     * arriving during one is news the attempt does not need.
     */
    @Volatile
    private var connectInProgress = false

    /**
     * Reconnects when the headphones come back at the Bluetooth profile level,
     * so giving up does not mean staying dead until the user presses Start.
     */
    private val aclReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (dev.address != deviceAddress) return
            // An attempt is already running and will find the headphones on its
            // own. Acting on the broadcast would cancel it mid-handshake, which
            // is precisely the failure this guard exists to stop.
            if (connectInProgress) {
                Log.d(tag, "ACL broadcast during a live connect attempt — ignoring")
                return
            }
            // The link can already be up. Reconnecting now would close a
            // working socket because the profile happened to re-announce.
            if (btSocket != null && status == Status.CONNECTED) {
                Log.d(tag, "ACL broadcast for an already-connected link — ignoring")
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAclReconnectAt < ACL_RECONNECT_COOLDOWN_MS) {
                Log.d(tag, "ACL broadcast within cooldown — ignoring")
                return
            }
            lastAclReconnectAt = now
            connectFailures = 0
            Log.d(tag, "Headphones back at the Bluetooth profile — retrying now")
            triggerReconnect()
        }
    }
    private var batteryPercent: Int? = null

    /** Latch for the battery-low trigger, so it fires once per crossing. */
    private var batteryLowFired = false
    private var currentModeName: String = "—"
    @Volatile
    private var autoPaused = false
    private var profile: HeadphoneProfile = HeadphoneProfile.Xm6

    /** "auto" | "xm5" | "xm6" — user-pinned protocol, see EXTRA_PROFILE_OVERRIDE. */
    @Volatile
    private var profileOverride: String = "auto"

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        automationRules = Automation.load(this)
        createNotificationChannel()
        // Resume automatically when the headphones come back, so that giving up
        // on the retry cycle does not mean staying disconnected until Start is
        // pressed again.
        runCatching {
            registerReceiver(
                aclReceiver,
                android.content.IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
            )
        }
        Log.d(tag, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> {
                    val address = intent.getStringExtra(EXTRA_ADDRESS)
                        ?: run {
                            Log.w(tag, "START without device address, ignoring")
                            broadcastStatus(Status.ERROR, "No device address")
                            stopSelf()
                            return START_NOT_STICKY
                        }
                    deviceAddress = address
                    profileOverride = intent.getStringExtra(EXTRA_PROFILE_OVERRIDE) ?: "auto"
                    isRunning = true
                    powerOffRequested = false   // fresh start, not a leftover power-off
                    Log.d(tag, "Starting service, device=$address")
                    safeStartForeground()
                    serviceInit(address)
                }

                ACTION_STOP -> {
                    Log.d(tag, "Stopping service")
                    isRunning = false
                    status = Status.DISCONNECTED
                    broadcastStatus(status, "Stopped")
                    try { btSocket?.close() } catch (_: Exception) {}
                    stopSelf()
                }

                ACTION_GET_STATUS -> {
                    broadcastStatus(status)
                    scope.launch { sendFrame(byteArrayOf(0x56, 0x00)) }
                }

                ACTION_ANC_ON -> {
                    Log.d(tag, "Debug: manual ANC ON")
                    scope.launch { sendAncCommandOnce(profile.ancOn(ambientLevel())) }
                }

                ACTION_AMBIENT -> {
                    Log.d(tag, "Debug: manual AMBIENT")
                    scope.launch { sendAncCommandOnce(sonyAmbientCommand()) }
                }

                // Re-send the ambient payload after the user moves a control, so a
                // change takes effect on the headphones now rather than on the next
                // connection. Same frame as ACTION_AMBIENT — the level, voice
                // passthrough and auto-ambient flags are all carried in it — so
                // applying them necessarily engages ambient mode.
                ACTION_APPLY_AMBIENT -> {
                    Log.d(tag, "Applying ambient settings now")
                    scope.launch { sendAncCommandOnce(sonyAmbientCommand()) }
                }

                ACTION_ANC_OFF -> {
                    Log.d(tag, "Debug: manual ANC OFF")
                    scope.launch { sendAncCommandOnce(profile.ancOff(ambientLevel())) }
                }

                ACTION_TOGGLE_AUTO -> {
                    autoPaused = !autoPaused
                    Log.d(tag, "Auto-pause: ${if (autoPaused) "PAUSED" else "RUNNING"}")
                    refreshNotification()
                    // The UI could never show this state before: autoPaused was not in
                    // broadcastStats(), so the notification was the only place it existed.
                    broadcastStats()
                }

                ACTION_SET_EQ -> {
                    val presetId = intent.getIntExtra(EXTRA_EQ_PRESET, 0)
                    Log.d(tag, "Setting EQ preset to ${String.format("0x%02x", presetId)}")
                    scope.launch { setEQPreset(presetId) }
                }

                ACTION_SET_EQ_CUSTOM -> {
                    val bands = intent.getIntArrayExtra(EXTRA_EQ_BANDS)
                    val presetId = intent.getIntExtra(EXTRA_EQ_PRESET, 0xA0)
                    if (bands != null) {
                        Log.d(tag, "Setting custom EQ for 0x%02x (%d bands)".format(presetId, bands.size))
                        scope.launch { setCustomEQ(bands, presetId) }
                    }
                }

                ACTION_POWER_OFF -> {
                    Log.d(tag, "Power-off command")
                    powerOffRequested = true
                    scope.launch {
                        sendFrame(SonyMdrV2.buildPowerOff())
                    }
                }

                ACTION_REFRESH_DEVICES -> {
                    Log.d(tag, "Refreshing multipoint device list (T2)")
                    scope.launch { refreshMultipoint() }
                }

                ACTION_SOURCE_SWITCH -> {
                    val mac = intent.getStringExtra(EXTRA_TARGET_MAC)
                    val payload = mac?.let { SonyMdrV2.buildSourceSwitchSet(it) }
                    if (payload == null) {
                        Log.e(tag, "Bad source-switch MAC: $mac")
                    } else {
                        Log.d(tag, "Source switch → $mac (T2)")
                        scope.launch { sendFrame(payload, SonyMdrV2.Table.T2) }
                    }
                }

                ACTION_SET_SPEAK_TO_CHAT -> {
                    val on = intent.getBooleanExtra("on", true)
                    scope.launch { sendFrame(SonyMdrV2.buildSpeakToChatSet(on)) }
                }

                ACTION_SET_PAUSE_TAKEN_OFF -> {
                    val on = intent.getBooleanExtra("on", true)
                    scope.launch { sendFrame(SonyMdrV2.buildPauseWhenTakenOffSet(on)) }
                }

                ACTION_SET_DSEE -> {
                    val on = intent.getBooleanExtra("on", true)
                    scope.launch { sendFrame(SonyMdrV2.buildUpscalingSet(on)) }
                }

                ACTION_SET_BGM -> {
                    val on = intent.getBooleanExtra("on", true)
                    scope.launch { sendFrame(SonyMdrV2.buildBgmSet(on)) }
                }

                ACTION_SET_UPMIX -> {
                    val on = intent.getBooleanExtra("on", true)
                    scope.launch { sendFrame(SonyMdrV2.buildUpmixSet(on)) }
                }

                ACTION_CLEAR_FRAME_LOG -> {
                    frameLog.clear()
                    pushFrameLog()
                }

                ACTION_GET_FRAME_LOG -> pushFrameLog()

                ACTION_SYNC_STATE -> {
                    scope.launch {
                        runSweep()
                        broadcastStats()
                    }
                }

                ACTION_SEND_RAW -> {
                    val hex = intent.getStringExtra("hex")
                    val bytes = hex?.let { parseHex(it) }
                    if (bytes == null) {
                        Log.w(tag, "Rejected raw payload: $hex")
                    } else {
                        val table = if (intent.getBooleanExtra("t2", false))
                            SonyMdrV2.Table.T2 else SonyMdrV2.Table.T1
                        scope.launch { sendFrame(bytes, table) }
                    }
                }

                ACTION_SET_QUICK_ACCESS -> {
                    val fns = intent.getIntArrayExtra("functions")
                    if (fns == null || fns.isEmpty()) {
                        Log.w(tag, "Quick Access write with no functions")
                    } else {
                        scope.launch {
                            sendFrame(SonyMdrV2.buildQuickAccessFunctionSet(fns))
                            delay(200L)
                            // Read back rather than trusting what we sent.
                            sendFrame(SonyMdrV2.buildQuickAccessFunctionGet())
                        }
                    }
                }

                ACTION_SET_LE_AUDIO -> {
                    val on = intent.getBooleanExtra("le_audio", false)
                    scope.launch {
                        // Re-read after the switch: the transport change drops the
                        // link, so the reply may not arrive until it is back.
                        sendFrame(SonyMdrV2.buildLeAudioSet(on, !on))
                        delay(2500L)
                        sendFrame(SonyMdrV2.buildLeAudioStatusGet())
                    }
                }

                ACTION_SET_CONNECTION_MODE -> {
                    val soundQuality = intent.getBooleanExtra("sound_quality", true)
                    scope.launch {
                        sendFrame(SonyMdrV2.buildConnectionModeSet(isXm5(), soundQuality))
                        delay(150L)
                        sendFrame(SonyMdrV2.buildConnectionModeGet(isXm5()))
                    }
                }

                ACTION_SET_AUTO_POWER -> {
                    val mode = intent.getIntExtra("mode", 0x11)
                    scope.launch { sendFrame(SonyMdrV2.buildAutoPowerOffSet(mode)) }
                }

                ACTION_SET_VOICE_GUIDANCE -> {
                    val v = intent.getIntExtra("value", 5)
                    scope.launch {
                        sendFrame(SonyMdrV2.buildVoiceGuidanceVolumeSet(v), SonyMdrV2.Table.T2)
                        delay(120L)
                        sendFrame(SonyMdrV2.buildVoiceGuidanceVolumeGet(), SonyMdrV2.Table.T2)
                    }
                }

                ACTION_ENTER_PAIRING_MODE -> {
                    val enter = intent.getBooleanExtra("enter", true)
                    Log.d(tag, "Entering pairing mode = $enter")
                    scope.launch {
                        sendFrame(SonyMdrV2.buildPairingModeSet(enter), SonyMdrV2.Table.T2)
                        delay(200L)
                        sendFrame(SonyMdrV2.buildPairingModeGet(), SonyMdrV2.Table.T2)
                    }
                }

                ACTION_PAIRED_DEVICE_ACTION -> {
                    val mac = intent.getStringExtra("mac").orEmpty()
                    val action = intent.getIntExtra("action", SonyMdrV2.CONN_ACTION_DISCONNECT)
                    val frame = SonyMdrV2.buildPairedDeviceActionSet(action, mac)
                    if (frame == null) {
                        Log.w(tag, "Refusing paired-device action: bad MAC '$mac'")
                    } else {
                        val what = when (action) {
                            SonyMdrV2.CONN_ACTION_UNPAIR -> "unpair"
                            SonyMdrV2.CONN_ACTION_CONNECT -> "connect"
                            else -> "disconnect"
                        }
                        Log.d(tag, "Paired-device $what $mac")
                        scope.launch {
                            sendFrame(frame, SonyMdrV2.Table.T2)
                            delay(400L)
                            // The device list is stale after a change; re-read it.
                            refreshMultipoint()
                        }
                    }
                }

                ACTION_SET_FIX_PLAYBACK -> {
                    val fix = intent.getBooleanExtra("fix", false)
                    Log.d(tag, "Fix playback = $fix")
                    scope.launch {
                        sendFrame(SonyMdrV2.buildSourceSwitchControlSet(fix), SonyMdrV2.Table.T2)
                        delay(150L)
                        sendFrame(SonyMdrV2.buildSourceSwitchControlGet(), SonyMdrV2.Table.T2)
        delay(120)
        sendFrame(SonyMdrV2.buildPairingModeGet(), SonyMdrV2.Table.T2)
                    }
                }


                ACTION_RELOAD_AUTOMATION -> reloadAutomation()
            }
        } catch (e: Exception) {
            Log.e(tag, "Unhandled in onStartCommand", e)
            broadcastStatus(Status.ERROR)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Wraps [startForeground] in a try-catch to survive denied notification
     * permission (Android 13+) or other transient failures.
     */
    private fun safeStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        } catch (e: SecurityException) {
            Log.e(tag, "startForeground denied — missing POST_NOTIFICATIONS?", e)
            // Fall back to sticky background service (no visible notification)
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed", e)
        }
    }

    override fun onDestroy() {
        Log.d(tag, "Destroying service")
        isRunning = false
        mediaMonitor?.stop()
        mediaMonitor = null
        refreshJob?.cancel()
        // Close socket first to unblock the read() loop in the coroutine
        try {
            btSocket?.close()
        } catch (_: Exception) {}
        connectionJob?.cancel()
        runCatching { unregisterReceiver(aclReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Settings ----

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun ambientLevel(): Int {
        val addr = deviceAddress ?: return 20
        return prefs().getInt("ambient_level_$addr", 20).coerceIn(1, 20)
    }

    private fun voicePassthrough(): Boolean {
        val addr = deviceAddress ?: return false
        return prefs().getBoolean("voice_passthrough_$addr", false)
    }

    private fun autoAmbient(): Boolean {
        val addr = deviceAddress ?: return false
        return prefs().getBoolean("auto_ambient_$addr", false)
    }

    // App filtering removed — see MediaPlaybackMonitor.decide.

    private fun sonyAmbientCommand(): ByteArray {
        return profile.ambient(
            level = ambientLevel(),
            voice = voicePassthrough(),
            noiseAdaptive = autoAmbient(),
        )
    }

    // ---- Initialisation ----

    private fun serviceInit(address: String) {
        status = Status.CONNECTING
        broadcastStatus(status)
        updateNotification("Connecting…")

                // Start media playback monitoring immediately.
        mediaMonitor = MediaPlaybackMonitor(this) { playing ->
            onMediaStateChanged(playing)
        }
        mediaMonitor?.start()

        connectBluetooth(address)
    }

    // ---- Bluetooth connection (runs on IO dispatcher) ----

    private fun connectBluetooth(address: String) {
        lastConnectAttemptAt = android.os.SystemClock.elapsedRealtime()
        connectionJob?.cancel()
        connectionJob = scope.launch {

            while (isActive && connectFailures < MAX_RETRIES) {
                connectInProgress = true
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null) {
                        Log.e(tag, "No Bluetooth adapter")
                        status = Status.ERROR
                        broadcastStatus(status)
                        updateNotification("No Bluetooth")
                        return@launch
                    }
                    if (!adapter.isEnabled) {
                        Log.w(tag, "Bluetooth is disabled")
                        status = Status.ERROR
                        broadcastStatus(status)
                        updateNotification("Bluetooth off")
                        return@launch
                    }

                    val device: BluetoothDevice = adapter.getRemoteDevice(address)

                    // Detect headphone model from device name, unless the user pinned
                    // one. A silent fallback to XM6 is the wrong default to be stuck
                    // with on hardware you cannot test against, so the override is
                    // threaded through to here rather than living in the UI only.
                    profile = when (profileOverride) {
                        "xm5" -> HeadphoneProfile.Xm5
                        "xm6" -> HeadphoneProfile.Xm6
                        else -> HeadphoneProfile.detect(device.name ?: "")
                    }
                    Log.d(
                        tag,
                        "Profile: ${profile.modelName} (${profile.serviceUuid.take(8)}…)" +
                            if (profileOverride != "auto") " [pinned: $profileOverride]" else "",
                    )
                    try {
                        btSocket?.close()
                    } catch (_: Exception) {}
                    btSocket = null

                    Log.d(tag, "Connecting to ${device.name ?: "?"} ($address) via RFCOMM…")
                    status = Status.CONNECTING
                    broadcastStatus(status, "Connecting…")
                    updateNotification("Connecting…")

                    // Cancel discovery — required before RFCOMM on Android
                    try {
                        if (adapter.isDiscovering) {
                            adapter.cancelDiscovery()
                            Log.d(tag, "Cancelled BT discovery before socket creation")
                        }
                    } catch (_: Exception) {}

                    // Try multiple connection methods — Sony RFCOMM is finicky
                    if (!awaitProfileReady(device)) {
                        Log.d(tag, "Profile not ready after wait — trying anyway")
                    }
                    val socket = connectSonyRfcomm(device)
                    // Publish only once the channel is actually up. Assigning
                    // first left a window where a concurrent sender (the 60s
                    // poll, an ACTION_ intent, a media-state change) could pick
                    // up a socket that was created but not yet connected, fail
                    // its write, and take the whole in-flight connect down with
                    // it -- which is what a lot of "first try" failures were.
                    socket.connect()
                    btSocket = socket

                    Log.d(tag, "Connected!")
                    slowPollJob?.cancel()
                    slowPollJob = null
                    status = Status.CONNECTED
                    broadcastStatus(status, "Connected")
                    updateNotification("Connected")
                    currentSeq = 0       // Reset seq on every fresh connection
                    connectFailures = 0   // Reset the failure count on success
                    everConnected = true

                    // The read loop has to be running before the handshake: request()
                    // waits for a reply, and with nothing draining the socket every
                    // query would simply time out. Runs for the life of the link on
                    // Dispatchers.IO, alongside the connect coroutine below.
                    val reader = launch {
                        // Blocking read loop — any data or -1 / IOException = disconnected
                        val inputStream = socket.inputStream
                        val buffer = ByteArray(1024)
                        val frameDecoder = MdrFrameDecoder()
                        try {
                            while (isActive) {
                                val n = inputStream.read(buffer)
                                if (n == -1) break // EOF = orderly disconnect
                                if (n > 0) {
                                    val frames = frameDecoder.feed(buffer.copyOfRange(0, n))
                                    for (frame in frames) {
                                        // Stop-and-wait: every non-ACK frame must be ACKed
                                        // with flipped sequence bit, or the XM6 closes the channel.
                                        if (frame.dataType != 0x01) {
                                            val ackSeq = (frame.sequence xor 1) and 0xFF
                                            sendAck(socket, ackSeq)
                                            Log.d(tag, "← [${if (SonyMdrV2.Table.of(frame.dataType) == SonyMdrV2.Table.T2) "T2" else "T1"}] ${SonyMdrV2.hex(frame.payload)}")
                                            recordFrame("RX", if (SonyMdrV2.Table.of(frame.dataType) == SonyMdrV2.Table.T2) "T2" else "T1", frame.payload)
                                            // Resolve an outstanding request, matched on the command
                                            // byte only. An unsolicited notification carries a notify
                                            // command, never request+1, so it cannot be mistaken for
                                            // the answer we are waiting on.
                                            synchronized(pendingLock) {
                                                val p = pending
                                                if (p != null && frame.payload.isNotEmpty() &&
                                                    p.replyCommand == (frame.payload[0].toInt() and 0xFF)
                                                ) {
                                                    pending = null
                                                    p.reply.complete(frame.payload)
                                                }
                                            }
                                            parseIncoming(frame)
                                        }
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            Log.w(tag, "BT error: IOException: ${e.message}")
                        }
                    }


                    // XM6 protocol handshake — required before the headphones accept 0x19 commands
                    Log.d(tag, "Handshake: protocol info")
                    request(byteArrayOf(0x00, 0x00))
                    Log.d(tag, "Handshake: support functions")
                    request(byteArrayOf(0x06, 0x00))
                    Log.d(tag, "Handshake: battery")
                    request(byteArrayOf(0x22, 0x00))
                    Log.d(tag, "Handshake: XM6 ANC inquiry (${profile.ancInquiry.joinToString(" ") { "%02x".format(it) }})")
                    request(profile.ancInquiry)
                    Log.d(tag, "Handshake: EQ")
                    request(byteArrayOf(0x56, 0x00))

                    // Probe every feature we can display, on both command tables.
                    // The peripheral/multipoint family only answers on table 2.
                    Log.d(tag, "Querying full device state (T1 + T2)")
                    runSweep()
                    delay(200L)

                    Log.d(tag, "Handshake complete — re-applying automation rules")
                    runAutomation(currentPlaybackTrigger())
                    runAutomation(Automation.Trigger.DEVICE_CONNECTED)

                    // Periodic battery refresh (every 60s) + ANC state re-sync
                    refreshJob?.cancel()
                    refreshJob = launch {
                        while (isActive && btSocket != null) {
                            delay(60_000L)
                            if (!isActive || btSocket == null) break
                            // Same exclusion as the full sweep: a poll landing mid-sweep
                            // interleaves frames and desyncs the link.
                            if (!sweepLock.tryLock()) continue
                            try {
                                sendFrame(byteArrayOf(0x22, 0x00))  // battery inquiry
                                sendFrame(SonyMdrV2.buildAudioCodecGet())
                                sendFrame(SonyMdrV2.buildWearingStatusGet(), SonyMdrV2.Table.T2)
                                sendFrame(SonyMdrV2.buildLeAudioStatusGet())
                                sendFrame(SonyMdrV2.buildQuickAccessEnableGet())
                                sendFrame(SonyMdrV2.buildQuickAccessFunctionGet())
                                sendFrame(SonyMdrV2.buildUpscalingStatusGet())
                                delay(500L)
                            } finally {
                                sweepLock.unlock()
                            }
                            runAutomation(currentPlaybackTrigger())
                        }
                    }

                    // Held open below so the socket keeps being drained.
                    reader.join()

                    // Socket closed — reconnect automatically
                    btSocket = null
                    status = Status.DISCONNECTED

                    if (powerOffRequested) {
                        // The user turned the headphones off on purpose. Do not
                        // treat the resulting disconnect as a fault.
                        Log.d(tag, "Socket closed after deliberate power-off — not reconnecting")
                        powerOffRequested = false
                        broadcastStatus(status, "Powered off")
                        updateNotification("Powered off")
                        refreshNotification()
                        return@launch
                    }

                    Log.d(tag, "Socket closed — reconnecting")
                    broadcastStatus(status, "Disconnected")
                    updateNotification("Disconnected, reconnecting…")
                    runAutomation(Automation.Trigger.DEVICE_DISCONNECTED)
                    triggerReconnect()
                    return@launch

                } catch (e: CancellationException) {
                    throw e  // Propagate cancellation
                } catch (e: Exception) {
                    Log.w(tag, "BT error: ${e.javaClass.simpleName}: ${e.message}")
                    status = Status.DISCONNECTED
                    broadcastStatus(status, "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")

                    if (!isActive) break

                    // Cleared before the backoff, not after: the wait is exactly
                    // when the headphones are most likely to come back, and an
                    // ACL broadcast during it has to be able to shorten it.
                    connectInProgress = false
                    connectFailures++
                    // A pair that was reachable moments ago is present, not absent.
                    // Retry promptly for the first few attempts, then fall back to
                    // the normal backoff so a genuinely powered-off pair is not
                    // hammered -- the loop gives up on its own after this.
                    val delay = if (everConnected && connectFailures <= 3) 800L
                    else RETRY_DELAY_MS * (1L shl (connectFailures - 1).coerceAtMost(4))
                    updateNotification("Reconnecting in ${delay / 1000}s…")
                    delay(delay)
                } finally {
                    // Every other exit -- a clean give-up, an early return, a
                    // cancellation -- lands here, so the flag cannot be left
                    // stuck true and deafen the ACL receiver for good.
                    connectInProgress = false
                }
            }

            if (!isActive) {
                status = Status.DISCONNECTED
                broadcastStatus(status)
                updateNotification("Stopped")
            } else {
                status = Status.DISCONNECTED
                // Not a fault: most often the headphones are simply powered off.
                // Waiting for the Bluetooth profile to report them back beats
                // hammering the stack every few seconds.
                // The retry budget is spent, so the next round starts from the
                // absent-device pacing rather than the dropped-link pacing.
                everConnected = false
                broadcastStatus(status, "Headphones off — waiting")
                updateNotification("Headphones off — waiting")

                // Keep a slow heartbeat rather than stopping dead. One attempt a
                // minute is not "constantly reconnecting", and it covers the
                // case the ACL receiver cannot: the headphones already being
                // on and ACL-up, so no further broadcast is coming. Without
                // this the app needs a manual Start to recover.
                slowPollJob?.cancel()
                slowPollJob = scope.launch {
                    while (isActive && btSocket == null) {
                        delay(ABSENT_RETRY_MS)
                        if (!isActive || btSocket != null) break
                        // A burst may already be running from an earlier round.
                        // connectBluetooth cancels the live job before starting
                        // a new one, so reconnecting here would truncate that
                        // burst and restart its backoff from zero -- turning a
                        // slow recovery into exactly the loop this is meant to
                        // prevent. Skip the round and look again next minute.
                        if (connectionJob?.isActive == true) continue
                        Log.d(tag, "Still nothing after the burst — slow retry")
                        connectFailures = 0
                        connectBluetooth(address)
                    }
                }
            }
        }
    }

    // ---- Incoming frame parsing (battery + ANC state) ----

    private fun parseIncoming(frame: MdrFrame) {
        val p = frame.payload
        if (p.size < 2) return
        val cmd = p[0].toInt() and 0xFF
        when (cmd) {
            0x23, 0x25 -> {  // battery RET / NTFY (single battery)
                if (p.size >= 3) {
                    batteryPercent = p[2].toInt() and 0xFF
                    Log.d(tag, "Battery: $batteryPercent%")
                    refreshNotification()
                    // Fire only on the crossing, not on every 60s poll — otherwise a
                    // rule bound to "battery low" would re-run every minute.
                    val low = batteryPercent!! < BATTERY_LOW_THRESHOLD
                    if (low && !batteryLowFired) {
                        batteryLowFired = true
                        scope.launch { runAutomation(Automation.Trigger.BATTERY_LOW) }
                    } else if (!low) {
                        batteryLowFired = false
                    }
                }
            }
            0x67, 0x69 -> {  // NCASM RET / NTFY — mode may have changed on-device
                if (p.size >= 4) {
                    val idx = if (p.size >= 9) 3 else 2
                    val totalEffect = p[idx].toInt() and 0xFF
                    val mode = p[idx + 1].toInt() and 0xFF
                    val level = if (p.size >= idx + 4) p[idx + 3].toInt() and 0xFF else 0
                    currentModeName = when {
                        totalEffect == 0x00 -> "Off"
                        mode == 0x01 -> "Ambient $level"
                        else -> "NC"
                    }
                    Log.d(tag, "Headphones report mode: $currentModeName")
                    refreshNotification()
                    // Without this the UI never learns the mode on connect: the service
                    // learns it, updates the notification, and goes quiet. The Home chip
                    // only refreshed when some unrelated broadcast happened to carry
                    // EXTRA_MODE, which made the stale mode look intermittent.
                    broadcastStats()
                }
            }

            0x57, 0x59 -> {  // EQ RET / NTFY: pid, subtype, presetID, count, values…
                val presetId = if (p.size >= 3) p[2].toInt() and 0xFF else 0
                val bandCount = if (p.size >= 4) p[3].toInt() and 0xFF else 0
                eqActivePreset = presetId
                if (bandCount > 0 && p.size >= 4 + bandCount) {
                    val bands = p.copyOfRange(4, 4 + bandCount)
                        .map { (it.toInt() and 0xFF) - 6 }.toIntArray()
                    eqActiveBands = bands
                    Log.d(tag, "EQ state: preset=0x%02x, %d bands".format(presetId, bandCount))
                } else {
                    eqActiveBands = null
                    Log.d(tag, "EQ state: preset=0x%02x, no bands".format(presetId))
                }
                refreshNotification()
            }

            // ---- Table 2 (peripheral / multipoint) ----
            SonyMdrV2.CMD_CONNECT_RET_SUPPORT_FUNCTION -> {
                val funcs = SonyMdrV2.parseSupportFunctions(p)
                sourceSwitchControlSupported = SonyMdrV2.FUNC_SOURCE_SWITCH_CONTROL in funcs
                Log.d(tag, "T2 support functions: $funcs — sourceSwitchControl=$sourceSwitchControlSupported")
                broadcastStats()
            }

            SonyMdrV2.CMD_PERI_RET_CAPABILITY -> {
                peripheralSupported = true
                Log.d(tag, "Peripheral family present (multipoint available)")
                broadcastStats()
            }

            SonyMdrV2.CMD_PERI_NTFY_STATUS, SonyMdrV2.CMD_PERI_RET_STATUS -> {
                val pm = SonyMdrV2.decodePairingMode(p)
                if (pm != null) {
                    pairingMode = pm
                    Log.d(tag, "Pairing mode = $pm")
                    broadcastStats()
                }
            }

            SonyMdrV2.CMD_PERI_RET_PARAM, SonyMdrV2.CMD_PERI_NTFY_PARAM -> {
                val subtype = p[1].toInt() and 0xFF
                when (subtype) {
                    SonyMdrV2.PERI_TYPE_DEVICE_MANAGEMENT,
                    SonyMdrV2.PERI_TYPE_DEVICE_MANAGEMENT_COD -> {
                        val devices = SonyMdrV2.decodeDeviceList(p)
                        if (devices != null) {
                            peripheralSupported = true
                            connectedDevices = devices
                            Log.d(tag, "Multipoint list (${devices.size}): " +
                                devices.joinToString { "${it.name}${if (it.isActive) "*" else ""}/${it.mac}" })
                            refreshNotification()
                        }
                    }
                    SonyMdrV2.PERI_TYPE_MUSIC_HAND_OVER -> {
                        if (p.size >= 3) {
                            musicHandOver = p[2].toInt() and 0xFF != 0
                            broadcastStats()
                        }
                    }
                    SonyMdrV2.PERI_TYPE_SOURCE_SWITCH -> {
                        // The wire value is "source switch control ENABLED", the
                        // NEGATION of playback-fixed. 0x00 = locked, 0x01 = unlocked.
                        if (p.size >= 3) {
                            val enabled = p[2].toInt() and 0xFF != 0
                            playbackFixed = !enabled
                            Log.d(tag, "Source-switch enabled=$enabled → playbackFixed=$playbackFixed")
                            // The NTFY variant carries the result code at offset 3; the
                            // RET variant has no result field. Refusals actually arrive
                            // here — the 0x3D extended reply is not always sent at all.
                            if (cmd == SonyMdrV2.CMD_PERI_NTFY_PARAM && p.size >= 4) {
                                val result = p[3].toInt() and 0xFF
                                if (result != SonyMdrV2.SOURCE_SWITCH_SUCCESS) {
                                    Log.w(tag, "Source switch REFUSED result=0x%02x".format(result))
                                }
                            }
                            broadcastStats()
                        }
                    }
                }
            }

            SonyMdrV2.CMD_PERI_NTFY_EXT_PARAM -> {
                val res = SonyMdrV2.decodeSourceSwitchResult(p)
                if (res != null) {
                    val ok = res.first == SonyMdrV2.SOURCE_SWITCH_SUCCESS
                    val target = res.second
                    val name = connectedDevices.firstOrNull { it.mac == target }?.name ?: target
                    if (ok) {
                        if (target.isNotBlank()) playbackDeviceMac = target
                        multiStatus = "Switched to $name"
                        scope.launch { refreshMultipoint() }
                    } else {
                        // The refusal code is the whole answer to "why can't I
                        // switch", and it used to be logged and then dropped on the
                        // floor: the row said "Switching to X…" forever with no
                        // indication the headset had already said no. The most
                        // common cause seen so far is a target that is paired but
                        // not connected -- it reports status=0 in the device list.
                        multiStatus = "Switch to $name refused (0x%02x)%s".format(
                            res.first,
                            if (connectedDevices.any { it.mac == target && !it.isConnected })
                                " — that device isn't connected" else "",
                        )
                    }
                    Log.d(tag, "Source switch $multiStatus")
                    broadcastStats()
                }
            }

            // ---- Power params (both tables) ----
            SonyMdrV2.CMD_POWER_RET_PARAM, SonyMdrV2.CMD_POWER_NTFY_PARAM -> {
                if (p.size >= 3 && (p[1].toInt() and 0xFF) == SonyMdrV2.POWER_TYPE_AUTO_POWER_OFF_WEARING) {
                    autoPowerOffMode = p[2].toInt() and 0xFF
                    Log.d(tag, "Auto power-off: ${SonyMdrV2.AutoPowerOff.label(autoPowerOffMode)}")
                    broadcastStats()
                }
            }

            // ---- System params: speak-to-chat / pause-when-taken-off ----
            SonyMdrV2.CMD_SYSTEM_RET_PARAM, SonyMdrV2.CMD_SYSTEM_NTFY_PARAM -> {
                val subtype = p[1].toInt() and 0xFF
                val flag = SonyMdrV2.decodeInvertedFlag(p, subtype)
                when (subtype) {
                    SonyMdrV2.SYS_TYPE_SMART_TALKING -> if (flag != null) {
                        speakToChat = flag
                        Log.d(tag, "Speak-to-chat: $speakToChat")
                    }
                    SonyMdrV2.SYS_TYPE_PLAYBACK_CONTROL_BY_WEARING -> if (flag != null) {
                        pauseWhenTakenOff = flag
                        Log.d(tag, "Pause when taken off: $pauseWhenTakenOff")
                    }
                    // Quick Access: [0D, count, fn...]. Positional, no key byte —
                    // index 0 is the left button, 1 the right.
                    SonyMdrV2.SYS_TYPE_QUICK_ACCESS -> if (p.size >= 3) {
                        val n = p[2].toInt() and 0xFF
                        val fns = IntArray(n) { p[3 + it].toInt() and 0xFF }
                        quickAccessFunctions = fns
                        Log.d(
                            tag, "Quick Access: " +
                                fns.joinToString(", ") { SonyMdrV2.quickAccessName(it) },
                        )
                        broadcastStats()
                    }
                }
                broadcastStats()
            }

            SonyMdrV2.CMD_SYSTEM_RET_EXT_PARAM, SonyMdrV2.CMD_SYSTEM_NTFY_EXT_PARAM -> {
                if (p.size >= 4 && (p[1].toInt() and 0xFF) == SonyMdrV2.SYS_TYPE_SMART_TALKING) {
                    stcSensitivity = p[2].toInt() and 0xFF
                    stcTimeout = p[3].toInt() and 0xFF
                    Log.d(tag, "Speak-to-chat config: sens=$stcSensitivity timeout=$stcTimeout")
                    broadcastStats()
                }
            }

            // ---- Voice guidance (T2 only) ----
            SonyMdrV2.CMD_VOICE_GUIDANCE_RET_PARAM -> {
                val v = SonyMdrV2.decodeVoiceGuidanceVolume(p)
                if (v != null) {
                    voiceGuidanceVolume = v
                    Log.d(tag, "Voice guidance volume: $v")
                    broadcastStats()
                }
            }

            // ---- Audio params: DSEE / BGM / upmix ----
            SonyMdrV2.CMD_AUDIO_RET_PARAM, SonyMdrV2.CMD_AUDIO_NTFY_PARAM -> {
                val subtype = p[1].toInt() and 0xFF
                val flag = SonyMdrV2.decodeInvertedFlag(p, subtype)
                when (subtype) {
                    SonyMdrV2.AUDIO_TYPE_UPSCALING -> {
                        val plain = SonyMdrV2.decodePlainFlag(p, subtype)
                        if (plain != null) {
                            dseeExtreme = plain
                            Log.d(tag, "DSEE Extreme: $plain")
                        }
                    }
                    SonyMdrV2.AUDIO_TYPE_UPMIX_CINEMA -> if (flag != null) {
                        upmixCinema = flag
                        Log.d(tag, "Upmix/Cinema: $upmixCinema")
                    }
                    SonyMdrV2.AUDIO_TYPE_BGM_AND_ERRORCODE,
                    SonyMdrV2.AUDIO_TYPE_BGM_MODE -> if (flag != null) {
                        bgmMode = flag
                        Log.d(tag, "BGM mode: $bgmMode")
                    }
                    SonyMdrV2.AUDIO_TYPE_CONNECTION_MODE_XM6,
                    SonyMdrV2.AUDIO_TYPE_CONNECTION_NOTIFY,
                    SonyMdrV2.AUDIO_TYPE_CONNECTION_MODE_XM5 -> {
                        // XM6 carries the PriorMode byte at index 2; XM5 inserts a
                        // setting-type byte first, so it lands at index 3.
                        val idx = if (subtype == SonyMdrV2.AUDIO_TYPE_CONNECTION_MODE_XM5) 3 else 2
                        val prior = SonyMdrV2.decodePriorMode(p, idx)
                        if (prior != null) {
                            connectionSoundQuality = prior
                            Log.d(tag, "Connection mode: sound quality = $prior")
                        }
                    }
                }
                broadcastStats()
            }

            // Active codec: [0x13, 0x02, codec]. Only meaningful with a live
            // stream — idle the XM6 reports AAC, playing reports LDAC.
            // COMMON_RET_STATUS / COMMON_NTFY_STATUS. Codec is type 0x02, upscaling
            // is 0x03 — same command byte, so dispatch on the type byte.
            SonyMdrV2.CMD_COMMON_RET_STATUS, 0x15 -> {
                val subtype = p.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                when {
                    subtype == SonyMdrV2.COMMON_TYPE_AUDIO_CODEC && p.size >= 3 -> {
                        val c = p[2].toInt() and 0xFF
                        if (c != activeCodec) {
                            activeCodec = c
                            // Derive LDAC from the codec rather than from the AUDIO
                            // connection-mode flag, which reports "inactive" here even
                            // while this same read says LDAC.
                            ldacActive = c == SonyMdrV2.CODEC_LDAC
                            Log.d(tag, "Active codec: ${SonyMdrV2.codecName(c)}")
                        }
                        broadcastStats()
                    }
                    subtype == SonyMdrV2.COMMON_TYPE_UPSCALING_EFFECT && p.size >= 4 -> {
                        val effect = p[2].toInt() and 0xFF
                        val st = p[3].toInt() and 0xFF
                        if (st != upscalingStatus || effect != upscalingEffect) {
                            upscalingStatus = st
                            upscalingEffect = effect
                            Log.d(
                                tag, "Upscaling: ${SonyMdrV2.upscalingEffectName(effect)} " +
                                    SonyMdrV2.upscalingStatusName(st),
                            )
                            broadcastStats()
                        }
                    }
                }
            }

            // SYSTEM_RET_STATUS (0xF3) serves two different features on two
            // different tables, sharing one command byte. Dispatch on table
            // first, then type: T1 0x00 is plain CONNECTION_MODE status, which
            // is not wear state despite looking identical on the wire.
            SonyMdrV2.CMD_SYSTEM_RET_STATUS_T2 -> {
                val type = p.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                val table = SonyMdrV2.Table.of(frame.dataType)
                when {
                    table == SonyMdrV2.Table.T2 &&
                        type == SonyMdrV2.T2_TYPE_WEARING_STATUS_CHECKER && p.size >= 3 ->
                        applyWearCode(p[2].toInt() and 0xFF)

                    // Quick Access master enable, T1: [0xF3, 0x0D, enable].
                    table == SonyMdrV2.Table.T1 &&
                        type == SonyMdrV2.SYS_TYPE_QUICK_ACCESS && p.size >= 3 -> {
                        val en = SonyMdrV2.decodeInvertedEnable(p[2].toInt() and 0xFF)
                        if (en != null && en != quickAccessEnabled) {
                            quickAccessEnabled = en
                            Log.d(tag, "Quick Access enabled: $en")
                            broadcastStats()
                        }
                    }
                }
            }

            // T1 head-gesture notify. Not wear data — it flips in step with
            // don/doff, so it is useful as an early "something changed" hint.
            // Re-ask T2 rather than reading a value off it.
            SonyMdrV2.CMD_SYSTEM_NTFY_STATUS -> {
                val subtype = p.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                if (subtype == SonyMdrV2.SYS_TYPE_HEAD_GESTURE_TRAINING && p.size >= 4) {
                    scope.launch { sendFrame(SonyMdrV2.buildWearingStatusGet(), SonyMdrV2.Table.T2) }
                }
                if (subtype == SonyMdrV2.SYS_TYPE_QUICK_ACCESS && p.size >= 3) {
                    val en = SonyMdrV2.decodeInvertedEnable(p[2].toInt() and 0xFF)
                    if (en != null && en != quickAccessEnabled) {
                        quickAccessEnabled = en
                        Log.d(tag, "Quick Access enabled: $en")
                        broadcastStats()
                    }
                }
            }

            // AUDIO_RET_STATUS for the connection mode. The LDAC flag in this reply
            // is deliberately not read: on the XM6 it reports inactive while the
            // codec read simultaneously reports LDAC, so its field position is not
            // established and it cannot be trusted. LDAC is derived from the codec.
            // AUDIO_RET_STATUS. Type 0x02 is the connection mode, type 0x05 the
            // LE Audio / Classic transport. Both are AUDIO-group types: querying
            // 0x05 through the SYSTEM group returns wake-word status instead,
            // because that byte is also VOICE_ASSISTANT_WAKE_WORD in
            // SystemInquiredType.
            SonyMdrV2.CMD_AUDIO_RET_STATUS -> {
                val atype = p.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                when {
                    atype == SonyMdrV2.AUDIO_TYPE_CONNECTION_MODE_LE_AUDIO && p.size >= 4 -> {
                        val le = SonyMdrV2.decodeInvertedEnable(p[2].toInt() and 0xFF)
                        val classic = SonyMdrV2.decodeInvertedEnable(p[3].toInt() and 0xFF)
                        if (le != null && le != leAudioActive) {
                            leAudioActive = le
                            Log.d(tag, "LE Audio: $le (classic: $classic)")
                            broadcastStats()
                        }
                    }
                    atype == SonyMdrV2.AUDIO_TYPE_CONNECTION_MODE_XM6 -> {
                        Log.d(tag, "Connection mode status: ${SonyMdrV2.hex(p.copyOfRange(2, p.size))}")
                        broadcastStats()
                    }
                }
            }

            // SYSTEM_RET_PARAM (0xF7) — answers a 0xF6 SYSTEM_GET_PARAM. The
            // button mapping arrives here: buildAssignableSettingsGet() is f6 03,
            // so the reply is f7 03, not f3 03. Nothing parsed this before, and
            // the payload layout is still unknown, so it is logged verbatim rather
            // than decoded. An empty reply means the XM6 does not expose it.
            SonyMdrV2.CMD_SYSTEM_RET_PARAM -> {
                val ptype = p.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
                if (ptype == SonyMdrV2.SYS_TYPE_ASSIGNABLE_SETTINGS) {
                    Log.d(tag, "Assignable settings: ${SonyMdrV2.hex(p)}")
                }
            }

            // SENSE (wearing / adaptive control). Not driven yet — the XM6
            // semantics are not understood well enough to write to. Captured raw
            // so the format can be learned from real hardware instead of guessed.
            SonyMdrV2.CMD_SENSE_RET_CAPABILITY,
            SonyMdrV2.CMD_SENSE_NTFY_STATUS,
            SonyMdrV2.CMD_SENSE_NTFY_PARAM,
            SonyMdrV2.CMD_SENSE_RET_EXT_INFO -> {
                senseDebug = p.joinToString(" ") { "0x%02X".format(it) }
                Log.d(tag, "SENSE frame: ${senseDebug}")
                broadcastStats()
            }
        }
    }

    /** Query everything we can display, on both command tables. */
    /**
     * Serialises every outbound state query.
     *
     * The frame log shows three sweeps running at once on a fresh connection —
     * connect, the activity coming to the foreground, and the periodic poll —
     * each interleaving its frames with the others over one socket and one
     * sequence counter. The link is stop and wait, and the result was the
     * device's banner frame arriving four times in a row at 3ms intervals,
     * plus the first few connections failing outright.
     *
     * A sweep already running now causes the next request to be dropped rather
     * than run concurrently.
     */
    private val sweepLock = Mutex()

    private suspend fun runSweep() {
        if (!sweepLock.tryLock()) {
            Log.d(tag, "State sweep already running, skipping")
            return
        }
        try {
            queryAllState()
        } finally {
            sweepLock.unlock()
        }
    }

    private suspend fun queryAllState() {

        // Genuinely stop and wait. Each query is answered before the next is
        // written, which is what the link requires and what a fixed delay could
        // only ever approximate.
        // Table 1 — main features
        request(byteArrayOf(SonyMdrV2.CMD_NCASM_GET_PARAM.toByte(), SonyMdrV2.NCASM_SUBTYPE_STANDARD.toByte()))
        request(SonyMdrV2.buildAutoPowerOffGet())
        request(SonyMdrV2.buildSpeakToChatGet())
        request(SonyMdrV2.buildPauseWhenTakenOffGet())
        request(SonyMdrV2.buildSpeakToChatConfigGet())
        request(SonyMdrV2.buildUpscalingGet())
        request(SonyMdrV2.buildBgmGet())
        request(SonyMdrV2.buildUpmixGet())
        request(SonyMdrV2.buildConnectionModeGet(isXm5()))
        // Button mapping. The reply shape is not established yet, so it is
        // captured raw rather than parsed -- this is the step that makes the
        // [NC/AMB] remap possible instead of guessed at.
        request(SonyMdrV2.buildAssignableSettingsGet())
        // Asked here as well as on the 60s timer: without it the codec stays
        // unknown until a playback transition or a minute elapses, so the badge
        // reads empty on a freshly connected app.
        request(SonyMdrV2.buildAudioCodecGet())
        request(SonyMdrV2.buildWearingStatusGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildLeAudioStatusGet())
        request(SonyMdrV2.buildQuickAccessEnableGet())
        request(SonyMdrV2.buildQuickAccessFunctionGet())
        request(SonyMdrV2.buildUpscalingStatusGet())
        request(byteArrayOf(SonyMdrV2.CMD_EQ_GET_PARAM.toByte(), SonyMdrV2.EQ_SUBTYPE_PRESET_AND_ERROR.toByte()))
        // Table 2 — peripheral / multipoint
        request(SonyMdrV2.buildPeripheralCapabilityGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildDeviceListGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildMusicHandOverGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildSourceSwitchControlGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildPairingModeGet(), SonyMdrV2.Table.T2)
        request(SonyMdrV2.buildVoiceGuidanceVolumeGet(), SonyMdrV2.Table.T2)
    }

    /** Re-read the multipoint list (used after a successful source switch). */
    private suspend fun refreshMultipoint() {
        sendFrame(SonyMdrV2.buildDeviceListGet(), SonyMdrV2.Table.T2)
    }

    // ---- Bluetooth connection helpers ----

    /**
     * Drives every RFCOMM strategy through to an actual connect.
     *
     * Both socket factories only build a socket object: neither proves the
     * channel exists and neither throws when it does not. A previous version
     * therefore drove every strategy through to connect() in order to "prove"
     * each one. That was a regression, and an instructive one: the reflection
     * channel fallback can produce a socket that *connects* without being the
     * MDR service. The stack accepts it, the handshake never arrives, and the
     * headphones drop the link immediately — an EOF on the first read, which
     * looks like a network fault rather than a wrong channel.
     *
     * So: create-first, as it was. The SDP UUID path is the known-good route and
     * is used whenever it yields a socket at all. Reflection channels are tried
     * only when no UUID produces one, which is the case they actually exist for.
     */
    private fun connectSonyRfcomm(device: BluetoothDevice): BluetoothSocket {
        val failures = mutableListOf<String>()

        for (uuidStr in HeadphoneProfile.allUuids) {
            val uuid = UUID.fromString(uuidStr)
            for (secure in listOf(true, false)) {
                val label = (if (secure) "" else "insecure ") + uuidStr
                try {
                    val socket = if (secure) device.createRfcommSocketToServiceRecord(uuid)
                    else device.createInsecureRfcommSocketToServiceRecord(uuid)
                    Log.d(tag, "Using socket from $label")
                    return socket
                } catch (e: IOException) {
                    failures += "create $label: ${e.message}"
                }
            }
        }

        // No UUID produced a socket. Only now is trying channels safe, because
        // there is nothing known-good left to prefer.
        val channels = intArrayOf(1, 10, 2, 3, 5, 15, 20)
        val createCh = try {
            device.javaClass.getMethod("createRfcommSocket", Int::class.java)
        } catch (e: Exception) {
            throw IOException("No RFCOMM strategy available: ${e.message}", e)
        }
        for (ch in channels) {
            try {
                val socket = createCh.invoke(device, ch) as BluetoothSocket
                Log.d(tag, "Using socket from reflection channel $ch")
                return socket
            } catch (e: Exception) {
                failures += "create channel $ch: ${e.message}"
            }
        }

        throw IOException("No RFCOMM socket could be created — ${failures.joinToString("; ")}")
    }

    /**
     * Waits for the Bluetooth profile to report the device connected.
     *
     * ACL being up does not mean the RFCOMM channel is registered yet, and Sony
     * headphones are prone to refusing a socket connect in that window. The
     * profile query is reflective because the two-argument
     * getProfileConnectionState is not in this SDK's stubs. If it cannot be
     * reached at all the wait degrades to a plain settle, so the behaviour is
     * never worse than not having it.
     */
    private suspend fun awaitProfileReady(device: BluetoothDevice, timeoutMs: Long = 4_000L): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return true
        val method = try {
            adapter.javaClass.getMethod(
                "getProfileConnectionState",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
            )
        } catch (_: Exception) {
            Log.d(tag, "Profile query unavailable — settling briefly instead")
            delay(400L)
            return true
        }
        val profiles = intArrayOf(
            android.bluetooth.BluetoothProfile.HEADSET,
            android.bluetooth.BluetoothProfile.A2DP,
        )
        val connected = android.bluetooth.BluetoothProfile.STATE_CONNECTED
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        // Logged once per call, at the first probe. The value arrives boxed from
        // reflection, and if it never equals STATE_CONNECTED this wait silently
        // burns its full timeout on every attempt -- including the ones where
        // the headphones are off and it can never succeed. One line per attempt
        // is enough to tell those two cases apart in a capture.
        var logged = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val ready = profiles.any { p ->
                try {
                    val state = method.invoke(adapter, device, p) as? Int
                    if (!logged) {
                        logged = true
                        Log.d(tag, "Profile probe $p → $state (connected=$connected)")
                    }
                    state == connected
                } catch (e: Exception) {
                    if (!logged) {
                        logged = true
                        Log.d(tag, "Profile probe threw: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    false
                }
            }
            if (ready) return true
            delay(150L)
        }
        return false
    }

    // ---- Media playback callback ----

    private fun onMediaStateChanged(playing: Boolean) {
        if (autoPaused) {
            Log.d(tag, "Auto-paused — ignoring media change")
            return
        }
        if (playing == isMediaPlaying) return  // No change
        isMediaPlaying = playing
        Log.d(tag, "Media ${if (playing) "playing" else "paused"}")
        updateNotification(if (playing) "▶ Playing" else "⏸ Paused")

        // Wait a moment for headphone audio state to settle, then let the user's
        // automation rules decide what to do.
        scope.launch {
            delay(300L)  // brief settle, then send immediately
            // The codec only settles once a stream exists — idle the XM6 reports
            // AAC, playing reports LDAC — so re-ask on every transition rather
            // than waiting for the 60s timer.
            sendFrame(SonyMdrV2.buildAudioCodecGet())
            runAutomation(
                if (playing) Automation.Trigger.PLAYBACK_START else Automation.Trigger.PLAYBACK_STOP
            )
        }
    }

    /**
     * Resolve the first enabled rule for [trigger] and execute it.
     *
     * The original build hard-coded "playing → NC, stopped → ambient". That is
     * now just the *default* rule set, so the behaviour is the user's choice
     * rather than an assumption baked into the app.
     */
    private suspend fun runAutomation(trigger: Automation.Trigger) {
        // The single choke point for every automated command, so the pause check
        // belongs here rather than in one caller. Guarding only onMediaStateChanged
        // was not enough in two ways:
        //   - that check runs *before* a 300ms settle delay, so pausing inside
        //     that window still let the command through;
        //   - the post-handshake path calls runAutomation directly, with no check
        //     at all, so a reconnect re-applied rules even while paused.
        if (autoPaused) {
            Log.d(tag, "Automations paused — skipping ${trigger.id}")
            return
        }
        val rules = automationRules
        val rule = Automation.resolve(rules, trigger)
        if (rule == null) {
            Log.d(tag, "Automation: no rule for ${trigger.id} — leaving state alone")
            return
        }
        Log.d(tag, "Automation: ${trigger.id} → ${Automation.describe(rule)}")
        when (rule.action.type) {
            Automation.ActionType.NONE -> Unit
            Automation.ActionType.SET_MODE -> when (rule.action.mode) {
                Automation.Mode.NC -> sendFrame(profile.ancOn(ambientLevel()))
                Automation.Mode.AMBIENT -> sendFrame(sonyAmbientCommand())
                Automation.Mode.OFF -> sendFrame(profile.ancOff(ambientLevel()))
            }
            Automation.ActionType.SET_AMBIENT_LEVEL -> {
                // Was `profile.ancOn(rule.action.value)`, which builds
                // [0x68,0x19,0x01,0x01,0x00,...] — byte 4 = 0x00, i.e. the NC
                // payload. "Set ambient level" therefore switched noise cancelling
                // ON instead of changing the ambient level. Use the same builder the
                // UI uses, with the rule's level overriding the stored one.
                val saved = ambientLevel()
                val cmd = profile.ambient(
                    level = rule.action.value,
                    voice = voicePassthrough(),
                    noiseAdaptive = autoAmbient(),
                )
                sendFrame(cmd)
                if (rule.action.value != saved) {
                    // Persist so the UI slider reflects what the routine just set.
                    val addr = deviceAddress
                    if (addr != null)
                        prefs().edit().putInt("ambient_level_$addr", rule.action.value).apply()
                }
            }
            Automation.ActionType.SET_VOLUME -> {
                // Media volume had no working path on this hardware and was judged
                // useless, so it was removed rather than left half-wired.
                Log.d(tag, "Automation: SET_VOLUME no longer supported — ignoring")
            }
            Automation.ActionType.SET_EQ_PRESET -> {
                sendFrame(byteArrayOf(SonyMdrV2.CMD_EQ_SET_PARAM.toByte(), SonyMdrV2.EQ_SUBTYPE_PRESET_AND_ERROR.toByte(), rule.action.presetId.toByte(), 0x00))
                delay(120L)
                sendFrame(byteArrayOf(SonyMdrV2.CMD_EQ_GET_PARAM.toByte(), SonyMdrV2.EQ_SUBTYPE_PRESET_AND_ERROR.toByte()))
            }
            // The remaining headphone features. Each reuses the exact builder the
            // Audio tab uses, so a routine and a manual toggle cannot drift apart.
            Automation.ActionType.SET_SPEAK_TO_CHAT ->
                sendFrame(SonyMdrV2.buildSpeakToChatSet(rule.action.value != 0))
            Automation.ActionType.SET_PAUSE_TAKEN_OFF ->
                sendFrame(SonyMdrV2.buildPauseWhenTakenOffSet(rule.action.value != 0))
            Automation.ActionType.SET_DSEE ->
                sendFrame(SonyMdrV2.buildUpscalingSet(rule.action.value != 0))
            Automation.ActionType.SET_BGM ->
                sendFrame(SonyMdrV2.buildBgmSet(rule.action.value != 0))
            Automation.ActionType.SET_UPMIX ->
                sendFrame(SonyMdrV2.buildUpmixSet(rule.action.value != 0))
            // These two live in the ambient payload rather than a command of their
            // own, so the stored pref is flipped and the ambient frame re-sent —
            // same path as applyAmbientNow(), just driven by a rule.
            Automation.ActionType.SET_VOICE_PASSTHROUGH -> {
                setAmbientFlag("voice_passthrough", rule.action.value != 0)
                sendFrame(sonyAmbientCommand())
            }
            Automation.ActionType.SET_AUTO_AMBIENT -> {
                setAmbientFlag("auto_ambient", rule.action.value != 0)
                sendFrame(sonyAmbientCommand())
            }
            Automation.ActionType.SET_AUTO_POWER -> {
                val mode = if (rule.action.power == Automation.AutoPower.NEVER)
                    SonyMdrV2.AutoPowerOff.NEVER else SonyMdrV2.AutoPowerOff.WHEN_TAKEN_OFF
                sendFrame(SonyMdrV2.buildAutoPowerOffSet(mode))
            }
            Automation.ActionType.SET_VOICE_GUIDANCE -> {
                sendFrame(SonyMdrV2.buildVoiceGuidanceVolumeSet(rule.action.value), SonyMdrV2.Table.T2)
                delay(120L)
                sendFrame(SonyMdrV2.buildVoiceGuidanceVolumeGet(), SonyMdrV2.Table.T2)
            }
        }
        refreshNotification()
    }

    /** Persist an ambient flag so the ambient frame picks it up next time. */
    private fun setAmbientFlag(key: String, on: Boolean) {
        val addr = deviceAddress ?: return
        prefs().edit().putBoolean("${key}_$addr", on).apply()
    }

    private fun currentPlaybackTrigger(): Automation.Trigger =
        if (isMediaPlaying) Automation.Trigger.PLAYBACK_START else Automation.Trigger.PLAYBACK_STOP

    /** Re-read rules from prefs — called when the UI changes them. */
    fun reloadAutomation() {
        automationRules = Automation.load(this)
        Log.d(tag, "Automation rules reloaded: ${automationRules.size}")
    }

    /**
     * Single send (no retries) for debug buttons — the payload format is correct now
     * so a single frame is enough. Uses [sendFrame] to keep it simple.
     */
    private fun sendAncCommandOnce(payload: ByteArray) {
        Log.d(tag, "Single: ${profile.describe(payload)} — ${SonyAncProtocol.describePayload(payload)}")
        sendFrame(payload)
    }

    /**
     * Send arbitrary payload bytes wrapped in an MDR frame, used for
     * protocol handshake commands.
     */
    private fun sendFrame(payload: ByteArray, table: SonyMdrV2.Table = SonyMdrV2.Table.T1) {
        val socket = btSocket ?: return
        try {
            val frame = SonyMdrV2.buildFrame(currentSeq, payload, table)
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            Log.d(tag, "→ [${if (table == SonyMdrV2.Table.T2) "T2" else "T1"}] ${SonyMdrV2.hex(payload)} seq=$currentSeq")
            recordFrame("TX", if (table == SonyMdrV2.Table.T2) "T2" else "T1", payload, currentSeq)
            currentSeq = currentSeq xor 1

            // Track the mode we last commanded (for notification stats)
            if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == SonyMdrV2.CMD_NCASM_SET_PARAM) {
                currentModeName = profile.describe(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Frame send failed: ${e.message}")
            // Only tear down the socket this write actually used. A newer
            // connection may have replaced btSocket while this frame was in
            // flight, and nulling it unconditionally killed a perfectly
            // healthy link over a failure belonging to the previous one.
            if (btSocket === socket) {
                btSocket = null
                try { socket.close() } catch (_: Exception) {}
                triggerReconnect()
            } else {
                Log.d(tag, "Write failed on an already-replaced socket — leaving the live link alone")
            }
        }
    }

    /** Set an EQ preset. Command: 58 00 <presetID> 00, then re-query with 56 00. */
    private suspend fun setEQPreset(presetId: Int) {
        sendFrame(byteArrayOf(0x58, 0x00, presetId.toByte(), 0x00))
        delay(100L)
        sendFrame(byteArrayOf(0x56, 0x00))
    }

    /** Set custom EQ bands for a specific profile (0xA0=Custom, 0xA1=User1, etc.). */
    private suspend fun setCustomEQ(bands: IntArray, presetId: Int = 0xA0) {
        val count = bands.size
        val offset = if (count == 10) 6 else 10
        var payload = byteArrayOf(0x58, 0x00, presetId.toByte(), count.toByte())
        for (v in bands) {
            val clamped = (v + offset).coerceIn(0, 2 * offset)
            payload += clamped.toByte()
        }
        sendFrame(payload)
        delay(100L)
        sendFrame(byteArrayOf(0x56, 0x00))
    }

    /**
     * Send a protocol ACK frame (dataType=0x01, flipped seq, empty payload).
     */
    // ---- Hidden debug menu: frame log + raw payload injection ----
    //
    // SENSE and the LDAC status read both draw silence from the XM6, and working
    // out why by guessing type bytes one release at a time is slow. This records
    // the traffic and lets a payload be sent by hand, so the protocol can be read
    // off the device instead of proposed and rejected.

    private val frameLog = ArrayDeque<String>()
    private var lastFrameLogPush = 0L
    private val frameLogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Trailing push. A plain throttle drops the update for any frame arriving
     * inside the window, which means the last frame of an exchange is recorded
     * but never sent — the screen then looks frozen until some later frame
     * happens to trip the throttle. Scheduling the remainder fixes that.
     */
    private val trailingPush = Runnable {
        lastFrameLogPush = System.currentTimeMillis()
        pushFrameLog()
    }

    private fun recordFrame(direction: String, table: String, payload: ByteArray, seq: Int? = null) {
        val cal = java.util.Calendar.getInstance()
        val stamp = String.format(
            java.util.Locale.US, "%02d:%02d:%02d.%03d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND), cal.get(java.util.Calendar.MILLISECOND),
        )
        val seqTxt = if (seq != null) " seq=$seq" else ""
        frameLog.addLast("$stamp  $direction [$table] ${SonyMdrV2.hex(payload)}$seqTxt")
        while (frameLog.size > 300) frameLog.removeFirst()
        // Throttle a burst, but always flush the tail.
        val now = System.currentTimeMillis()
        val elapsed = now - lastFrameLogPush
        if (elapsed > FRAME_LOG_PUSH_INTERVAL_MS) {
            lastFrameLogPush = now
            pushFrameLog()
        } else {
            frameLogHandler.removeCallbacks(trailingPush)
            frameLogHandler.postDelayed(trailingPush, FRAME_LOG_PUSH_INTERVAL_MS - elapsed)
        }
    }

    private fun pushFrameLog() {
        val intent = Intent(FRAME_LOG_BROADCAST).apply {
            putExtra(EXTRA_FRAME_LOG, frameLog.joinToString("\n"))
            `package` = packageName
        }
        try { sendBroadcast(intent) } catch (_: Exception) {}
    }

    /** "e7 02 00" / "e7,02,00" / "e70200" -> bytes; null when unparseable. */
    private fun parseHex(input: String): ByteArray? {
        val cleaned = input.replace(",", " ").replace("-", " ").trim()
        if (cleaned.isEmpty()) return null
        val parts = if (cleaned.contains(' ')) cleaned.split(Regex("\\s+"))
        else cleaned.chunked(2)
        val out = ArrayList<Byte>(parts.size)
        for (p in parts) {
            if (p.isEmpty()) continue
            val v = p.toIntOrNull(16) ?: return null
            if (v !in 0..0xFF) return null
            out.add(v.toByte())
        }
        return if (out.isEmpty()) null else out.toByteArray()
    }

    /**
     * ACK on the socket the frame actually arrived on, not on whatever
     * `btSocket` currently points at.
     *
     * These two can differ: the reader is draining the socket it captured when
     * the link came up, while a reconnect may already have published a newer
     * one. Re-reading the global meant ACKs for the old link were written to the
     * new channel, which on a strict stop-and-wait link desyncs the device's
     * retransmit state and can close a perfectly good connection.
     */
    private fun sendAck(socket: BluetoothSocket, seq: Int) {
        try {
            // body: dataType(0x01) seq size(4×0x00) — checksum = sum
            val check = (0x01 + seq) and 0xFF
            val frame = byteArrayOf(0x3E, 0x01, seq.toByte(), 0x00, 0x00, 0x00, 0x00, check.toByte(), 0x3C)
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        } catch (_: Exception) {
            // ACK failure is fine; the read loop will detect a real disconnect
        }
    }

    /**
     * Incremental MDR frame decoder: feeds raw bytes, emits complete frames.
     * Handles byte-stuffing (0x3D escape) and checksum validation.
     */
    private class MdrFrameDecoder {
        private val buffer = java.io.ByteArrayOutputStream()
        private var inFrame = false
        private var escaping = false

        fun feed(bytes: ByteArray): List<MdrFrame> {
            val frames = mutableListOf<MdrFrame>()
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (!inFrame) {
                    if (v == 0x3E) {
                        inFrame = true
                        buffer.reset()
                        escaping = false
                    }
                    continue
                }
                if (escaping) {
                    buffer.write((v + 0x10) and 0xFF)
                    escaping = false
                    continue
                }
                when (v) {
                    0x3D -> escaping = true
                    0x3C -> {
                        inFrame = false
                        parse(buffer.toByteArray())?.let { frames.add(it) }
                    }
                    0x3E -> {
                        buffer.reset()  // resync on unexpected SOF
                    }
                    else -> buffer.write(v)
                }
            }
            return frames
        }

        private fun parse(inner: ByteArray): MdrFrame? {
            if (inner.size < 7) return null  // type + seq + 4 size + checksum min
            val len = ((inner[2].toInt() and 0xFF) shl 24) or
                ((inner[3].toInt() and 0xFF) shl 16) or
                ((inner[4].toInt() and 0xFF) shl 8) or
                (inner[5].toInt() and 0xFF)
            if (inner.size != 6 + len + 1) return null
            val sum = inner.dropLast(1).fold(0) { acc, x -> acc + (x.toInt() and 0xFF) } and 0xFF
            if (sum != (inner.last().toInt() and 0xFF)) return null
            return MdrFrame(
                dataType = inner[0].toInt() and 0xFF,
                sequence = inner[1].toInt() and 0xFF,
                payload = inner.copyOfRange(6, 6 + len),
            )
        }
    }

    private data class MdrFrame(val dataType: Int, val sequence: Int, val payload: ByteArray)

    private fun triggerReconnect() {
        val addr = deviceAddress ?: return
        // First close old socket to unblock the read() loop
        try {
            btSocket?.close()
        } catch (_: Exception) {}
        btSocket = null
        // Honour the backoff instead of firing immediately. Without this an
        // open-then-EOF socket spun the connect loop with nothing in between.
        val since = android.os.SystemClock.elapsedRealtime() - lastConnectAttemptAt
        val wait = (RETRY_DELAY_MS - since).coerceAtLeast(0L)
        scope.launch {
            if (wait > 0) delay(wait)
            connectBluetooth(addr)
        }
    }

    // ---- Notifications ----

    private lateinit var notificationManager: NotificationManager

    private fun createNotificationChannel() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sony ANC Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controls headphone ANC based on media playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusLine: String?): Notification {
        val paused = autoPaused

        // Pause state REPLACES the status line instead of being appended to it.
        // Appending produced "⏸ Automations paused — ⏸ Automations paused",
        // because the status line and the stats block each carried the same string.
        val primary = if (paused) "Automations paused" else (statusLine ?: defaultStatusLine())

        val meta = buildString {
            batteryPercent?.let { append("$it%") }
            if (currentModeName != "—") {
                if (isNotEmpty()) append(" · ")
                append(currentModeName)
            }
        }

        val expanded = if (meta.isEmpty()) primary else "$primary\n$meta"

        val toggleIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_TOGGLE_AUTO },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val ancOnIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_ANC_ON },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ambientIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_AMBIENT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openAppIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The shade already shows "Sony ANC Auto" above this, so the model name
            // is more useful here than repeating the app name.
            .setContentTitle(profile.modelName)
            .setContentText(primary)
            .apply { if (meta.isNotEmpty()) setSubText(meta) }
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setSmallIcon(R.drawable.ic_stat_headphones)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            // Foreground service notification: it refreshes on every battery tick and
            // mode change, and must not buzz each time.
            .setSilent(true)
            .addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "Resume" else "Pause",
                toggleIntent,
            )
            .addAction(R.drawable.ic_mode_nc, "Noise cancelling", ancOnIntent)
            .addAction(R.drawable.ic_mode_ambient, "Ambient", ambientIntent)
            .build()
    }

    /** Status derived purely from state, used when no transient message is supplied. */
    private fun defaultStatusLine(): String = when (status) {
        Status.CONNECTED -> if (isMediaPlaying) "Playing" else "Idle"
        Status.CONNECTING -> "Connecting…"
        Status.DISCONNECTED -> "Disconnected"
        Status.ERROR -> "Error"
    }

    private fun updateNotification(text: String? = null) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
            // Race on early startup
        }
    }

    /** Refreshes the notification with current stats without changing the main text. */
    private fun refreshNotification() {
        updateNotification(null)
        broadcastStats()
    }

    // ---- Status broadcasting ----

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    // Tracked EQ state (from incoming 0x57/0x59 frames)
    var eqActivePreset: Int = 0
        private set
    var eqActiveBands: IntArray? = null
        private set

    // Multipoint device list from PERIPHERAL NTFY
    var connectedDevices: List<MultipointDevice> = emptyList()
        private set
    var peripheralSupported: Boolean = false
        private set
    var musicHandOver: Boolean = false
        private set

    // Headphone-side feature state (read back from the device, not just what we sent)
    var autoPowerOffMode: Int = 0
        private set
    var speakToChat: Boolean = false
        private set
    var stcSensitivity: Int = 0
        private set
    var stcTimeout: Int = 0
        private set
    var pauseWhenTakenOff: Boolean = false
        private set
    var dseeExtreme: Boolean = false
        private set
    var bgmMode: Boolean = false
        private set
    var upmixCinema: Boolean = false
        private set

    /** null until the headphones answer our inquiry. */
    var connectionSoundQuality: Boolean? = null
        private set

    /** null until the headphones report LDAC is actually in use. */
    var ldacActive: Boolean? = null
        private set

    /** Active Bluetooth codec id, or null until the first report. */
    var activeCodec: Int? = null
        private set

    /**
     * Authoritative wear code from the T2 wearing-status checker, or null until
     * the first reply. Every T1 wearing probe on this model returns nothing, so
     * this is the only trustworthy source.
     */
    var wearStatusCode: Int? = null
        private set

    /** true = on head, false = off head, null = unknown. Derived from [wearStatusCode]. */
    var headphonesWorn: Boolean? = null
        private set

    /**
     * LE Audio transport active, or null until reported. Switching this forces
     * the headphones to drop the Bluetooth link and re-establish it.
     */
    var leAudioActive: Boolean? = null
        private set

    /** Quick Access master enable, or null until reported. */
    var quickAccessEnabled: Boolean? = null
        private set

    /** Per-button quick access functions. Index 0 = left, 1 = right. */
    var quickAccessFunctions: IntArray? = null
        private set

    /** DSEE/upscaling effect status, or null until reported. No reason field exists. */
    var upscalingStatus: Int? = null
        private set

    /** Which upscaling variant the headset has selected, or null until reported. */
    var upscalingEffect: Int? = null
        private set

    private fun applyWearCode(code: Int) {
        val changed = code != wearStatusCode
        wearStatusCode = code
        val worn = SonyMdrV2.isWorn(code)
        val wearChanged = worn != headphonesWorn
        headphonesWorn = worn
        if (changed) Log.d(tag, "Wear status: ${SonyMdrV2.wearStatusName(code)}")
        // Broadcast unconditionally. The activity can miss the first one — it may
        // still be starting, or be recreated later — so skipping this when the
        // value is unchanged left the glyph blank until the next real don/doff.
        broadcastStats()
        if (changed && wearChanged) {
            scope.launch {
                runAutomation(
                    if (worn) Automation.Trigger.HEADPHONES_ON
                    else Automation.Trigger.HEADPHONES_OFF
                )
            }
        }
    }

    /** Last raw SENSE frame seen, for the Home debug line. */
    var senseDebug: String = "no SENSE frame yet"
        private set

    /** True when the connected model uses the V1 (XM5) wire format. */
    private fun isXm5(): Boolean = profile == HeadphoneProfile.Xm5

    /** True = "Fix Playback": multipoint will not hand the audio over. */

    /** True while the headphones are in Bluetooth pairing (inquiry scan) mode. */
    var pairingMode: Boolean = false
        private set
    var playbackFixed: Boolean = false
        private set

    /**
     * Null until the headset's advertised T2 support list arrives. False means the
     * headset does not implement `SOURCE_SWITCH_CONTROL`, so the device silently
     * ignores the lock and the button has to be disabled.
     */
    var sourceSwitchControlSupported: Boolean? = null
        private set

    /**
     * MAC of the device the headphones currently route audio to, as reported by the
     * source-switch reply. Null until the headset has told us at least once.
     */
    var playbackDeviceMac: String? = null

    /** Outcome of the last source-switch attempt, shown under the device list. */
    var multiStatus: String = ""
        private set
    var voiceGuidanceVolume: Int = -1
        private set

    private fun broadcastStatus(s: Status, message: String? = null) {
        val intent = Intent(STATUS_BROADCAST).apply {
            putExtra(EXTRA_STATUS, s.name)
            putExtra(EXTRA_DEVICE, deviceAddress)
            if (message != null) putExtra(EXTRA_MESSAGE, message)
            if (batteryPercent != null) putExtra(EXTRA_BATTERY, batteryPercent!!)
            putExtra(EXTRA_MODE, currentModeName)
            `package` = packageName
        }
        try {
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    /** Broadcast current stats (battery/mode) without changing status. */
    private fun broadcastStats() {
        val intent = Intent(STATUS_BROADCAST).apply {
            putExtra(EXTRA_STATUS, status.name)
            putExtra(EXTRA_DEVICE, deviceAddress)
            if (batteryPercent != null) putExtra(EXTRA_BATTERY, batteryPercent!!)
            putExtra(EXTRA_MODE, currentModeName)
            putExtra(EXTRA_EQ_ACTIVE_PRESET, eqActivePreset)
            eqActiveBands?.let { putExtra(EXTRA_EQ_ACTIVE_BANDS, it) }
            if (connectedDevices.isNotEmpty()) {
                putExtra(EXTRA_DEVICE_LIST, connectedDevices.map { it.name }.toTypedArray())
                putExtra(EXTRA_DEVICE_MACS, connectedDevices.map { it.mac }.toTypedArray())
                putExtra(EXTRA_MULTI_STATUS, multiStatus)
                putExtra(EXTRA_DEVICE_ACTIVE, connectedDevices.map { it.isActive }.toBooleanArray())
            }
            putExtra(EXTRA_PERIPHERAL_OK, peripheralSupported)
            putExtra(EXTRA_AUTO_POWER_MODE, autoPowerOffMode)
            putExtra(EXTRA_SPEAK_TO_CHAT, speakToChat)
            putExtra(EXTRA_STC_SENS, stcSensitivity)
            putExtra(EXTRA_STC_TIMEOUT, stcTimeout)
            putExtra(EXTRA_PAUSE_TAKEN_OFF, pauseWhenTakenOff)
            putExtra(EXTRA_DSEE, dseeExtreme)
            putExtra(EXTRA_BGM, bgmMode)
            putExtra(EXTRA_UPMIX, upmixCinema)
        connectionSoundQuality?.let { putExtra(EXTRA_CONNECTION_MODE, it) }
        ldacActive?.let { putExtra(EXTRA_LDAC_ACTIVE, it) }
        activeCodec?.let { putExtra(EXTRA_ACTIVE_CODEC, it) }
        headphonesWorn?.let { putExtra(EXTRA_HEADPHONES_WORN, it) }
        leAudioActive?.let { putExtra(EXTRA_LE_AUDIO, it) }
        quickAccessEnabled?.let { putExtra(EXTRA_QUICK_ACCESS_ENABLED, it) }
        quickAccessFunctions?.let { putExtra(EXTRA_QUICK_ACCESS_FUNCTIONS, it) }
        upscalingStatus?.let { putExtra(EXTRA_UPSCALING_STATUS, it) }
        upscalingEffect?.let { putExtra(EXTRA_UPSCALING_EFFECT, it) }
        putExtra(EXTRA_SENSE_DEBUG, senseDebug)
            putExtra(EXTRA_FIX_PLAYBACK, playbackFixed)
            putExtra(EXTRA_AUTO_PAUSED, autoPaused)
            sourceSwitchControlSupported?.let { putExtra(EXTRA_SWITCH_CONTROL_SUPPORTED, it) }
            putExtra(EXTRA_PAIRING_MODE, pairingMode)
            putExtra(EXTRA_VOICE_GUIDANCE_VOLUME, voiceGuidanceVolume)
            `package` = packageName
        }
        try { sendBroadcast(intent) } catch (_: Exception) {}
    }

    // ---- Companion / constants ----

    companion object {
        const val TAG = "BTAncSvc"
        const val PACKAGE = "com.fencewatcher.sonyanc"

        // Running state shared with the UI
        @Volatile
        var isRunning = false

        // Service UUID from Gadgetbridge reverse-engineering
        // Service UUIDs — now managed by HeadphoneProfile.allUuids

        // Intent actions
        const val ACTION_START = "$PACKAGE.action.START"
        const val ACTION_STOP = "$PACKAGE.action.STOP"
        const val ACTION_GET_STATUS = "$PACKAGE.action.GET_STATUS"
        const val ACTION_ANC_ON = "$PACKAGE.action.ANC_ON"
        const val ACTION_AMBIENT = "$PACKAGE.action.AMBIENT"
        const val ACTION_APPLY_AMBIENT = "$PACKAGE.action.APPLY_AMBIENT"

        /** Percentage at or below which the battery-low automation trigger fires. */
        const val BATTERY_LOW_THRESHOLD = 20
        const val ACTION_ANC_OFF = "$PACKAGE.action.ANC_OFF"
        const val ACTION_TOGGLE_AUTO = "$PACKAGE.action.TOGGLE_AUTO"
        const val ACTION_SET_EQ = "$PACKAGE.action.SET_EQ"
        const val ACTION_SET_EQ_CUSTOM = "$PACKAGE.action.SET_EQ_CUSTOM"
        const val ACTION_POWER_OFF = "$PACKAGE.action.POWER_OFF"
        const val ACTION_REFRESH_DEVICES = "$PACKAGE.action.REFRESH_DEVICES"
        const val ACTION_SOURCE_SWITCH = "$PACKAGE.action.SOURCE_SWITCH"
        const val ACTION_SET_SPEAK_TO_CHAT = "$PACKAGE.action.SET_SPEAK_TO_CHAT"
        const val ACTION_SET_PAUSE_TAKEN_OFF = "$PACKAGE.action.SET_PAUSE_TAKEN_OFF"
        const val ACTION_SET_DSEE = "$PACKAGE.action.SET_DSEE"
        const val ACTION_SET_BGM = "$PACKAGE.action.SET_BGM"
        const val ACTION_SET_UPMIX = "$PACKAGE.action.SET_UPMIX"

        /** "sound_quality" boolean extra: true = prioritise audio, false = stability. */
        const val ACTION_SET_CONNECTION_MODE = "$PACKAGE.action.SET_CONNECTION_MODE"
        const val ACTION_SET_AUTO_POWER = "$PACKAGE.action.SET_AUTO_POWER"
        const val ACTION_SET_VOICE_GUIDANCE = "$PACKAGE.action.SET_VOICE_GUIDANCE"
        const val ACTION_RELOAD_AUTOMATION = "$PACKAGE.action.RELOAD_AUTOMATION"
        const val ACTION_ENTER_PAIRING_MODE = "$PACKAGE.action.ENTER_PAIRING_MODE"
        const val ACTION_SET_FIX_PLAYBACK = "$PACKAGE.action.SET_FIX_PLAYBACK"
        const val ACTION_PAIRED_DEVICE_ACTION = "$PACKAGE.action.PAIRED_DEVICE_ACTION"
        const val EXTRA_FIX_PLAYBACK = "fix_playback"
        const val EXTRA_AUTO_PAUSED = "auto_paused"
        const val EXTRA_PAIRING_MODE = "pairing_mode"
        const val EXTRA_SWITCH_CONTROL_SUPPORTED = "switch_control_supported"

        const val EXTRA_VOICE_GUIDANCE_VOLUME = "voice_guidance_volume"

        const val EXTRA_TARGET_MAC = "target_mac"
        const val EXTRA_PERIPHERAL_OK = "peripheral_ok"
        const val EXTRA_SPEAK_TO_CHAT = "speak_to_chat"
        const val EXTRA_STC_SENS = "stc_sens"
        const val EXTRA_STC_TIMEOUT = "stc_timeout"
        const val EXTRA_PAUSE_TAKEN_OFF = "pause_taken_off"
        const val EXTRA_DSEE = "dsee"
        const val EXTRA_BGM = "bgm"
        const val EXTRA_UPMIX = "upmix"

        /** Tri-state: true = sound quality, false = connection, absent = unknown. */
        const val EXTRA_CONNECTION_MODE = "connection_mode"

        /** Last raw SENSE frame, hex, for the Home debug line. */
        const val EXTRA_SENSE_DEBUG = "sense_debug"

        /** True when the headphones report LDAC is actually in use. */
        const val EXTRA_LDAC_ACTIVE = "ldac_active"

        const val EXTRA_ACTIVE_CODEC = "active_codec"
        const val EXTRA_HEADPHONES_WORN = "headphones_worn"
        const val EXTRA_LE_AUDIO = "le_audio"
        const val EXTRA_QUICK_ACCESS_ENABLED = "quick_access_enabled"
        const val EXTRA_QUICK_ACCESS_FUNCTIONS = "quick_access_functions"
        const val EXTRA_UPSCALING_STATUS = "upscaling_status"
        const val EXTRA_UPSCALING_EFFECT = "upscaling_effect"

        const val FRAME_LOG_BROADCAST = "$PACKAGE.action.FRAME_LOG"
        const val EXTRA_FRAME_LOG = "frame_log"
        const val ACTION_SEND_RAW = "$PACKAGE.action.SEND_RAW"
        const val ACTION_CLEAR_FRAME_LOG = "$PACKAGE.action.CLEAR_FRAME_LOG"

        /** Switch the LE Audio / Classic Audio transport. Expect a reconnect. */
        const val ACTION_SET_LE_AUDIO = "$PACKAGE.action.SET_LE_AUDIO"

        /** Assign quick access functions. Extra "functions" as an int array. */
        const val ACTION_SET_QUICK_ACCESS = "$PACKAGE.action.SET_QUICK_ACCESS"

        /** Re-send the current log without clearing it. */
        const val ACTION_GET_FRAME_LOG = "$PACKAGE.action.GET_FRAME_LOG"

        /**
         * Re-query everything and re-broadcast. The activity calls this when it
         * comes to the foreground: the connect sweep only runs once, so opening
         * the app after that leaves every field stale until the 60s poll.
         */
        const val ACTION_SYNC_STATE = "$PACKAGE.action.SYNC_STATE"

        /** Minimum gap between frame-log broadcasts to the debug menu. */
        const val FRAME_LOG_PUSH_INTERVAL_MS = 250L

        // Intent extras
        const val EXTRA_ADDRESS = "device_address"

        /**
         * "auto" (default), "xm5" or "xm6". Lets the user pin a protocol when the
         * device name is unhelpful or the fallback guess is wrong.
         */
        const val EXTRA_PROFILE_OVERRIDE = "profile_override"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_EQ_PRESET = "eq_preset"
        const val EXTRA_EQ_BANDS = "eq_bands"
        const val EXTRA_EQ_ACTIVE_PRESET = "eq_active_preset"
        const val EXTRA_EQ_ACTIVE_BANDS = "eq_active_bands"
        const val EXTRA_DEVICE_LIST = "device_list"
        const val EXTRA_MULTI_STATUS = "multi_status"
        const val EXTRA_DEVICE_MACS = "device_macs"
        const val EXTRA_DEVICE_ACTIVE = "device_active"
        const val EXTRA_AUTO_POWER_MODE = "auto_power_mode"

        // Status broadcast
        const val STATUS_BROADCAST = "$PACKAGE.STATUS"

        // Notification
        const val CHANNEL_ID = "sony_anc_service"
        const val NOTIFICATION_ID = 1

        // Settings
        const val PREFS_NAME = "anc_settings"
        const val KEY_AMBIENT_LEVEL = "ambient_level"
        const val KEY_VOICE_PASSTHROUGH = "voice_passthrough"
        const val KEY_AMBIENT_NOISE_ADAPTIVE = "ambient_noise_adaptive"
        const val KEY_ALLOWLIST_ENABLED = "allowlist_enabled" // unused, retained to clear stale prefs
        const val KEY_ALLOWLIST = "allowlist_apps" // unused, retained to clear stale prefs

        // Broadcast extras
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_MODE = "mode"

        // Reconnection
        // Bounded on purpose. The old budget of 20 attempts with a 48s ceiling
        // meant 13.6 minutes of backoff before the app would admit the
        // headphones were gone -- and a dropped link deserves far less than
        // that. A link that is merely flapping recovers in about a second
        // (the everConnected fast path); a pair that is off is not coming back
        // on attempt six, and the slow poll below covers that case anyway.
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 3_000L

        /** Gap between ACL-triggered reconnects, so a flapping link cannot storm. */
        private const val ACL_RECONNECT_COOLDOWN_MS = 30_000L

        /**
         * How often to try again once the burst is spent and the headphones
         * look absent.
         *
         * Stopping outright would risk stranding the app: if the headphones are
         * already on and ACL-up, no further ACL_CONNECTED ever arrives to wake
         * us. One attempt a minute is not "constantly reconnecting", and it is
         * the difference between recovering on its own and needing a manual
         * Start.
         */
        private const val ABSENT_RETRY_MS = 60_000L
    }
}