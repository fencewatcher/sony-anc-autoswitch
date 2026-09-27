package com.fencewatcher.sonyanc

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Tracks which apps currently have media notifications (Spotify, TikTok, …).
 *
 * Why: on Android 14+ MediaSessionManager.getActiveSessions only returns your
 * own sessions, and AudioManager.getActivePlaybackConfigurations only returns
 * configurations owned by the caller. The only reliable cross-version way to
 * attribute "this app is playing" is watching media notifications.
 *
 * The user must enable notification access for this app (the MainActivity has
 * a button that opens the system settings screen). Until then, the monitor
 * falls back to the best-effort APIs and defaults to ALLOW when nothing can
 * be detected, so the core feature keeps working.
 */
object MediaAppTracker {
    private const val TAG = "MediaAppTracker"

    @Volatile
    var trackingEnabled = false

    // package -> last posted timestamp
    private val activeNotifications = mutableMapOf<String, Long>()

    @Synchronized
    fun onPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (isMediaNotification(sbn)) {
            activeNotifications[pkg] = System.currentTimeMillis()
            Log.d(TAG, "Media notification from $pkg")
        }
    }

    @Synchronized
    fun onRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (isMediaNotification(sbn)) {
            activeNotifications.remove(pkg)
            Log.d(TAG, "Media notification removed: $pkg")
        }
    }

    @Synchronized
    fun currentPackages(): Set<String> = activeNotifications.keys.toSet()

    fun updateTrackingState(context: Context) {
        trackingEnabled = try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.isNotificationListenerAccessGranted(
                ComponentName(context, MediaNotificationListener::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check listener access: ${e.message}")
            false
        }
    }

    // Some apps post non-media notifications; media ones carry a transport
    // category or a media-style session/token.
    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return n.category == android.app.Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) ||
            n.extras.containsKey("android.media.metadata") ||
            sbn.key.contains("media") // fallback heuristic
    }
}

/** The actual NotificationListenerService entry point. */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaAppTracker.trackingEnabled = true
        Log.d("MediaNotificationListener", "Listener connected")

        // Replay currently active notifications
        try {
            for (sbn in activeNotifications) {
                MediaAppTracker.onPosted(sbn)
            }
        } catch (e: Exception) {
            Log.w("MediaNotificationListener", "Replay failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaAppTracker.trackingEnabled = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        MediaAppTracker.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        MediaAppTracker.onRemoved(sbn)
    }
}