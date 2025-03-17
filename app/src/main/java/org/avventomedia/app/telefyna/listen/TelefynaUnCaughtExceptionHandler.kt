package org.avventomedia.app.telefyna.listen

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger

class TelefynaUnCaughtExceptionHandler : Thread.UncaughtExceptionHandler {
    companion object {
        const val CRASH = "crash"
        const val EXCEPTION = "exception"
    }

    @OptIn(UnstableApi::class)
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Logger.log(AuditLog.Event.ERROR, ex)
        Monitor.instance?.restartApp()
    }
}
