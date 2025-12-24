package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class BootReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start playback service to resume 24/7 playback
            val startIntent = Intent(context, org.avventomedia.app.telefyna.service.PlayerService::class.java).apply {
                action = org.avventomedia.app.telefyna.service.PlayerService.ACTION_START
            }
            context.startForegroundService(startIntent)
        }
    }
}