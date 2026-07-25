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

/**
 * Standalone manifest-registered BroadcastReceiver for the KeepOnAir alarm.
 * Unlike the previous inner-class version, this receiver survives process death
 * because it is declared in AndroidManifest.xml. If the app process was killed
 * by Android LMK/Doze, this receiver will relaunch Monitor Activity automatically.
 */
class KeepOnAirReceiver : BroadcastReceiver() {

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        val monitor = Monitor.instance
        if (monitor != null) {
            // Process is alive — delegate to Monitor's keepOnAir logic
            monitor.handleKeepOnAir()
        } else {
            // Process was killed by OS — relaunch Monitor Activity
            Logger.logWithContext(context, AuditLog.Event.ERROR, "App Relaunch [KeepOnAirReceiver]. ${Logger.getOsKillReason(context)}")
            val launchIntent = Intent(context, Monitor::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
