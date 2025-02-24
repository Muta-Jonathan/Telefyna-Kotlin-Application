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
        val monitorInstance = Monitor.instance ?: return
        try {
            Monitor.instance?.getFirstDefaultIndex()
                ?.let { intent.getIntExtra(PLAYLIST_INDEX, it) }?.let {
                    monitorInstance.switchNow(
                        it,
                        false, monitorInstance
                    )
                }
        } catch (e: Exception) {
            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
        }
    }
}
