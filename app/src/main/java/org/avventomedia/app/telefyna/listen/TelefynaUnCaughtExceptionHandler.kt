package org.avventomedia.app.telefyna.listen

import org.avventomedia.app.telefyna.Monitor

class TelefynaUnCaughtExceptionHandler : Thread.UncaughtExceptionHandler {
    companion object {
        const val CRASH = "crash"
        const val EXCEPTION = "exception"
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Monitor.instance?.restartApp()
    }
}
