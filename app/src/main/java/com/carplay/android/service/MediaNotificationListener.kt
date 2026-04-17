package com.carplay.android.service

import android.service.notification.NotificationListenerService
import timber.log.Timber

/**
 * Media Notification Listener Service
 *
 * Required for getActiveSessions() to work in MediaSessionManager.
 * This service listens for notifications so the app can detect
 * which media apps are currently playing (YouTube, Spotify, etc.).
 *
 * The user must grant "Notification Access" permission for this to work.
 * Without it, media session observation won't work but the app still functions.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.d("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.d("Notification listener disconnected")
    }
}
