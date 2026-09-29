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
            val isPlaying = rawPlaying && isAllowedByAllowlist()
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
        val initialPlaying = audioManager.isMusicActive && isAllowedByAllowlist()
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

    val isAnyPlaying: Boolean
        get() = audioManager.isMusicActive && isAllowedByAllowlist()

    // ---- App allowlist ----

    /**
     * Allowlist check based on *real* playback state.
     *
     * The previous implementation fell back to the default behaviour most of
     * the time, for three separate reasons:
     *
     *  1. `AudioPlaybackConfiguration.getClientUid()` is a hidden API. Non-SDK
     *     interface restrictions have blocked that reflection since Android 9,
     *     so it always returned -1 and the "most accurate" path never ran.
     *  2. It treated "has a media notification" as "is playing" — but Spotify
     *     keeps its media notification posted while paused, so a paused app
     *     still counted as active. This also made ANC trigger spuriously.
     *  3. With several apps tracked it required *all* of them to be allowed,
     *     so one lingering notification from a non-allowlisted app blocked
     *     everything.
     *
     * [MediaAppTracker] now derives the playing set from real
     * `MediaSession` `STATE_PLAYING` through the bound notification listener,
     * which is accurate on every supported version and event-driven.
     */
    private fun isAllowedByAllowlist(): Boolean {
        val allowlist = allowlistProvider()
        if (allowlist.isEmpty()) return true  // no filtering

        if (!MediaAppTracker.trackingEnabled) {
            // Without a bound listener we cannot attribute playback to any app.
            // This used to allow, which is why the allowlist looked installed but
            // never filtered anything: the failure mode was indistinguishable
            // from "no filter". Deny instead, and make the reason loud.
            Log.w(tag, "tier=deny — allowlist set but notification access is not granted")
            return false
        }

        val playing = MediaAppTracker.playingPackages
        val ok = MediaAppTracker.isAllowed(allowlist)
        Log.d(tag, "playing=$playing allow=$allowlist allowed=$ok")
        return ok
    }

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        /** How often to re-derive the playing app. Each call is a binder round-trip. */
        private const val ATTRIBUTION_INTERVAL_MS = 500L
        /** Require this many consecutive polls with the same state before reporting a change (≈1s). */
        private const val DEBOUNCE_COUNT = 5
    }
}