package org.avventomedia.app.telefyna.listen

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor

class TelefynaUnCaughtExceptionHandler : Thread.UncaughtExceptionHandler {
    companion object {
        const val CRASH = "crash"
        const val EXCEPTION = "exception"
    }

    @OptIn(UnstableApi::class)
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Monitor.instance?.restartApp()
    }
}
