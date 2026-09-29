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
 * Android 14 blocks [MediaSessionManager.getActiveSessions] so the
 * active-package detection uses [AudioManager.getActivePlaybackConfigurations]
 * with a UID → package lookup instead.
 *
 * App filtering was removed: see [decide].
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val onPlaybackChanged: (isPlaying: Boolean) -> Unit,
) {
    private val tag = "MediaMonitor"

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var lastReportedPlaying: Boolean? = null
    private var pendingCount = 0
    private var polling = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val isPlaying = audioManager.isMusicActive
            if (isPlaying != lastReportedPlaying) {
                pendingCount++
                if (pendingCount >= DEBOUNCE_COUNT) {
                    pendingCount = 0
                    lastReportedPlaying = isPlaying
                    Log.d(tag, if (isPlaying) "▶ PLAYING (stable)" else "⏸ PAUSED (stable)")
                    onPlaybackChanged(isPlaying)
                }
            } else {
                pendingCount = 0
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (polling) return
        polling = true
        val initialPlaying = decide()
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

    val isAnyPlaying: Boolean get() = audioManager.isMusicActive

    /**
     * App filtering was removed.
     *
     * It depended on notification-listener access to attribute playback to an app,
     * and the whole path was invisible when that permission was missing — a filter
     * that looked enabled but could never fire. Rather than keep a feature whose
     * hard dependency is a system permission toggle the user can silently lose on
     * reinstall, triggering is now based purely on whether audio is playing.
     */
    private fun decide(): Boolean = audioManager.isMusicActive

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        /** Require this many consecutive polls with the same state before reporting a change (≈1s). */
        private const val DEBOUNCE_COUNT = 5
    }
}