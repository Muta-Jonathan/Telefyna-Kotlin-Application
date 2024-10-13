package org.avventomedia.app.telefyna.listen

class TelefynaUnCaughtExceptionHandler : Thread.UncaughtExceptionHandler {
    companion object {
        const val CRASH = "crash"
        const val EXCEPTION = "exception"
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Monitor.instance.restartApp()
    }
}
