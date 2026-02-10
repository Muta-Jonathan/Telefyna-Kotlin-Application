package org.avventomedia.app.telefyna.listen

import android.os.Build
import android.os.FileObserver
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import java.io.File
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger

/**
 * FileObserver to monitor the init.txt file for real-time schedule updates. When init.txt is
 * created, it immediately triggers a schedule reload.
 */
@RequiresApi(Build.VERSION_CODES.O)
class InitFileObserver(
        private val directoryPath: String,
        private val fileName: String = "init.txt"
) : FileObserver(directoryPath, CREATE or DELETE or MOVED_TO) {
    init {
        // Observer initialized
    }

    @OptIn(UnstableApi::class)
    override fun onEvent(event: Int, path: String?) {

        // Skip if path is null
        if (path == null) return

        // Check for specific files we are interested in
        when (path) {
            fileName -> { // init.txt
                handleInitFile(event)
            }
            "Restart.txt" -> {
                handleRestartFile(event)
            }
            "Reboot.txt" -> {
                handleRebootFile(event)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handleInitFile(event: Int) {
        when (event and ALL_EVENTS) {
            CREATE, MOVED_TO -> {
                Logger.log(
                        AuditLog.Event.FILE_OBSERVER,
                        "init.txt detected with event ${eventToString(event)}, triggering schedule reload"
                )

                // Delete the init.txt file
                val initFile = File(directoryPath, fileName)
                if (initFile.exists()) {
                    initFile.delete()
                }

                // Trigger maintenance to reload the schedule only if config.json exists
                val configFile = File(Monitor.instance?.getConfigFile() ?: return)

                if (configFile.exists()) {
                    // Execute on main thread to avoid "Player is accessed on the wrong thread"
                    // exception
                    Monitor.instance?.runOnUiThread {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Monitor.instance?.maintenance?.run()
                        }
                    }
                } else {
                    Logger.log(
                            AuditLog.Event.FILE_OBSERVER,
                            "init.txt detected but config.json does not exist at ${configFile.absolutePath}, skipping schedule reload"
                    )
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handleRestartFile(event: Int) {
        when (event and ALL_EVENTS) {
            CREATE, MOVED_TO -> {
                Logger.log(
                        AuditLog.Event.FILE_OBSERVER,
                        "Restart.txt detected with event ${eventToString(event)}, initiating app restart"
                )

                // Delete the file immediately
                val restartFile = File(directoryPath, "Restart.txt")
                if (restartFile.exists()) {
                    restartFile.delete()
                }

                // Execute restart
                Monitor.instance?.restartApp()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handleRebootFile(event: Int) {
        when (event and ALL_EVENTS) {
            CREATE, MOVED_TO -> {
                Logger.log(
                        AuditLog.Event.FILE_OBSERVER,
                        "Reboot.txt detected with event ${eventToString(event)}, initiating device reboot"
                )

                // Delete the file immediately
                val rebootFile = File(directoryPath, "Reboot.txt")
                if (rebootFile.exists()) {
                    rebootFile.delete()
                }

                // Execute reboot
                Monitor.instance?.rebootDevice()
            }
        }
    }

    private fun eventToString(event: Int): String {
        return when (event and ALL_EVENTS) {
            ACCESS -> "ACCESS"
            MODIFY -> "MODIFY"
            ATTRIB -> "ATTRIB"
            CLOSE_WRITE -> "CLOSE_WRITE"
            CLOSE_NOWRITE -> "CLOSE_NOWRITE"
            OPEN -> "OPEN"
            MOVED_FROM -> "MOVED_FROM"
            MOVED_TO -> "MOVED_TO"
            CREATE -> "CREATE"
            DELETE -> "DELETE"
            DELETE_SELF -> "DELETE_SELF"
            MOVE_SELF -> "MOVE_SELF"
            else -> "UNKNOWN($event)"
        }
    }
}
