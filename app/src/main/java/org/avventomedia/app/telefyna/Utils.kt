package org.avventomedia.app.telefyna

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.Playlist
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Utils {

    @RequiresApi(Build.VERSION_CODES.O)
    /**
     * seconds
     */
    @JvmStatic
    fun internetConnected(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 8.8.4.4")
            process.waitFor() == 0
        } catch (e: IOException) {
            e.message?.let { Logger.log(AuditLog.Event.NO_INTERNET, it) }
            false
        } catch (e: InterruptedException) {
            e.message?.let { Logger.log(AuditLog.Event.NO_INTERNET, it) }
            false
        }
    }

    fun setupLocalPrograms(programs: MutableList<MediaItem>, fileOrFolder: File, addedFirstItem: Boolean, playlist: Playlist) {
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
                        programs.add(0, MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(Uri.fromFile(file).toString()).build())
                        firstItemAdded = true
                    } else {
                        programs.add(MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(Uri.fromFile(file).toString()).build())
                    }
                }
            }

            if (Playlist.Type.LOCAL_RANDOMIZED == playlist.type) {
                // Shuffle the playlist using a fresh random seed to ensure better randomness and reduce repeat patterns
                programs.shuffle(Random(System.nanoTime()))
            }
        }
    }

    fun isValidEmail(email: String): Boolean {
        val regex = "^[\\w-_\\.+]*[\\w-_\\.]\\@([\\w]+\\.)+[\\w]+[\\w]$"
        return email.matches(Regex(regex))
    }

    fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val mins = TimeUnit.MILLISECONDS.toMinutes(millis - TimeUnit.HOURS.toMillis(hours))
        val secs = TimeUnit.MILLISECONDS.toSeconds(millis - TimeUnit.MINUTES.toMillis(mins))
        return String.format("%02d:%02d:%02d", hours, mins, secs)
    }

    fun readUrl(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val reader = BufferedReader(InputStreamReader(url.openStream()))
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

    // TODO add more better algorithm
    fun validPlayableItem(file: File): Boolean {
        return file.exists() && !file.name.startsWith(".")
    }
}