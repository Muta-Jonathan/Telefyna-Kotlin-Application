package org.avventomedia.app.telefyna

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.TimeUnit
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist

object Utils {

    const val COMMA_SPLITTER: String = ","

    // Reachability probe result, refreshed in the background so callers (often the main thread) never block on a socket
    @Volatile
    private var lastProbeReachable = true
    private val probeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val PROBE_INTERVAL_SECONDS = 10L

    @JvmStatic
    fun internetConnected(context: Context? = Monitor.instance): Boolean {
        if (context != null && !hasInternetNetwork(context)) return false
        if (probeStarted.compareAndSet(false, true)) {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "telefyna-net-probe").apply { isDaemon = true }
            }.scheduleWithFixedDelay({ lastProbeReachable = probeReachable() }, 0, PROBE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
        return lastProbeReachable
    }

    private fun hasInternetNetwork(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
                capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun probeReachable(): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun setupLocalPrograms(
            programs: MutableList<MediaItem>,
            fileOrFolder: File,
            addedFirstItem: Boolean,
            playlist: Playlist
    ) {
        if (fileOrFolder.exists()) {
            val fileOrFolderList = fileOrFolder.listFiles() ?: return

            if (Playlist.Type.LOCAL_SEQUENCED == playlist.type || playlist.isResuming()) {
                fileOrFolderList.sort() // Ordering programs alphabetically
            }

            var firstItemAdded = addedFirstItem
            for ((index, file) in fileOrFolderList.withIndex()) {
                if (file.isDirectory) {
                    setupLocalPrograms(programs, file, firstItemAdded, playlist)
                } else if (validPlayableItem(file)) {
                    if (index == 0 && !firstItemAdded) { // First in the folder if not yet added
                        programs.add(
                                0,
                                MediaItem.Builder()
                                        .setUri(Uri.fromFile(file))
                                        .setMediaId(Uri.fromFile(file).toString())
                                        .build()
                        )
                        firstItemAdded = true
                    } else {
                        programs.add(
                                MediaItem.Builder()
                                        .setUri(Uri.fromFile(file))
                                        .setMediaId(Uri.fromFile(file).toString())
                                        .build()
                        )
                    }
                }
            }

            if (playlist.isRandomized()) {
                // Shuffle the playlist using the default random seed to ensure better randomness
                // and reduce repeat patterns
                programs.shuffle()
            }
        }
    }

    /**
     * Exact wake-up alarm that also fires in Doze. Falls back to an inexact one if the exact-alarm
     * permission was revoked (API 31+) instead of throwing a SecurityException.
     */
    fun setExactAlarm(alarmManager: android.app.AlarmManager, triggerAtMillis: Long, operation: android.app.PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Logger.log(AuditLog.Event.ERROR, "Exact alarms not permitted, scheduling inexact alarm")
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    /** Immediate sub-folders of [roots] that contain at least one playable file (at any depth), by name. */
    fun programFolders(roots: List<File>): List<File> {
        return roots.flatMap { root -> root.listFiles()?.filter { it.isDirectory && playableFiles(it).isNotEmpty() }.orEmpty() }
            .sortedBy { it.name }
    }

    /** All playable files under [folder] (sub-folders included), in alphabetical order. */
    fun playableFiles(folder: File): List<File> {
        val entries = folder.listFiles()?.sortedBy { it.name } ?: return emptyList()
        return entries.flatMap { if (it.isDirectory) playableFiles(it) else if (validPlayableItem(it)) listOf(it) else emptyList() }
    }

    /** A random folder, avoiding [lastPicked] (absolute path) when there is a choice. */
    fun pickFolder(folders: List<File>, lastPicked: String?, random: kotlin.random.Random = kotlin.random.Random.Default): File? {
        if (folders.isEmpty()) return null
        val candidates = folders.filter { it.absolutePath != lastPicked }.ifEmpty { folders }
        return candidates[random.nextInt(candidates.size)]
    }

    fun isValidEmail(email: String): Boolean {
        val regex = "^[\\w-_\\.+]*[\\w-_\\.]\\@([\\w]+\\.)+[\\w]+[\\w]$"
        return email.matches(Regex(regex))
    }

    fun formatDuration(millis: Long): String {
        val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(millis)
        val mins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val secs = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(java.util.Locale.ENGLISH, "%02d:%02d:%02d", hours, mins, secs)
    }

    fun readUrl(urlString: String): String? {
        return try {
            val connection = URL(urlString).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
            val buffer = StringBuilder()
            val chars = CharArray(1024)
            var read: Int
            while (reader.read(chars).also { read = it } != -1) {
                buffer.appendRange(chars, 0, read)
            }
            reader.close()
            buffer.toString()
        } catch (e: Exception) {
            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
            null
        }
    }

    fun logLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val nwis = NetworkInterface.getNetworkInterfaces()
            while (nwis.hasMoreElements()) {
                val ni = nwis.nextElement()
                for (ia in ni.interfaceAddresses) {
                    ips.add("${ni.displayName}: ${ia.address}/ ${ia.networkPrefixLength}")
                }
            }
        } catch (e: Exception) {
            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
        }
        return ips
    }

    private val PLAYABLE_EXTENSIONS =
            listOf("mp4", "mkv", "avi", "mov", "ts", "webm", "png", "jpg", "jpeg", "webp", "gif")

    fun validPlayableItem(file: File): Boolean {
        if (!file.exists() || file.name.startsWith(".")) return false
        val extension = file.extension.lowercase(java.util.Locale.ENGLISH)
        return PLAYABLE_EXTENSIONS.contains(extension)
    }
}
