package org.avventomedia.app.telefyna.listen

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
import org.avventomedia.app.telefyna.R

/**
 * Silent foreground service to elevate the app's process priority to FOREGROUND_SERVICE.
 * This prevents the Android OS (especially Low Memory Killer and Doze mode) from 
 * backgrounding or killing the app during 24/7 TV broadcasting.
 */
class TelefynaForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "telefyna_24_7_service"
        val channelName = "Telefyna Broadcasting Service"
        
        // Create the NotificationChannel, but with IMPORTANCE_MIN so it's silent and 
        // unintrusive (especially for Android TV environments).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the broadcasting app alive"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Telefyna Broadcasting")
            .setContentText("Broadcasting 24/7...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        // Start the foreground service with MEDIA_PLAYBACK type (required for Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1001, notification)
        }

        // If the system kills the service, recreate it as soon as possible
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using a bound service
    }
}
