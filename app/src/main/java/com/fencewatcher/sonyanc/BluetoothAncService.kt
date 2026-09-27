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
                startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
                serviceInit(address)
            }

            ACTION_STOP -> {
                Log.d(tag, "Stopping service")
                stopSelf()
            }

            ACTION_GET_STATUS -> {
                // Responded via broadcast; just a trigger
                broadcastStatus(status)
            }
        }
        // Don't restart if killed; the user can restart via the UI
        return START_NOT_STICKY
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
                    val uuid = UUID.fromString(SERVICE_UUID)

                    // Close stale socket
                    try {
                        btSocket?.close()
                    } catch (_: Exception) {}
                    btSocket = null

                    Log.d(tag, "Connecting to ${device.name ?: "?"} ($address) via RFCOMM…")
                    status = Status.CONNECTING
                    broadcastStatus(status)
                    updateNotification("Connecting…")

                    val socket = device.createRfcommSocketToServiceRecord(uuid)
                    btSocket = socket
                    socket.connect()

                    Log.d(tag, "Connected!")
                    status = Status.CONNECTED
                    broadcastStatus(status)
                    updateNotification("Connected ✓")
                    currentSeq = 0       // Reset seq on every fresh connection
                    retries = 0          // Reset retry counter on success

                    // If media is already playing, send ANC now
                    if (isMediaPlaying) {
                        sendAncCommand(SonyAncProtocol.ANC_ON)
                    }

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
                    Log.w(tag, "BT error: ${e.message}")
                    status = Status.DISCONNECTED
                    broadcastStatus(status)

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
                broadcastStatus(status)
                updateNotification("Connection failed")
            }
        }
    }

    // ---- Media playback callback ----

    private fun onMediaStateChanged(playing: Boolean) {
        if (playing == isMediaPlaying) return  // No change
        isMediaPlaying = playing
        Log.d(tag, "Media ${if (playing) "playing" else "paused"}")
        updateNotification(if (playing) "▶ Playing" else "⏸ Paused")

        // Send command directly (coroutine-safe BT write)
        scope.launch {
            if (playing) {
                sendAncCommand(SonyAncProtocol.ANC_ON)
            } else {
                sendAncCommand(SonyAncProtocol.AMBIENT)
            }
        }
    }

    // ---- ANC command send ----

    private fun sendAncCommand(payload: ByteArray) {
        val socket = btSocket ?: return
        try {
            val frame = SonyAncProtocol.buildFrame(currentSeq, payload)
            socket.outputStream.write(frame)
            socket.outputStream.flush()
            Log.d(tag, "Sent ANC: ${payload.firstOrNull()?.let { SonyAncProtocol.describePayload(payload) }}")
            currentSeq = currentSeq xor 1  // Toggle seq bit
        } catch (e: Exception) {
            Log.w(tag, "Write failed: ${e.message}")
            // Connection lost — the read loop will detect this and trigger reconnect
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

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BluetoothAncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sony ANC Auto-Switch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play) // built-in icon
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
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

    private fun broadcastStatus(s: Status) {
        val intent = Intent(STATUS_BROADCAST).apply {
            putExtra(EXTRA_STATUS, s.name)
            putExtra(EXTRA_DEVICE, deviceAddress)
            `package` = packageName // explicit app-local broadcast
        }
        try {
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // ---- Companion / constants ----

    companion object {
        const val TAG = "BTAncSvc"

        // Service UUID from Gadgetbridge reverse-engineering
        const val SERVICE_UUID = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

        // Intent actions
        const val ACTION_START = "$PACKAGE.action.START"
        const val ACTION_STOP = "$PACKAGE.action.STOP"
        const val ACTION_GET_STATUS = "$PACKAGE.action.GET_STATUS"

        // Intent extras
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE = "device"

        // Status broadcast
        const val STATUS_BROADCAST = "$PACKAGE.STATUS"

        // Notification
        const val CHANNEL_ID = "sony_anc_service"
        const val NOTIFICATION_ID = 1

        // Reconnection
        private const val MAX_RETRIES = 20
        private const val RETRY_DELAY_MS = 3_000L
        private const val PACKAGE = "com.fencewatcher.sonyanc"
    }
}