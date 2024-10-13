package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger

class DateChange : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_TIME_CHANGED == intent.action) {
            Logger.log(AuditLog.Event.TIME_CHANGED)
            // Monitor.instance.getMaintenance().run() // TODO do more here
        }
    }
}