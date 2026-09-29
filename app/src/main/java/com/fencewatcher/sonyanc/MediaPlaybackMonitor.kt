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
 * App allowlist: when [allowlistProvider] returns a non-empty set, playback
 * only counts if the active media package is in the set (e.g. Spotify yes,
 * TikTok no). Empty set = all apps trigger.
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val allowlistProvider: () -> Set<String>,
    private val onPlaybackChanged: (isPlaying: Boolean) -> Unit,
) {
    private val tag = "MediaMonitor"

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var lastReportedPlaying: Boolean? = null
    private var pendingCount = 0
    private var polling = false
    private var lastAttributionMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            // Re-derive which app is playing before deciding. Throttled because
            // each call is a binder round-trip enumerating every active session.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAttributionMs >= ATTRIBUTION_INTERVAL_MS) {
                lastAttributionMs = now
                MediaAppTracker.refreshHook?.invoke()
            }
            val rawPlaying = audioManager.isMusicActive
            val allowlist = allowlistProvider()
            // With an allowlist configured, attribution decides. `isMusicActive`
            // only reports audio the platform classifies as *music*, so gating the
            // real answer behind it (`rawPlaying && allowed`) let a weak signal veto
            // a correct one — a correctly identified Spotify session still showed
            // nothing happening whenever `isMusicActive` read false.
            val isPlaying = when {
                allowlist.isEmpty() -> rawPlaying
                else -> {
                    val playing = MediaAppTracker.playingPackages
                    when {
                        playing.isNotEmpty() -> playing.any { it in allowlist }
                        // Nothing attributable. Fall back to `isMusicActive` rather
                        // than guaranteeing silence, which is what made the filter
                        // look dead in the first place.
                        else -> {
                            if (rawPlaying) Log.w(tag, "no attributable app, but isMusicActive=true — allowing")
                            rawPlaying
                        }
                    }
                }
            }
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
        lastAttributionMs = 0L
        MediaAppTracker.refreshHook?.invoke()
    }

    val isAnyPlaying: Boolean get() = decide()

    /** Single decision path, so polling and on-demand reads can never disagree. */
    private fun decide(): Boolean {
        val allowlist = allowlistProvider()
        if (allowlist.isEmpty()) return audioManager.isMusicActive
        val playing = MediaAppTracker.playingPackages
        return if (playing.isNotEmpty()) playing.any { it in allowlist }
        else audioManager.isMusicActive
    }

    // ---- App allowlist ----


    companion object {
        private const val POLL_INTERVAL_MS = 200L
        /** How often to re-derive the playing app. Each call is a binder round-trip. */
        private const val ATTRIBUTION_INTERVAL_MS = 500L
        /** Require this many consecutive polls with the same state before reporting a change (≈1s). */
        private const val DEBOUNCE_COUNT = 5
    }
}