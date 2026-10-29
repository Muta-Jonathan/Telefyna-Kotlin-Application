package org.avventomedia.app.telefyna.audit

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import org.apache.commons.io.FileUtils
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.Utils
import org.avventomedia.app.telefyna.listen.mail.SendEmail
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class Logger {
    companion object {
        @SuppressLint("SimpleDateFormat")
        private val datetimeFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss")
        // High-frequency bookkeeping events: logged, but one email each would flood inboxes and trip SMTP rate limits
        private val NOT_EMAILED = setOf(
            AuditLog.Event.KEY_PRESS,
            AuditLog.Event.CACHE_NOW_PLAYING_RESUME,
            AuditLog.Event.RETRIEVE_NOW_PLAYING_RESUME
        )

        // Single writer thread: keeps disk I/O off the main thread while preserving log order
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "telefyna-audit-writer") }

        /** Waits for queued log lines to reach disk; call before the process is killed. */
        fun flush(timeoutMillis: Long = 2000) {
            try {
                writer.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.e("WRITING_AUDIT_ERROR", "Flush timed out: ${e.message}")
            }
        }

        /*
         * TODO mail, save to file
         */
        @OptIn(UnstableApi::class)
        fun log(event: AuditLog.Event, vararg params: Any) {
            logWithContext(Monitor.instance, event, *params)
        }

        @OptIn(UnstableApi::class)
        fun logWithContext(context: Context?, event: AuditLog.Event, vararg params: Any) {
            val message = String.format(event.message, *params)
            if (event == AuditLog.Event.ERROR) {
                Log.e(event.name, message)
            } else {
                Log.i(event.name, message)
            }
            // Resolve path safely even if Monitor.instance is null
            val path = Monitor.instance?.getAuditLogsFilePath(getToday()) ?: if (context != null) {
                val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    File(context.getExternalFilesDir(null), "telefynaAudit")
                } else {
                    File(android.os.Environment.getExternalStorageDirectory(), "telefynaAudit")
                }
                if (!directory.exists()) directory.mkdirs()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val today = dateFormat.format(Calendar.getInstance().time)
                "${directory.absolutePath}/${today}.log"
            } else {
                null
            }

            if (!path.isNullOrBlank()) {
                val file = File(path)
                val msg = String.format("%s %s: \n\t%s\n\n", getNow(), event.name, message)
                writer.execute {
                    try {
                        // Always append log entries so logs are never deleted or overwritten
                        FileUtils.writeStringToFile(file, msg.replace("<br>", ","), StandardCharsets.UTF_8, true)
                    } catch (e: IOException) {
                        Log.e("WRITING_AUDIT_ERROR", e.message ?: "Error writing audit")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                emailAudit(event, message)
            }
        }

        @OptIn(UnstableApi::class)
        @RequiresApi(Build.VERSION_CODES.O)
        private fun emailAudit(event: AuditLog.Event, msg: String) {
            // email notification
            val config = Monitor.instance?.configuration
            if (config != null && config.alerts?.isEnabled== true && event !in NOT_EMAILED && (event.getCategory() == AuditLog.Event.Category.ADMIN || event.getCategory() == AuditLog.Event.Category.BROADCAST)) {
                if (Utils.internetConnected()) {
                    SendEmail().execute(AuditAlert(config.alerts!!, event, msg))
                } else {
                    log(AuditLog.Event.NO_INTERNET, "Sending emails failed, no internet connection")
                }
            }
        }

        private fun getNow(): String {
            // SimpleDateFormat is not thread-safe and logging happens from several threads
            return synchronized(datetimeFormat) { datetimeFormat.format(Calendar.getInstance().time) }
        }

        @OptIn(UnstableApi::class)
        fun getToday(): String {
            return Monitor.instance?.dateFormat?.format(Calendar.getInstance().time) ?: ""
        }

        @OptIn(UnstableApi::class)
        fun getAuditsForNDays(days: Int): List<String> {
            val monitor = Monitor.instance ?: return emptyList()
            val dateFormat = monitor.dateFormat ?: return emptyList()
            return (0 until days).map { i ->
                val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
                monitor.getAuditLogsFilePath(dateFormat.format(day.time))
            }
        }

        /**
         * Deletes daily audit logs (yyyy-MM-dd.log) dated more than [keepDays] days before [today], so a box running
         * for months doesn't fill its storage. Files that don't follow the daily naming are left alone.
         */
        fun pruneOldLogs(dir: File, keepDays: Int, today: Calendar = Calendar.getInstance()): List<File> {
            if (keepDays <= 0) return emptyList()
            val format = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { isLenient = false }
            val cutoff = (today.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -keepDays)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.time
            val deleted = mutableListOf<File>()
            dir.listFiles { f -> f.isFile && f.name.endsWith(AuditLog.ENDPOINT) }?.forEach { file ->
                val date = try {
                    format.parse(file.name.removeSuffix(AuditLog.ENDPOINT))
                } catch (e: java.text.ParseException) {
                    null
                }
                if (date != null && date.before(cutoff) && file.delete()) {
                    deleted.add(file)
                }
            }
            return deleted
        }

        fun getOsKillReason(context: Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val exitReasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                    if (exitReasons.isNotEmpty()) {
                        val info = exitReasons[0]
                        val reasonString = when (info.reason) {
                            ApplicationExitInfo.REASON_ANR -> "ANR"
                            ApplicationExitInfo.REASON_CRASH -> "CRASH"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (LMK)"
                            ApplicationExitInfo.REASON_OTHER -> "OTHER"
                            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                            else -> "UNKNOWN (${info.reason})"
                        }
                        return "OS Kill Reason: $reasonString | Description: ${info.description ?: "None"}"
                    }
                } catch (e: Exception) {
                    return "Failed to fetch OS kill reason: ${e.message}"
                }
            }
            return "OS aggressively killed process (Exit reason not supported on this Android version)"
        }
    }
}
