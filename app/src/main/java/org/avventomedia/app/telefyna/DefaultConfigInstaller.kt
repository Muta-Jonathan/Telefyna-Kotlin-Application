package org.avventomedia.app.telefyna

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * First-run setup: when there is no config.json yet, installs the bundled generic default and creates the folders
 * operators fill, so a fresh box shows the slate and starts playing as soon as media is copied in.
 */
object DefaultConfigInstaller {
    const val ASSET = "default-config.json"
    private const val CONFIG = "config.json"

    val FOLDERS = listOf(
        "playlist/default",
        "playlist/fillers",
        "bumper/General-INTRO",
        "bumper/General-OUTRO",
        "lowerThird",
        "watermark"
    )

    /** Installs into [root] only when [root]/config.json is missing; never overwrites. Returns true if installed. */
    @Throws(IOException::class)
    fun installIfMissing(root: File, defaultConfig: () -> InputStream): Boolean {
        val target = File(root, CONFIG)
        if (target.exists()) return false
        FOLDERS.forEach { File(root, it).mkdirs() }
        if (!root.isDirectory) throw IOException("Cannot create ${root.absolutePath}")
        // Write then rename, so a half-written file is never read as the config
        val temp = File(root, "$CONFIG.tmp")
        defaultConfig().use { input -> temp.outputStream().use { input.copyTo(it) } }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Cannot write ${target.absolutePath}")
        }
        return true
    }
}
