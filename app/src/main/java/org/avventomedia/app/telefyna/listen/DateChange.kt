package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import kotlin.math.abs

/**
 * Reschedules everything when the wall clock jumps (NTP correcting an RTC-less TV box after boot,
 * manual time set) or the timezone changes; alarms set against the old clock would fire at wrong times.
 */
class DateChange : BroadcastReceiver() {
    companion object {
        private const val CLOCK_OFFSET = "CLOCK_OFFSET"
        // NTP routinely nudges the clock by milliseconds; only a real jump needs a reschedule
        private const val MAX_DRIFT_MILLIS = 60_000L

        private fun currentOffset(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        fun rememberClock(context: Context) {
            context.getSharedPreferences(Monitor.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putLong(CLOCK_OFFSET, currentOffset()).apply()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val timezoneChanged = Intent.ACTION_TIMEZONE_CHANGED == intent.action
        if (Intent.ACTION_TIME_CHANGED != intent.action && !timezoneChanged) return

        val prefs = context.getSharedPreferences(Monitor.PREFERENCES, Context.MODE_PRIVATE)
        val offset = currentOffset()
        val jump = offset - prefs.getLong(CLOCK_OFFSET, offset)
        rememberClock(context)

        if (timezoneChanged || abs(jump) > MAX_DRIFT_MILLIS) {
            Logger.logWithContext(context, AuditLog.Event.TIME_CHANGED, if (timezoneChanged) "timezone changed" else "clock jumped ${jump / 1000}s")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Monitor.instance?.maintenance?.run()
            }
        }
    }
}
