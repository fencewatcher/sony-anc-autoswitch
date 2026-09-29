package com.fencewatcher.sonyanc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_CLEAR_FRAME_LOG
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_GET_FRAME_LOG
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.ACTION_SEND_RAW
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.EXTRA_FRAME_LOG
import com.fencewatcher.sonyanc.BluetoothAncService.Companion.FRAME_LOG_BROADCAST

/**
 * Hidden diagnostics, reached by tapping the headphone image five times.
 *
 * This exists because SENSE and the LDAC status read both draw silence from the
 * XM6. Every attempt so far has been a guess at a type byte, checked against the
 * reference implementation and shipped; that is a slow way to reverse a protocol.
 * This screen shows the actual traffic and lets a payload be sent by hand, so the
 * next question is answered by the device rather than by another release.
 *
 * Everything here is read or inject-only against the same socket the service
 * already owns — it deliberately does not reach around the service, so the
 * stop-and-wait sequencing and ACK handling stay in one place.
 */
class DebugActivity : android.app.Activity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var hexInput: EditText
    private lateinit var t2Toggle: CheckBox

    private var registered = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra(EXTRA_FRAME_LOG) ?: return
            logView.text = log.ifBlank { "no frames yet" }
            // Pin to the newest entry so a long capture does not need scrolling by hand.
            logView.post {
                (logView.parent as? android.widget.ScrollView)?.let { it.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        logView = findViewById(R.id.textFrameLog)
        statusView = findViewById(R.id.textStatus)
        hexInput = findViewById(R.id.editHex)
        t2Toggle = findViewById(R.id.chkT2)

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = hexInput.text.toString().trim()
            if (text.isEmpty()) {
                statusView.text = "Enter a payload first"
                return@setOnClickListener
            }
            sendToService(BluetoothAncService.ACTION_SEND_RAW) {
                putExtra("hex", text)
                putExtra("t2", t2Toggle.isChecked)
            }
            statusView.text = "Sent: $text"
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            sendToService(ACTION_CLEAR_FRAME_LOG)
            logView.text = "no frames yet"
            statusView.text = "Cleared"
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = logView.text.toString()
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("frames", text))
            statusView.text = "Copied ${text.length} chars"
            Toast.makeText(this, "Frame log copied", Toast.LENGTH_SHORT).show()
        }

        statusView.text = "Connected: no"
    }

    override fun onResume() {
        super.onResume()
        // These are package-private broadcasts, so the filter does not leak the log
        // to other apps even though the values are not sensitive.
        // targetSdk 34 requires an export flag on registerReceiver, or this throws
        // SecurityException the moment the screen opens. Matched to MainActivity.
        registerReceiver(logReceiver, IntentFilter(FRAME_LOG_BROADCAST),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
        registered = true
        // Ask for whatever has already been captured this session — fetching must not
        // clear, or opening the menu would throw away the very frames being debugged.
        sendToService(ACTION_GET_FRAME_LOG)
    }

    override fun onPause() {
        if (registered) {
            unregisterReceiver(logReceiver)
            registered = false
        }
        super.onPause()
    }

    private fun sendToService(action: String, block: android.content.Intent.() -> Unit = {}) {
        val intent = Intent(this, BluetoothAncService::class.java).apply {
            this.action = action
            block()
        }
        runCatching { startService(intent) }
            .onFailure { statusView.text = "Service not running — connect the headphones first" }
    }
}
