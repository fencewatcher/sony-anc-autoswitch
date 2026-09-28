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
    private var currentSeq = 0
    private var isMediaPlaying = false
    private var deviceAddress: String? = null
    private var batteryPercent: Int? = null
    private var currentModeName: String = "—"
    @Volatile
    private var autoPaused = false
    private var profile: HeadphoneProfile = HeadphoneProfile.Xm6

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
                    isRunning = true
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
                }

                ACTION_ANC_ON -> {
                    Log.d(tag, "Debug: manual ANC ON")
                    scope.launch { sendAncCommandOnce(profile.ancOn(ambientLevel())) }
                }

                ACTION_AMBIENT -> {
                    Log.d(tag, "Debug: manual AMBIENT")
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
                }

                ACTION_SET_EQ -> {
                    val presetId = intent.getIntExtra(EXTRA_EQ_PRESET, 0)
                    Log.d(tag, "Setting EQ preset to ${String.format("0x%02x", presetId)}")
                    scope.launch { setEQPreset(presetId) }
                }

                ACTION_SET_EQ_CUSTOM -> {
                    val bands = intent.getIntArrayExtra(EXTRA_EQ_BANDS)
                    if (bands != null) {
                        Log.d(tag, "Setting custom EQ (${bands.size} bands)")
                        scope.launch { setCustomEQ(bands) }
                    }
                }
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

    private fun allowlistEnabled(): Boolean =
        prefs().getBoolean(KEY_ALLOWLIST_ENABLED, false)

    private fun allowlist(): Set<String> =
        prefs().getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()

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

        // Start media playback monitoring immediately (with app filtering)
        mediaMonitor = MediaPlaybackMonitor(
            this,
            allowlistProvider = {
                if (allowlistEnabled()) allowlist() else emptySet()
            },
        ) { playing ->
            onMediaStateChanged(playing)
        }
        mediaMonitor?.start()

        connectBluetooth(address)
    }

    // ---- Bluetooth connection (runs on IO dispatcher) ----

    private fun connectBluetooth(address: String) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            var retries = 0

            while (isActive && retries < MAX_RETRIES) {
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

                    // Detect headphone model from device name
                    profile = HeadphoneProfile.detect(device.name ?: "")
                    Log.d(tag, "Profile: ${profile.modelName} (${profile.serviceUuid.take(8)}…)")
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
                    val socket = createSonyRfcommSocket(device)
                    btSocket = socket
                    socket.connect()

                    Log.d(tag, "Connected!")
                    status = Status.CONNECTED
                    broadcastStatus(status, "Connected")
                    updateNotification("Connected ✓")
                    currentSeq = 0       // Reset seq on every fresh connection
                    retries = 0          // Reset retry counter on success

                    // XM6 protocol handshake — required before the headphones accept 0x19 commands
                    Log.d(tag, "Handshake: protocol info")
                    sendFrame(byteArrayOf(0x00, 0x00))
                    delay(100L)
                    Log.d(tag, "Handshake: support functions")
                    sendFrame(byteArrayOf(0x06, 0x00))
                    delay(100L)
                    Log.d(tag, "Handshake: battery")
                    sendFrame(byteArrayOf(0x22, 0x00))
                    delay(100L)
                    Log.d(tag, "Handshake: XM6 ANC inquiry (${profile.ancInquiry.joinToString(" ") { "%02x".format(it) }})")
                    sendFrame(profile.ancInquiry)
                    delay(100L)
                    Log.d(tag, "Handshake: EQ")
                    sendFrame(byteArrayOf(0x56, 0x00))
                    delay(200L)

                    Log.d(tag, "Handshake complete — sending active ANC command")
                    sendCommandForPlaybackState()

                    // Periodic battery refresh (every 60s) + ANC state re-sync
                    refreshJob?.cancel()
                    refreshJob = launch {
                        while (isActive && btSocket != null) {
                            delay(60_000L)
                            if (!isActive || btSocket == null) break
                            sendFrame(byteArrayOf(0x22, 0x00))  // battery inquiry
                            delay(500L)
                            sendCommandForPlaybackState()
                        }
                    }

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
                                        sendAck(ackSeq)
                                        parseIncoming(frame)
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Log.w(tag, "BT error: IOException: ${e.message}")
                    }

                    // Socket closed — reconnect automatically
                    Log.d(tag, "Socket closed — reconnecting")
                    btSocket = null
                    status = Status.DISCONNECTED
                    broadcastStatus(status, "Disconnected")
                    updateNotification("Disconnected, reconnecting…")
                    triggerReconnect()
                    return@launch

                } catch (e: CancellationException) {
                    throw e  // Propagate cancellation
                } catch (e: Exception) {
                    Log.w(tag, "BT error: ${e.javaClass.simpleName}: ${e.message}")
                    status = Status.DISCONNECTED
                    broadcastStatus(status, "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")

                    if (!isActive) break

                    retries++
                    val delay = RETRY_DELAY_MS * (1L shl (retries - 1).coerceAtMost(4))
                    updateNotification("Reconnecting in ${delay / 1000}s…")
                    delay(delay)
                }
            }

            if (!isActive) {
                status = Status.DISCONNECTED
                broadcastStatus(status)
                updateNotification("Stopped")
            } else {
                status = Status.ERROR
                broadcastStatus(status, "All $MAX_RETRIES connection retries failed")
                updateNotification("Connection failed")
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
        }
    }

    // ---- Bluetooth connection helpers ----

    /**
     * Tries several RFCOMM socket-creation strategies.
     * Sony's UUID is often missing from the device SDP record on Android,
     * so we fall back to reflection-based channel 1.
     */
    private fun createSonyRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        // Try known Sony service UUIDs (in order: XM6, XM5) first
        for (uuidStr in HeadphoneProfile.allUuids) {
            val uuid = UUID.fromString(uuidStr)
            try {
                val s = device.createRfcommSocketToServiceRecord(uuid)
                Log.d(tag, "Socket created via UUID $uuidStr")
                return s
            } catch (e: IOException) {
                Log.d(tag, "UUID $uuidStr failed: ${e.message}")
            }
            try {
                val s = device.createInsecureRfcommSocketToServiceRecord(uuid)
                Log.d(tag, "Socket created via insecure UUID")
                return s
            } catch (e: IOException) {
                Log.d(tag, "Insecure UUID $uuidStr failed: ${e.message}")
            }
        }

        // Fallback: reflection with channel 1, 10, etc.
        val channels = intArrayOf(1, 10, 2, 3, 5, 15, 20)
        for (ch in channels) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                val s = method.invoke(device, ch) as BluetoothSocket
                Log.d(tag, "Socket created via reflection (channel $ch)")
                return s
            } catch (e: Exception) {
                Log.d(tag, "Reflection channel-$ch failed: ${e.message}")
            }
        }

        throw IOException("All RFCOMM socket strategies failed")
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

        // Wait a moment for headphone audio state to settle, then send
        scope.launch {
            delay(300L)  // brief settle, then send immediately
            sendCommandForPlaybackState()
        }
    }

    /** Sends NC when media is playing, configured ambient otherwise. */
    private fun sendCommandForPlaybackState() {
        val cmd = if (isMediaPlaying) {
            profile.ancOn(ambientLevel())
        } else {
            sonyAmbientCommand()
        }
        sendFrame(cmd)
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
    private fun sendFrame(payload: ByteArray) {
        val socket = btSocket ?: return
        try {
            val frame = SonyAncProtocol.buildFrame(currentSeq, payload)
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            Log.d(tag, "Frame ${payload.joinToString(" ") { "%02x".format(it) }} seq=$currentSeq")
            currentSeq = currentSeq xor 1

            // Track the mode we last commanded (for notification stats)
            if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x68) {
                currentModeName = profile.describe(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Frame send failed: ${e.message}")
            btSocket = null
            try { socket.close() } catch (_: Exception) {}
            triggerReconnect()
        }
    }

    /** Set an EQ preset. Command: 58 00 <presetID> 00, then re-query with 56 00. */
    private suspend fun setEQPreset(presetId: Int) {
        sendFrame(byteArrayOf(0x58, 0x00, presetId.toByte(), 0x00))
        delay(100L)
        sendFrame(byteArrayOf(0x56, 0x00))
    }

    /** Set custom EQ bands (10 bands for XM6: 31Hz–16kHz, range -6..+6, offset +6). */
    private suspend fun setCustomEQ(bands: IntArray) {
        val count = bands.size
        val offset = if (count == 10) 6 else 10
        var payload = byteArrayOf(0x58, 0x00, 0xA0.toByte(), count.toByte())
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
    private fun sendAck(seq: Int) {
        val socket = btSocket ?: return
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
        connectBluetooth(addr)
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

    private fun buildNotification(text: String): Notification {
        val toggleLabel: String
        val toggleIcon: Int
        if (autoPaused) {
            toggleLabel = "▶ Resume"
            toggleIcon = android.R.drawable.ic_media_play
        } else {
            toggleLabel = "⏸ Pause"
            toggleIcon = android.R.drawable.ic_media_pause
        }

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
        val ancOffIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_ANC_OFF },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Quick stats: battery + current mode
        val stats = buildString {
            if (autoPaused) {
                append("⏸ Auto-paused")
            } else {
                if (batteryPercent != null) append("🔋 $batteryPercent%")
                if (currentModeName != "—") {
                    if (isNotEmpty()) append(" · ")
                    append("🎧 $currentModeName")
                }
            }
        }

        val title = "Sony ANC Auto-Switch"
        val content = if (stats.isNotEmpty()) "$text — $stats" else text

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(0, "🔇 NC", ancOnIntent)
            .addAction(0, "🌬 Ambient", ambientIntent)
            .addAction(0, "⛔ Off", ancOffIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Race on early startup
        }
    }

    /** Refreshes the notification with current stats without changing the main text. */
    private fun refreshNotification() {
        // Reuse last text by rebuilding with stats (battery/mode changed)
        val base = when {
            autoPaused -> "⏸ Auto-paused"
            status == Status.CONNECTED -> if (isMediaPlaying) "▶ Playing" else "⏸ Paused"
            status == Status.CONNECTING -> "Connecting…"
            status == Status.DISCONNECTED -> "Disconnected"
            status == Status.ERROR -> "Error"
            else -> "—"
        }
        updateNotification(base)
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
        const val ACTION_ANC_OFF = "$PACKAGE.action.ANC_OFF"
        const val ACTION_TOGGLE_AUTO = "$PACKAGE.action.TOGGLE_AUTO"
        const val ACTION_SET_EQ = "$PACKAGE.action.SET_EQ"
        const val ACTION_SET_EQ_CUSTOM = "$PACKAGE.action.SET_EQ_CUSTOM"

        // Intent extras
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_EQ_PRESET = "eq_preset"
        const val EXTRA_EQ_BANDS = "eq_bands"
        const val EXTRA_EQ_ACTIVE_PRESET = "eq_active_preset"
        const val EXTRA_EQ_ACTIVE_BANDS = "eq_active_bands"

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
        const val KEY_ALLOWLIST_ENABLED = "allowlist_enabled"
        const val KEY_ALLOWLIST = "allowlist_apps"

        // Broadcast extras
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_MODE = "mode"

        // Reconnection
        private const val MAX_RETRIES = 20
        private const val RETRY_DELAY_MS = 3_000L
    }
}