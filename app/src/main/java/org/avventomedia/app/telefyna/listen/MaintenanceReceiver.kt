package org.avventomedia.app.telefyna.listen

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class MaintenanceReceiver : BroadcastReceiver() {
    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        // Route maintenance to PlayerService to recompute schedule and update playback
        val svcIntent = Intent(context, org.avventomedia.app.telefyna.service.PlayerService::class.java).apply {
            action = org.avventomedia.app.telefyna.service.PlayerService.ACTION_MAINTENANCE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}