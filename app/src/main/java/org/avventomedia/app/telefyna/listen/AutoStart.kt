package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.listen.RestartReceiver

class AutoStart : BroadcastReceiver() {
    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Schedule a gentle restart path that will either bring UI if foreground or show a notification to enter
            RestartReceiver.scheduleRestart(context, 10000L)
        }
        Log.d("AutoStart", "Boot completed received")
    }
}