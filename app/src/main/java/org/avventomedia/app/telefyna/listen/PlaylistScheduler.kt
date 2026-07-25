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
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val monitor = Monitor.instance
            if (monitor != null) {
                monitor.switchNow(
                    intent.getIntExtra(PLAYLIST_INDEX, monitor.getFirstDefaultIndex()),
                    false, monitor
                )
            } else {
                // Process was killed by OS — relaunch Monitor Activity
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
