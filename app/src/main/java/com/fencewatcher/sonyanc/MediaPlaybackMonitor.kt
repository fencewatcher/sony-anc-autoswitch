package com.fencewatcher.sonyanc

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors audio playback state using [AudioManager.isMusicActive]
 * and (on API 26+) [AudioManager.registerAudioPlaybackCallback].
 *
 * Dual approach: immediate callbacks when available + fast polling as
 * fallback. Android 14 blocks [MediaSessionManager.getActiveSessions]
 * but the [AudioPlaybackCallback] should still work for detecting
 * when any app begins or ceases audio playback.
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

    // ---- Immediate callback via AudioPlaybackCallback (API 26+) ----

    private val playbackCallback = if (Build.VERSION.SDK_INT >= 26) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                val isActive = configs.any {
                    it.getActiveState() == AudioPlaybackConfiguration.ACTIVE_STATE_ACTIVE
                }
                if (isActive != lastReportedPlaying) {
                    Log.d(tag, "AudioPlaybackCallback: ${if (isActive) "PLAYING" else "PAUSED"}")
                    reportChange(isActive)
                }
            }
        }
    } else null

    // ---- Fallback polling loop ----

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val isPlaying = audioManager.isMusicActive
            if (isPlaying != lastReportedPlaying) {
                Log.d(tag, "Poll: ${if (isPlaying) "PLAYING" else "PAUSED"}")
                reportChange(isPlaying)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---- Public API ----

    fun start() {
        if (polling) return
        polling = true

        // Register callback (API 26+)
        playbackCallback?.let {
            try {
                audioManager.registerAudioPlaybackCallback(it, mainHandler)
                Log.d(tag, "AudioPlaybackCallback registered")
            } catch (e: Exception) {
                Log.w(tag, "AudioPlaybackCallback failed: ${e.message}")
            }
        }

        // Fire initial state
        val initialPlaying = audioManager.isMusicActive
        lastReportedPlaying = initialPlaying
        Log.d(tag, "Initial: ${if (initialPlaying) "PLAYING" else "SILENT"}")
        onPlaybackChanged(initialPlaying)

        // Start polling as backup
        mainHandler.post(pollRunnable)
    }

    fun stop() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        playbackCallback?.let {
            try {
                audioManager.unregisterAudioPlaybackCallback(it)
            } catch (_: Exception) {}
        }
        lastReportedPlaying = null
    }

    val isAnyPlaying: Boolean
        get() = audioManager.isMusicActive

    // ---- Internal ----

    private fun reportChange(playing: Boolean) {
        lastReportedPlaying = playing
        onPlaybackChanged(playing)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}