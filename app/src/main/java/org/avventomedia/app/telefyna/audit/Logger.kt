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


class Logger {
    companion object {
        @SuppressLint("SimpleDateFormat")
        private val datetimeFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss")

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
                try {
                    // Always append log entries so logs are never deleted or overwritten
                    FileUtils.writeStringToFile(file, msg.replace("<br>", ","), StandardCharsets.UTF_8, true)
                } catch (e: IOException) {
                    Log.e("WRITING_AUDIT_ERROR", e.message ?: "Error writing audit")
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
            if (config != null && config.alerts?.isEnabled== true && (event.getCategory() == AuditLog.Event.Category.ADMIN || event.getCategory() == AuditLog.Event.Category.BROADCAST)) {
                if (Utils.internetConnected()) {
                    SendEmail().execute(AuditAlert(config.alerts!!, event, msg))
                } else {
                    log(AuditLog.Event.NO_INTERNET, "Sending emails failed, no internet connection")
                }
            }
        }

        private fun getNow(): String {
            return datetimeFormat.format(Calendar.getInstance().time)
        }

        @OptIn(UnstableApi::class)
        fun getToday(): String {
            return Monitor.instance?.dateFormat?.format(Calendar.getInstance().time) ?: ""
        }

        @OptIn(UnstableApi::class)
        fun getAuditsForNDays(days: Int): List<String> {
            val audits = mutableListOf<String>()
            val auditDir = File(Monitor.instance?.getAuditFilePath(Monitor.instance!!,"") ?: "")
            if (auditDir.exists()) {
                val auditContents = auditDir.listFiles()
                if (auditContents != null && auditContents.isNotEmpty()) {
                    for (i in 0 until days) {
                        val audit: String
                        audit = if (i == 0) ({
                            Monitor.instance?.getAuditLogsFilePath(getToday())
                        }).toString() else ({
                            val d = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, -i) // - one day
                            }
                            Monitor.instance?.dateFormat?.let {
                                Monitor.instance!!.getAuditLogsFilePath(
                                    it.format(d.time))
                            }
                        }).toString()
                        audits.add(audit)
                    }
                }
            }
            return audits
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
