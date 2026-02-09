package org.avventomedia.app.telefyna.listen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.R
import org.avventomedia.app.telefyna.Telefyna

@OptIn(UnstableApi::class)
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ALWAYS try to bring to front / restart, because if we are here from a crash/restart-loop,
        // the process might be new and ForegroundTracker might be false.
        val i =
                Intent(context, Monitor::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            // If starting the Activity fails (e.g. background restrictions), show a notification
            // fallback
            showBringToFrontNotification(context)
        }
    }

    private fun showBringToFrontNotification(context: Context) {
        val contentIntent =
                PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, Monitor::class.java).apply {
                            addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val builder =
                NotificationCompat.Builder(context, Telefyna.CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Telefyna is running")
                        .setContentText("Tap to open the player UI")
                        .setOngoing(true)
                        .setContentIntent(contentIntent)
                        .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            with(NotificationManagerCompat.from(context)) { notify(NOTIF_ID, builder.build()) }
        } catch (_: SecurityException) {
            // If POST_NOTIFICATIONS not granted on API 33+, silently ignore
        }
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun scheduleRestart(context: Context, delayMs: Long) {
            val intent = Intent(context, RestartReceiver::class.java)
            val pending =
                    PendingIntent.getBroadcast(
                            context,
                            700000001, // keep same request code used before
                            intent,
                            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + delayMs,
                        pending
                )
            } else {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pending)
            }
        }
    }
}
