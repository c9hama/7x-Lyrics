package com.aalyrics.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aalyrics.CrashLog
import com.aalyrics.R

/**
 * Foreground service kept running while the Lyrics View car session is open.
 *
 * In the car the phone screen is usually off and this app has no window on it, so Samsung's
 * background freezer ("Freecess") freezes the process. A frozen app can't answer Android Auto,
 * which then drops the Lyrics View from the car launcher. A foreground service marks the app as
 * in active use so it isn't frozen; Google's navigation sample does the same while navigating.
 */
class CarKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lyrics)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            CrashLog.record("keep-alive startForeground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "car_keepalive"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CarKeepAliveService::class.java))
            } catch (e: Exception) {
                // Android can refuse a background start; the app still works, just unprotected.
                CrashLog.record("keep-alive start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarKeepAliveService::class.java))
        }
    }
}
