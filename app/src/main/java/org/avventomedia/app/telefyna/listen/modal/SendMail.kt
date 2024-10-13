package org.avventomedia.app.telefyna.listen.modal

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.avventomedia.app.telefyna.audit.AuditAlert

class SendEmail {
    @RequiresApi(Build.VERSION_CODES.O)
    fun sendEmail(auditAlert: AuditAlert) {
        CoroutineScope(Dispatchers.IO).launch {
            Mail(auditAlert).sendEmail()
            // If you need to return a result or update UI, you can do that here
            // Example of returning a result (if needed)
            val result = "Email sent" // or any other result you want
            withContext(Dispatchers.Main) {
                // Update UI or handle the result on the main thread if needed
            }
        }
    }
}