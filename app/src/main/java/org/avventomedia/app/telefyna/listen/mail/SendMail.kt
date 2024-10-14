package org.avventomedia.app.telefyna.listen.mail

import android.os.AsyncTask
import android.os.Build
import androidx.annotation.RequiresApi
import org.avventomedia.app.telefyna.audit.AuditAlert

class SendEmail : AsyncTask<AuditAlert, Int, String>() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun doInBackground(vararg auditAlerts: AuditAlert): String? {
        Mail(auditAlerts[0]).sendEmail()
        return null
    }
}
