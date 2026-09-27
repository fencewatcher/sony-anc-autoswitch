package com.fencewatcher.sonyanc

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors audio playback state using [AudioManager.isMusicActive].
 *
 * Android 14 blocks [MediaSessionManager.getActiveSessions] for
 * non-privileged apps (requires `MEDIA_CONTENT_CONTROL` signature
 * permission), so we fall back to polling audio state instead.
 *
 * Polls every 1s — fast enough for ANC toggling, no special permissions.
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val onPlaybackChanged: (isPlaying: Boolean) -> Unit,
) {
    private val tag = "MediaMonitor"

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastReportedPlaying: Boolean? = null
    private var polling = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val isPlaying = audioManager.isMusicActive
            if (isPlaying != lastReportedPlaying) {
                lastReportedPlaying = isPlaying
                Log.d(tag, "Audio ${if (isPlaying) "▶ PLAYING" else "⏸ PAUSED"}")
                onPlaybackChanged(isPlaying)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---- public API ----

    /** Start monitoring. */
    fun start() {
        if (polling) return
        polling = true
        // Fire initial state immediately
        val initialPlaying = audioManager.isMusicActive
        lastReportedPlaying = initialPlaying
        Log.d(tag, "Initial audio state: ${if (initialPlaying) "PLAYING" else "SILENT"}")
        onPlaybackChanged(initialPlaying)
        // Start polling loop
        mainHandler.post(pollRunnable)
    }

    /** Stop monitoring. */
    fun stop() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        lastReportedPlaying = null
    }

    /** Current snapshot. */
    val isAnyPlaying: Boolean
        get() = audioManager.isMusicActive

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}