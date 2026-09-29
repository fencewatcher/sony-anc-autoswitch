package com.fencewatcher.sonyanc

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Answers "which app is actually playing audio right now?".
 *
 * Why this is not the obvious `MediaSessionManager.getActiveSessions(null)`:
 * on Android 14+ that returns nothing to a normal app, and the usual workaround —
 * reflecting `AudioPlaybackConfiguration.getClientUid()` — is a **hidden API**
 * that has been blocked by non-SDK interface restrictions since Android 9, so it
 * silently yields -1.
 *
 * The supported route is to be a bound [NotificationListenerService] and call
 * `getActiveSessions()` with *this listener's own* ComponentName. A bound
 * listener is a trusted caller, so this works on every version including 14+.
 * That yields real [MediaController] objects, which gives two things the old
 * notification-watching approach never could:
 *
 *  1. **Real playback state.** A paused Spotify keeps its media notification
 *     posted, so "has a media notification" reported paused apps as playing.
 *     `PlaybackState.STATE_PLAYING` does not.
 *  2. **Events instead of polling.** Session callbacks fire on transition, so
 *     detection is instant and costs no wakeups.
 *
 * The notification map is kept only as a last-resort hint for the UI.
 */
object MediaAppTracker {
    private const val TAG = "MediaAppTracker"

    /** True once the system has actually bound our listener. */
    @Volatile
    var trackingEnabled = false

    /** Packages whose media session is currently in STATE_PLAYING. */
    @Volatile
    var playingPackages: Set<String> = emptySet()
        private set

    /** Convenience: the single playing package when there is exactly one. */
    val playingPackage: String? get() = playingPackages.singleOrNull()

    private val activeNotifications = LinkedHashMap<String, Long>()

    @Synchronized
    internal fun updatePlaying(pkgs: Set<String>) {
        if (pkgs != playingPackages) {
            Log.d(TAG, "playing → $pkgs")
        }
        playingPackages = pkgs
    }

    @Synchronized
    fun onPosted(sbn: android.service.notification.StatusBarNotification) {
        val n = sbn.notification
        val isMedia = n.category == android.app.Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
        if (isMedia) activeNotifications[sbn.packageName] = System.currentTimeMillis()
    }

    @Synchronized
    fun onRemoved(sbn: android.service.notification.StatusBarNotification) {
        val n = sbn.notification
        val isMedia = n.category == android.app.Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
        if (isMedia) activeNotifications.remove(sbn.packageName)
    }

    /** Packages that currently have a media notification (hint only, not playback state). */
    @Synchronized
    fun notifiedPackages(): Set<String> = activeNotifications.keys.toSet()

    /**
     * Is this playback event allowed by the user's allowlist?
     *
     * Empty allowlist means "any app may trigger". When the allowlist is set but
     * we cannot attribute the playback, we allow rather than silently disabling
     * the headline feature — same policy as before, but now that fallback is
     * rare instead of constant.
     */
    fun isAllowed(allowlist: Set<String>): Boolean {
        if (allowlist.isEmpty()) return true
        val playing = playingPackages
        if (playing.isEmpty()) {
            Log.w(TAG, "Allowlist set but no playing package known — allowing")
            return true
        }
        return playing.any { it in allowlist }
    }

    fun reset() {
        playingPackages = emptySet()
        activeNotifications.clear()
    }
}

/**
 * Binds to the system and maintains the live set of playing media sessions.
 *
 * The listener also owns [MediaAppTracker] state, so playback attribution works
 * without polling from anywhere else.
 */
class MediaNotificationListener : NotificationListenerService() {

    private val callbacks = HashMap<String, MediaController.Callback>()
    private val self = ComponentName(this, MediaNotificationListener::class.java)
    private val logTag = "MediaNotifListener"

    private fun manager() =
        getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private fun isPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState ?: return false
        return state.state == PlaybackState.STATE_PLAYING
    }

    private fun publish() {
        val mgr = manager() ?: return
        val sessions = try {
            // Trusted caller: our own bound listener component.
            mgr.getActiveSessions(self)
        } catch (e: Exception) {
            Log.w(logTag, "getActiveSessions failed: ${e.message}")
            null
        } ?: return

        val nowPlaying = sessions
            .filter { isPlaying(it) }
            .mapNotNull { it.packageName }
            .toSet()

        // Keep callbacks attached to the sessions we care about.
        val wanted = sessions.mapNotNull { it.packageName }.toSet()
        (callbacks.keys - wanted).forEach { callbacks.remove(it) }
        sessions.forEach { s ->
            val pkg = s.packageName ?: return@forEach
            if (!callbacks.containsKey(pkg)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refresh()
                    }
                }
                try {
                    s.registerCallback(cb, Handler(Looper.getMainLooper()))
                    callbacks[pkg] = cb
                } catch (e: Exception) {
                    Log.w(logTag, "registerCallback($pkg): ${e.message}")
                }
            }
        }

        MediaAppTracker.updatePlaying(nowPlaying)
    }

    private fun refresh() = publish()

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaAppTracker.trackingEnabled = true
        Log.d(logTag, "Listener connected — attributing playback via trusted sessions")
        // Active notifications give us a seed set even before any playback event.
        try {
            activeNotifications?.forEach { MediaAppTracker.onPosted(it) }
        } catch (e: Exception) {
            Log.w(logTag, "seed replay failed: ${e.message}")
        }
        publish()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaAppTracker.trackingEnabled = false
        callbacks.clear()
        MediaAppTracker.reset()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        MediaAppTracker.onPosted(sbn)
        // A newly posted media session is a strong hint that playback began.
        publish()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        MediaAppTracker.onRemoved(sbn)
        publish()
    }

    companion object {
        private const val TAG = "MediaNotificationListener"
    }
}
