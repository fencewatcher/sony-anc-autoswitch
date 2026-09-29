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

    /**
     * Set by the bound listener so the playback monitor can force a fresh
     * attribution pass on its own cadence. Null whenever the listener is unbound.
     */
    @Volatile
    var refreshHook: (() -> Unit)? = null

    /**
     * Whether the system has actually granted this app notification-listener access.
     *
     * Read from `enabled_notification_listeners` rather than assumed, because the
     * only symptom of missing access is that attribution silently resolves to
     * "nothing playing" and the filter denies everything — which is invisible
     * unless something surfaces it.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        val expected = ComponentName(context, MediaNotificationListener::class.java)
            .flattenToString()
        return enabled.split(':').any { it == expected }
    }

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
        // `getNotification()` and `getExtras()` can both be null for a notification
        // that was torn down between posting and delivery. Touching either used to
        // throw an NPE from the listener callback, which takes the whole process
        // down — and this fires the moment notification access is granted.
        try {
            val n = sbn.notification ?: return
            val isMedia = n.category == android.app.Notification.CATEGORY_TRANSPORT ||
                n.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            if (isMedia) activeNotifications[sbn.packageName] = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "onPosted(${sbn.packageName}) failed: ${e.message}")
        }
    }

    @Synchronized
    fun onRemoved(sbn: android.service.notification.StatusBarNotification) {
        try {
            val n = sbn.notification
            val isMedia = n == null ||
                n.category == android.app.Notification.CATEGORY_TRANSPORT ||
                n.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true
            if (isMedia) activeNotifications.remove(sbn.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "onRemoved(${sbn.packageName}) failed: ${e.message}")
            activeNotifications.remove(sbn.packageName)
        }
    }

    /** Packages that currently have a media notification (hint only, not playback state). */
    @Synchronized
    fun notifiedPackages(): Set<String> = activeNotifications.keys.toSet()

    /**
     * Is this playback event allowed by the user's allowlist?
     *
     * Three tiers, because the strongest signal is not always available:
     *
     *  1. [playingPackages] — a real `MediaSession` in STATE_PLAYING. Authoritative.
     *  2. [notifiedPackages] — apps holding a live media notification. Weaker, since
     *     a paused app keeps its notification, but far better than allowing
     *     everything when a non-allowlisted app is the one playing.
     *  3. Nothing known — **deny**.
     *
     * Tier 3 used to allow. That made the filter appear to be on while ignoring the
     * list entirely, because the two ways attribution can fail (listener never
     * bound, or no session visible) both ended in "allow". If the user has chosen
     * an allowlist, "I cannot tell what is playing" must not mean "trigger" — the
     * failure has to be visible instead of silent.
     */
    fun isAllowed(allowlist: Set<String>): Boolean {
        if (allowlist.isEmpty()) return true

        val playing = playingPackages
        if (playing.isNotEmpty()) {
            val ok = playing.any { it in allowlist }
            Log.d(TAG, "tier=session playing=$playing allowed=$ok")
            return ok
        }

        val notified = notifiedPackages()
        if (notified.isNotEmpty()) {
            val ok = notified.any { it in allowlist }
            Log.w(TAG, "tier=notification (no playing session) $notified allowed=$ok")
            return ok
        }

        Log.w(TAG, "tier=deny (nothing attributable — allowlist set, so not triggering)")
        return false
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

    /** package -> (controller, callback), so callbacks can actually be unregistered. */
    private val registered = HashMap<String, Pair<MediaController, MediaController.Callback>>()
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

        // Keep callbacks attached to the sessions we care about, and genuinely
        // unregister the ones we drop — dropping them from the map alone leaked
        // a callback per controller for the lifetime of the listener.
        val wanted = sessions.mapNotNull { it.packageName }.toSet()
        (registered.keys - wanted).forEach { pkg ->
            registered.remove(pkg)?.let { (controller, cb) ->
                try {
                    controller.unregisterCallback(cb)
                } catch (e: Exception) {
                    Log.w(logTag, "unregister($pkg): ${e.message}")
                }
            }
        }
        sessions.forEach { c ->
            val pkg = c.packageName ?: return@forEach
            if (!registered.containsKey(pkg)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refresh()
                    }
                }
                try {
                    c.registerCallback(cb, Handler(Looper.getMainLooper()))
                    registered[pkg] = c to cb
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
        // Let the playback monitor drive refreshes on its own poll cadence.
        //
        // Attribution used to refresh only when a media notification was posted, so
        // an app that started playing without a new notification left
        // `playingPackages` empty and the allowlist denied everything — including
        // the app the user had explicitly allowed. Polling `getActiveSessions()`
        // cannot miss a transition, whereas relying on notification events could.
        MediaAppTracker.refreshHook = { runCatching { publish() } }
        Log.d(logTag, "Listener connected — attributing playback via trusted sessions")
        // Active notifications give us a seed set even before any playback event.
        try {
            activeNotifications?.forEach { MediaAppTracker.onPosted(it) }
        } catch (e: Exception) {
            Log.w(logTag, "seed replay failed: ${e.message}")
        }
        try {
            publish()
        } catch (e: Exception) {
            // Never let attribution failure take down the service.
            Log.e(logTag, "initial publish failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaAppTracker.trackingEnabled = false
        MediaAppTracker.refreshHook = null
        registered.values.forEach { (controller, cb) ->
            try {
                controller.unregisterCallback(cb)
            } catch (e: Exception) {
                Log.w(logTag, "unregister on disconnect: ${e.message}")
            }
        }
        registered.clear()
        MediaAppTracker.reset()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        MediaAppTracker.onPosted(sbn)
        // A newly posted media session is a strong hint that playback began.
        try {
            publish()
        } catch (e: Exception) {
            Log.e(logTag, "publish after post failed: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        MediaAppTracker.onRemoved(sbn)
        try {
            publish()
        } catch (e: Exception) {
            Log.e(logTag, "publish after remove failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MediaNotificationListener"
    }
}
