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

class PlaylistScheduler : BroadcastReceiver() {
    companion object {
        const val PLAYLIST_INDEX = "playlist_index"
        // exact slot start (epoch ms); the alarm fires Maintenance.PREROLL_MS before it
        const val START_AT = "start_at"
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val monitor = Monitor.instance
            if (monitor != null) {
                monitor.prepareSlot(
                    intent.getIntExtra(PLAYLIST_INDEX, monitor.getFirstDefaultIndex()),
                    intent.getLongExtra(START_AT, 0L)
                )
            } else {
                // Process was killed by OS — relaunch Monitor Activity
                Logger.logWithContext(context, AuditLog.Event.ERROR, "App Relaunch [PlaylistScheduler]. ${Logger.getOsKillReason(context)}")
                val launchIntent = Intent(context, Monitor::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
        }
    }
}
