package org.avventomedia.app.telefyna

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class Telefyna : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        registerActivityLifecycleCallbacks(ForegroundTracker)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telefyna Status",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Status and recovery notifications"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "telefyna.status"

        @JvmStatic
        lateinit var app: Telefyna
            private set

        @JvmStatic
        val appContext get() = app.applicationContext
    }
}