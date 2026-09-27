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
 * Foreground service that connects to Sony WH-1000XM5/XM6 headphones
 * over Bluetooth RFCOMM and sends ANC commands based on media playback state.
 *
 * Protocol: MDR framing on RFCOMM, UUID 956c7b26-…
 *
 * Lifecycle:
 *   Start via [startService] with action=[ACTION_START], extra=[EXTRA_ADDRESS]
 *   Stop via [startService] with action=[ACTION_STOP]
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
                            stopSelf()
                            return START_NOT_STICKY
                        }
                    deviceAddress = address
                    Log.d(tag, "Starting service, device=$address")
                    safeStartForeground()
                    serviceInit(address)
                }

                ACTION_STOP -> {
                    Log.d(tag, "Stopping service")
                    stopSelf()
                }

                ACTION_GET_STATUS -> {
                    broadcastStatus(status)
                }

                ACTION_ANC_ON -> {
                    Log.d(tag, "Debug: manual ANC ON")
                    isMediaPlaying = true
                    scope.launch { sendAncCommandReliable(SonyAncProtocol.ANC_ON) }
                }

                ACTION_AMBIENT -> {
                    Log.d(tag, "Debug: manual AMBIENT")
                    isMediaPlaying = false
                    scope.launch { sendAncCommandReliable(SonyAncProtocol.AMBIENT) }
                }

                ACTION_ANC_OFF -> {
                    Log.d(tag, "Debug: manual ANC OFF")
                    scope.launch { sendAncCommandReliable(SonyAncProtocol.ANC_OFF) }
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
        mediaMonitor?.stop()
        mediaMonitor = null
        // Close socket first to unblock the read() loop in the coroutine
        try {
            btSocket?.close()
        } catch (_: Exception) {}
        connectionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Initialisation ----

    private fun serviceInit(address: String) {
        status = Status.CONNECTING
        broadcastStatus(status)
        updateNotification("Connecting…")

        // Start media playback monitoring immediately
        mediaMonitor = MediaPlaybackMonitor(this) { playing ->
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

                    // Close stale socket
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
                    Thread.sleep(100L)
                    Log.d(tag, "Handshake: support functions")
                    sendFrame(byteArrayOf(0x06, 0x00))
                    Thread.sleep(100L)
                    Log.d(tag, "Handshake: battery")
                    sendFrame(byteArrayOf(0x22, 0x00))
                    Thread.sleep(100L)
                    Log.d(tag, "Handshake: XM6 ANC inquiry (0x66 0x19)")
                    sendFrame(byteArrayOf(0x66, 0x19))
                    Thread.sleep(100L)
                    Log.d(tag, "Handshake: EQ")
                    sendFrame(byteArrayOf(0x56, 0x00))
                    Thread.sleep(200L)

                    Log.d(tag, "Handshake complete — sending active ANC command")
                    val initialCmd = if (isMediaPlaying) SonyAncProtocol.ANC_ON else SonyAncProtocol.AMBIENT
                    sendAncCommandReliable(initialCmd)

                    // Blocking read loop — any data or -1 / IOException = disconnected
                    val inputStream = socket.inputStream
                    val buffer = ByteArray(1024)
                    while (isActive) {
                        val n = inputStream.read(buffer)
                        if (n == -1) break // EOF = orderly disconnect
                        // We don't parse responses for v1; just detect the socket drop
                    }

                    // If we got here the socket closed cleanly
                    Log.d(tag, "Socket closed")
                    status = Status.DISCONNECTED
                    broadcastStatus(status)
                    updateNotification("Disconnected")

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

    // ---- Bluetooth connection helpers ----

    /**
     * Tries several RFCOMM socket-creation strategies.
     * Sony's UUID is often missing from the device SDP record on Android,
     * so we fall back to reflection-based channel 1.
     */
    private fun createSonyRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val uuid = UUID.fromString(SERVICE_UUID)

        // Strategy 1: standard UUID lookup (works on some phones)
        try {
            val s = device.createRfcommSocketToServiceRecord(uuid)
            Log.d(tag, "Socket created via UUID")
            return s
        } catch (e: IOException) {
            Log.w(tag, "UUID socket failed: ${e.message}")
        }

        // Strategy 2: insecure UUID variant (helps on some Samsung/OnePlus)
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(uuid)
            Log.d(tag, "Socket created via insecure UUID")
            return s
        } catch (e: IOException) {
            Log.w(tag, "Insecure UUID socket failed: ${e.message}")
        }

        // Strategy 3: reflection — createRfcommSocket(channel) with channel 1
        // This is the Sony-recommended fallback used by Gadgetbridge
        try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket", Int::class.java
            )
            val s = method.invoke(device, 1) as BluetoothSocket
            Log.d(tag, "Socket created via reflection (channel 1)")
            return s
        } catch (e: Exception) {
            Log.w(tag, "Reflection socket failed: ${e.message}")
        }

        // Strategy 4: try reflection with channel 10 (some devices use this)
        try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket", Int::class.java
            )
            val s = method.invoke(device, 10) as BluetoothSocket
            Log.d(tag, "Socket created via reflection (channel 10)")
            return s
        } catch (e: Exception) {
            Log.w(tag, "Reflection channel-10 failed: ${e.message}")
        }

        // Strategy 5: try reflection with channel 2, 3, 5, 15, 20
        val extraChannels = intArrayOf(2, 3, 5, 15, 20)
        for (ch in extraChannels) {
            try {
                val method = device.javaClass.getMethod(
                    "createRfcommSocket", Int::class.java
                )
                val s = method.invoke(device, ch) as BluetoothSocket
                Log.d(tag, "Socket created via reflection (channel $ch)")
                return s
            } catch (e: Exception) {
                Log.w(tag, "Reflection channel-$ch failed: ${e.message}")
            }
        }

        throw IOException("All RFCOMM socket strategies failed")
    }

    // ---- Media playback callback ----

    private fun onMediaStateChanged(playing: Boolean) {
        if (playing == isMediaPlaying) return  // No change
        isMediaPlaying = playing
        Log.d(tag, "Media ${if (playing) "playing" else "paused"}")
        updateNotification(if (playing) "▶ Playing" else "⏸ Paused")

        // Wait a moment for headphone audio state to settle, then send
        scope.launch {
            delay(300L)  // brief settle, then send immediately
            val cmd = if (playing) SonyAncProtocol.ANC_ON else SonyAncProtocol.AMBIENT
            sendAncCommandReliable(cmd)
        }
    }

    /**
     * Sends an ANC command twice with ALTERNATING seq bits so both
     * attempts are independently processed by the headphones.
     *
     * If the first frame is received but not applied, the second
     * arrives on a new seq and gets processed as a fresh command.
     * If the first was applied, the second applies the same command
     * again (harmless idempotent apply).
     *
     * After both sends, [currentSeq] is restored to the original
     * value so the next call alternates correctly from the last
     * seq the headphones saw.
     */
    private suspend fun sendAncCommandReliable(payload: ByteArray) {
        val firstSeq = currentSeq
        val name = when {
            payload.contentEquals(SonyAncProtocol.ANC_ON) -> "ANC_ON"
            payload.contentEquals(SonyAncProtocol.AMBIENT) -> "AMBIENT"
            payload.contentEquals(SonyAncProtocol.ANC_OFF) -> "ANC_OFF"
            else -> "CUSTOM"
        }
        // Send 5 rapid bursts with alternating seq so every frame is new
        val passes = listOf(firstSeq, firstSeq xor 1, firstSeq, firstSeq xor 1, firstSeq)
        for ((i, seq) in passes.withIndex()) {
            if (btSocket == null) break
            try {
                val frame = SonyAncProtocol.buildFrame(seq, payload)
                btSocket?.outputStream?.write(frame)
                btSocket?.outputStream?.flush()
                Log.d(tag, "Sent $name seq=$seq (burst ${i + 1}) — ${
                    payload.joinToString(" ") { "%02x".format(it) }
                }")
            } catch (e: Exception) {
                Log.w(tag, "$name burst ${i + 1} failed: ${e.message}")
                btSocket = null
                try { btSocket?.close() } catch (_: Exception) {}
                triggerReconnect()
                return
            }
            if (i < passes.size - 1) Thread.sleep(200L)
        }
        currentSeq = passes.last().xor(1)
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
        } catch (e: Exception) {
            Log.w(tag, "Frame send failed: ${e.message}")
            btSocket = null
            try { socket.close() } catch (_: Exception) {}
            triggerReconnect()
        }
    }

    /**
     * Legacy single-send kept for initial connection flow.
     */
    private fun sendAncCommand(payload: ByteArray) {
        val socket = btSocket ?: return
        try {
            val name = when {
                payload.contentEquals(SonyAncProtocol.ANC_ON) -> "ANC_ON"
                payload.contentEquals(SonyAncProtocol.AMBIENT) -> "AMBIENT"
                payload.contentEquals(SonyAncProtocol.ANC_OFF) -> "ANC_OFF"
                else -> "CUSTOM"
            }
            val frame = SonyAncProtocol.buildFrame(currentSeq, payload)
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            Log.d(tag, "Sent $name seq=$currentSeq (single)")
            currentSeq = currentSeq xor 1
        } catch (e: Exception) {
            Log.w(tag, "Write failed: ${e.message} — triggering reconnect")
            btSocket = null
            try { socket.close() } catch (_: Exception) {}
            triggerReconnect()
        }
    }

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
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_STOP },
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sony ANC Auto-Switch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
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

    // ---- Status broadcasting ----

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    private fun broadcastStatus(s: Status, message: String? = null) {
        val intent = Intent(STATUS_BROADCAST).apply {
            putExtra(EXTRA_STATUS, s.name)
            putExtra(EXTRA_DEVICE, deviceAddress)
            if (message != null) putExtra(EXTRA_MESSAGE, message)
            `package` = packageName
        }
        try {
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ---- Companion / constants ----

    companion object {
        const val TAG = "BTAncSvc"
        const val PACKAGE = "com.fencewatcher.sonyanc"

        // Service UUID from Gadgetbridge reverse-engineering
        const val SERVICE_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

        // Intent actions
        const val ACTION_START = "$PACKAGE.action.START"
        const val ACTION_STOP = "$PACKAGE.action.STOP"
        const val ACTION_GET_STATUS = "$PACKAGE.action.GET_STATUS"
        const val ACTION_ANC_ON = "$PACKAGE.action.ANC_ON"
        const val ACTION_AMBIENT = "$PACKAGE.action.AMBIENT"
        const val ACTION_ANC_OFF = "$PACKAGE.action.ANC_OFF"

        // Intent extras
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_MESSAGE = "message"

        // Status broadcast
        const val STATUS_BROADCAST = "$PACKAGE.STATUS"

        // Notification
        const val CHANNEL_ID = "sony_anc_service"
        const val NOTIFICATION_ID = 1

        // Reconnection
        private const val MAX_RETRIES = 20
        private const val RETRY_DELAY_MS = 3_000L
    }
}