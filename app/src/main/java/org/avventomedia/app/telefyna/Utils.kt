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

    @JvmStatic
    fun internetConnected(context: Context? = Monitor.instance): Boolean {
        return try {
            if (context != null) {
                val cm =
                        context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as?
                                android.net.ConnectivityManager
                if (cm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val network = cm.activeNetwork
                        if (network == null) return false
                        val capabilities = cm.getNetworkCapabilities(network)
                        if (capabilities == null ||
                                        !capabilities.hasCapability(
                                                android.net.NetworkCapabilities
                                                        .NET_CAPABILITY_INTERNET
                                        )
                        ) {
                            return false
                        }
                    } else {
                        @Suppress("DEPRECATION") val netInfo = cm.activeNetworkInfo
                        if (netInfo == null || !netInfo.isConnected) {
                            return false
                        }
                    }
                }
            }
            // Lightweight socket reachability check (no OS process creation or FD leak)
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

            if (Playlist.Type.LOCAL_RANDOMIZED == playlist.type) {
                // Shuffle the playlist using the default random seed to ensure better randomness
                // and reduce repeat patterns
                programs.shuffle()
            }
        }
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

    private val PLAYABLE_EXTENSIONS =
            listOf("mp4", "mkv", "avi", "mov", "ts", "webm", "png", "jpg", "jpeg", "webp", "gif")

    fun validPlayableItem(file: File): Boolean {
        if (!file.exists() || file.name.startsWith(".")) return false
        val extension = file.extension.lowercase(java.util.Locale.ENGLISH)
        return PLAYABLE_EXTENSIONS.contains(extension)
    }
}
