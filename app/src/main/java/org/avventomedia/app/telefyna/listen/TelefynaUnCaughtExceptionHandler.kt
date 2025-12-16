package org.avventomedia.app.telefyna.listen

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import java.io.File
import java.io.FileWriter

class TelefynaUnCaughtExceptionHandler : Thread.UncaughtExceptionHandler {
    companion object {
        const val CRASH = "crash"
        const val EXCEPTION = "exception"
        private const val OOM_MARKER = "oom.txt"
    }

    /**
     * Handles uncaught exceptions by logging the error and restarting the application.
     *
     * @param thread The thread that encountered the exception.
     * @param ex The throwable exception that was not caught.
     *
     * Logs the exception details using the Logger and writes a marker file if the exception
     * is an OutOfMemoryError to avoid crash loops. Restarts the application by invoking
     * context.restartApp().
     */
    @OptIn(UnstableApi::class)
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        val context = Monitor.instance

        // Log the exception clearly
        if (ex is OutOfMemoryError) {
            Logger.log(AuditLog.Event.ERROR, "OutOfMemoryError: ${ex.message}")
            // Write a marker to avoid crash loop
            writeOomMarker()
        } else {
            Logger.log(AuditLog.Event.ERROR, "${ex::class.simpleName}: ${ex.message}")
        }

        context?.restartApp()
    }


    /**
     * Writes a marker file to indicate that an OutOfMemoryError has been encountered.
     *
     * @see OOM_MARKER
     */
    @OptIn(UnstableApi::class)
    private fun writeOomMarker() {
        try {
            val context = Monitor.instance ?: return
            val markerFile = File(context.getAuditFilePath(context, OOM_MARKER))
            FileWriter(markerFile, false).use { writer ->
                writer.write("Detected OutOfMemoryError at ${System.currentTimeMillis()}")
            }
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "Failed to write OOM marker: ${e.message}")
        }
    }
}
