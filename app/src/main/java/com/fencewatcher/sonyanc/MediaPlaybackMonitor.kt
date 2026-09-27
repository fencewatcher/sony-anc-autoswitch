package com.fencewatcher.sonyanc

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
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

    private var lastReportedPlaying: Boolean? = null
    private var polling = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val rawPlaying = audioManager.isMusicActive
            val isPlaying = rawPlaying && isAllowedByAllowlist()
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
    }

    val isAnyPlaying: Boolean
        get() = audioManager.isMusicActive && isAllowedByAllowlist()

    // ---- App allowlist ----

    private fun isAllowedByAllowlist(): Boolean {
        val allowlist = allowlistProvider()
        if (allowlist.isEmpty()) return true  // no filtering

        // Priority 1: currently-playing via UID (most accurate)
        val current = currentlyPlayingPackage()
        if (current != null) {
            Log.d(tag, "Active player UID → $current | allowlist: $allowlist")
            return current in allowlist
        }

        // Priority 2: notification-tracked packages
        val active = activeMediaPackages()
        Log.d(tag, "Notification-tracked packages: $active | allowlist: $allowlist")

        if (active.isNotEmpty()) {
            if (active.size == 1) return active.first() in allowlist
            // Multiple tracked — require ALL in allowlist (conservative when ambiguous)
            return active.all { it in allowlist }
        }

        // All detection failed (UID blocked on 14+, notif not granted/not posted yet).
        // Default to ALLOW so the core feature works. The user can verify notif access
        // is enabled for proper filtering.
        Log.w(tag, "Cannot detect active app — defaulting to ALLOW. Grant notification access for filtering.")
        return true
    }

    /**
     * Uses AudioManager.activePlaybackConfigurations + UID to find the
     * app that is *actually producing audio right now* (not just having a
     * lingering notification). This is the most accurate source.
     */
    private fun currentlyPlayingPackage(): String? {
        try {
            val configs = audioManager.activePlaybackConfigurations
            for (cfg in configs) {
                val usage = cfg.audioAttributes.usage
                if (usage != android.media.AudioAttributes.USAGE_MEDIA) continue
                val uid = playbackConfigUid(cfg)
                if (uid > 0) {
                    context.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "currentlyPlayingPackage failed: ${e.message}")
        }
        return null
    }

    /**
     * Determines which app is currently producing media audio.
     * Primary source: MediaAppTracker (notification listener, works on all
     * Android versions incl. 14+). Falls back to best-effort APIs when the
     * notification listener isn't enabled.
     */
    private fun activeMediaPackages(): Set<String> {
        val packages = mutableSetOf<String>()

        // Primary: notification-listener tracking (cross-version reliable)
        MediaAppTracker.updateTrackingState(context)
        if (MediaAppTracker.trackingEnabled) {
            packages.addAll(MediaAppTracker.currentPackages())
            if (packages.isNotEmpty()) return packages
        }

        // Fallback: UID of active playback configurations → package name
        try {
            val configs = audioManager.activePlaybackConfigurations
            for (cfg in configs) {
                val usage = cfg.audioAttributes.usage
                val isMedia = usage == android.media.AudioAttributes.USAGE_MEDIA
                if (!isMedia) continue
                val uid = playbackConfigUid(cfg)
                if (uid > 0) {
                    val pkgs = context.packageManager.getPackagesForUid(uid)
                    if (pkgs != null) packages.addAll(pkgs)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "activePlaybackConfigurations failed: ${e.message}")
        }

        // Fallback 2 (pre-Android 14): MediaSessionManager
        if (packages.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val sessions = msm.getActiveSessions(null)
                for (session in sessions) {
                    session.packageName?.let { packages.add(it) }
                }
            } catch (e: Exception) {
                Log.w(tag, "MediaSessionManager failed: ${e.message}")
            }
        }

        return packages
    }

    /** getClientUid() isn't public API; retrieve it reflectively. */
    private fun playbackConfigUid(cfg: android.media.AudioPlaybackConfiguration): Int {
        return try {
            val m = android.media.AudioPlaybackConfiguration::class.java
                .getMethod("getClientUid")
            m.invoke(cfg) as Int
        } catch (_: Exception) {
            -1
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 200L
    }
}