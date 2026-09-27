package com.fencewatcher.sonyanc

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors all active MediaSessions and fires [onPlaybackChanged] whenever
 * the global "any media is playing" state transitions.
 *
 * Tracks every active MediaController individually. The callback fires when
 * the aggregate state (any playing / nothing playing) flips.
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val onPlaybackChanged: (isPlaying: Boolean) -> Unit,
) {
    private val tag = "MediaMonitor"

    private val sessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    // Map each watched controller to its current playing state
    private val controllers = HashMap<MediaController, Boolean>()

    // Tracks the last reported aggregate so we don't spam duplicate events
    private var lastReportedPlaying: Boolean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- listener for session changes ----

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            synchronized(controllers) {
                // Build a set of current tokens for fast lookup
                val currentTokens = sessions?.map { it.sessionToken }?.toSet() ?: emptySet()

                // Remove controllers that disappeared
                val toRemove = controllers.keys.filter { it.sessionToken !in currentTokens }
                for (ctrl in toRemove) {
                    removeController(ctrl)
                }

                // Add new controllers
                sessions?.forEach { controller ->
                    if (controller !in controllers) {
                        addController(controller)
                    }
                }
            }
            reevaluate()
        }

    // ---- per-controller callback ----

    private inner class PlaybackCallback(
        private val controller: MediaController,
    ) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            synchronized(controllers) {
                controllers[controller] = playing
            }
            mainHandler.post { reevaluate() }
        }

        override fun onSessionDestroyed() {
            synchronized(controllers) {
                removeController(controller)
            }
            mainHandler.post { reevaluate() }
        }
    }

    // maps controller -> its callback instance for cleanup
    private val callbacks = HashMap<MediaController, PlaybackCallback>()

    private fun addController(controller: MediaController) {
        if (controller in controllers) return
        val cb = PlaybackCallback(controller)
        controllers[controller] = false
        callbacks[controller] = cb
        controller.registerCallback(cb, mainHandler)
    }

    private fun removeController(controller: MediaController) {
        callbacks.remove(controller)?.let { cb ->
            controller.unregisterCallback(cb)
        }
        controllers.remove(controller)
    }

    // ---- public API ----

    /** Start monitoring. Call from main thread. */
    fun start() {
        val sessions = sessionManager.getActiveSessions(null)
        synchronized(controllers) {
            controllers.clear()
            sessions?.forEach { addController(it) }
        }
        sessionManager.addOnActiveSessionsChangedListener(sessionListener, null, mainHandler)
        reevaluate()
    }

    /** Stop monitoring and release all controllers. */
    fun stop() {
        sessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        synchronized(controllers) {
            val all = controllers.keys.toList()
            for (ctrl in all) {
                removeController(ctrl)
            }
        }
        lastReportedPlaying = null
    }

    /** Current snapshot: is any media session in PLAYING state? */
    val isAnyPlaying: Boolean
        get() = synchronized(controllers) {
            controllers.values.any { it }
        }

    // ---- aggregate evaluation ----

    private fun reevaluate() {
        val anyPlaying: Boolean
        synchronized(controllers) {
            anyPlaying = controllers.values.any { it }
        }
        if (anyPlaying != lastReportedPlaying) {
            lastReportedPlaying = anyPlaying
            Log.d(tag, "Playback: ${if (anyPlaying) "▶ PLAYING" else "⏸ PAUSED"}")
            onPlaybackChanged(anyPlaying)
        }
    }
}