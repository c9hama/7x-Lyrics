package com.aalyrics.source

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android grants us notification access, which is what lets
 * MediaSessionManager hand us other apps' media sessions.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        NowPlayingTracker.ensureStarted(this)
    }

    override fun onListenerDisconnected() {
        NowPlayingTracker.stop()
    }
}
