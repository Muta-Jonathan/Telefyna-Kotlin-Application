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
            // Process was killed by OS — relaunch Monitor Activity
            val launchIntent = Intent(context, Monitor::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}