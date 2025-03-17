package org.avventomedia.app.telefyna.listen

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger

class MaintenanceReceiver : BroadcastReceiver() {
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        val monitor = Monitor.instance
        if (monitor != null) {
            monitor.maintenance?.triggerMaintenance()
            monitor.maintenance?.scheduleNextMaintenance()
        } else {
            Logger.log(AuditLog.Event.ERROR, "Monitor instance is null in MaintenanceReceiver")
            // Optional: Start Monitor activity or reschedule if critical
        }
    }
}