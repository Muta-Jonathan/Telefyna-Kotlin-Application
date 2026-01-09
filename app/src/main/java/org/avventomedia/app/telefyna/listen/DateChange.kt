package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger

class DateChange : BroadcastReceiver() {
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        // Handle time, date, or timezone changes
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_DATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            Logger.log(AuditLog.Event.TIME_CHANGED, "Action: ${intent.action}")
            // Re-run maintenance to reset schedule for new day/time
            try {
                Monitor.instance?.maintenance?.run()
            } catch (e: Exception) {
                Logger.log(AuditLog.Event.ERROR, "DateChange maintenance failed: ${e.message}")
            }
        }
    }
}