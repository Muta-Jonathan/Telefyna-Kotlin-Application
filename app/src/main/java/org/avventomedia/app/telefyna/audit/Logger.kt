package org.avventomedia.app.telefyna.audit

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar


class Logger {
    companion object {
        private val datetimeFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss")

        /*
         * TODO mail, save to file
         */
        fun log(event: AuditLog.Event, vararg params: Any) {
            val message = String.format(event.message, *params)
            if (event == AuditLog.Event.ERROR) {
                Log.e(event.name, message)
            } else {
                Log.i(event.name, message)
            }
            val path = Monitor.instance.getAuditLogsFilePath(getToday())
            val msg = String.format("%s %s: \n\t%s", getNow(), event.name, message)
            try {
                FileUtils.writeStringToFile(File(path as String), msg.replace("<br>", ","), StandardCharsets.UTF_8, true)
            } catch (e: IOException) {
                Log.e("WRITING_AUDIT_ERROR", e.message ?: "Error writing audit")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                emailAudit(event, msg)
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun emailAudit(event: AuditLog.Event, msg: String) {
            // email notification
            val config = Monitor.instance.getConfiguration()
            if (config != null && config.alerts?.isEnabled == true && (event.category == AuditLog.Event.Category.ADMIN || event.category == AuditLog.Event.Category.BROADCAST)) {
                if (Utils.internetConnected()) {
                    SendEmail().execute(AuditAlert(config.alerts, event, msg))
                } else {
                    log(AuditLog.Event.NO_INTERNET, "Sending emails failed, no internet connection")
                }
            }
        }

        private fun getNow(): String {
            return datetimeFormat.format(Calendar.getInstance().time)
        }

        fun getToday(): String {
            return Monitor.instance.getDateFormat().format(Calendar.getInstance().time)
        }

        fun getAuditsForNDays(days: Int): List<String> {
            val audits = mutableListOf<String>()
            val auditDir = File(Monitor.instance.getAuditFilePath(""))
            if (auditDir.exists()) {
                val auditContents = auditDir.listFiles()
                if (auditContents != null && auditContents.isNotEmpty()) {
                    for (i in 0 until days) {
                        val audit: String
                        audit = if (i == 0) {
                            Monitor.instance.getAuditLogsFilePath(getToday())
                        } else {
                            val d = Calendar.getInstance().apply {
                                add(Calendar.DAY_OF_YEAR, -i) // - one day
                            }
                            Monitor.instance.getAuditLogsFilePath(Monitor.instance.getDateFormat().format(d.time))
                        }
                        audits.add(audit)
                    }
                }
            }
            return audits
        }
    }
}
