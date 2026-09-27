package com.fencewatcher.sonyanc

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors audio playback state by polling [AudioManager.isMusicActive].
 *
 * Fast polling (200ms) catches play/pause transitions quickly.
 * Android 14 blocks [MediaSessionManager.getActiveSessions] so
 * polling is the only reliable cross-version approach.
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
                Log.d(tag, if (isPlaying) "▶ PLAYING" else "⏸ PAUSED")
                onPlaybackChanged(isPlaying)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (polling) return
        polling = true
        val initialPlaying = audioManager.isMusicActive
        lastReportedPlaying = initialPlaying
        Log.d(tag, "Initial: ${if (initialPlaying) "PLAYING" else "SILENT"}")
        onPlaybackChanged(initialPlaying)
        mainHandler.post(pollRunnable)
    }

    fun stop() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        lastReportedPlaying = null
    }

    val isAnyPlaying: Boolean
        get() = audioManager.isMusicActive

    companion object {
        private const val POLL_INTERVAL_MS = 200L
    }
}