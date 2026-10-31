package org.avventomedia.app.telefyna.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import org.avventomedia.app.telefyna.Monitor
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB / SD auto-import: a drive with a `telefyna/` folder is copied into the box's own `telefyna/` folder
 * (new and changed files only, never deleting), so content can be carried to the box by hand.
 * An imported config.json is applied through the usual reload, keeping the last good config if it is broken.
 */
object DriveImporter {
    private const val FOLDER = "telefyna"
    private const val SPACE_MARGIN = 200L * 1024 * 1024
    // FAT/exFAT keep modification times to 2 s
    private const val MTIME_TOLERANCE_MS = 2_000L
    private val SKIPPED = setOf("System Volume Information", "LOST.DIR", "telefynaAudit")
    private val running = AtomicBoolean(false)

    data class Result(val copied: Int, val bytes: Long, val unchanged: Int, val failed: Int, val configChanged: Boolean)

    /** Files under [source] that are new or changed compared with [target], keyed by relative path. */
    fun pending(source: File, target: File): List<Pair<File, File>> =
        source.walkTopDown()
            .onEnter { it == source || (!it.name.startsWith(".") && it.name !in SKIPPED) }
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it to File(target, it.relativeTo(source).path) }
            .filter { (src, dest) -> !dest.isFile || dest.length() != src.length() || dest.lastModified() < src.lastModified() - MTIME_TOLERANCE_MS }
            .toList()

    /** Copies new and changed files from [source] into [target]; a file is only replaced once fully copied. */
    fun sync(source: File, target: File): Result {
        val todo = pending(source, target)
        val total = source.walkTopDown().count { it.isFile && !it.name.startsWith(".") }
        var copied = 0
        var bytes = 0L
        var failed = 0
        var configChanged = false
        for ((src, dest) in todo) {
            val part = File(dest.parentFile, ".${dest.name}.part")
            try {
                dest.parentFile?.mkdirs()
                src.copyTo(part, overwrite = true)
                part.setLastModified(src.lastModified())
                if (dest.exists() && !dest.delete()) throw IllegalStateException("could not replace ${dest.name}")
                if (!part.renameTo(dest)) throw IllegalStateException("could not move ${dest.name} into place")
                copied++
                bytes += src.length()
                if (dest.relativeTo(target).path == "config.json") configChanged = true
            } catch (e: Exception) {
                part.delete()
                failed++
            }
        }
        return Result(copied, bytes, total - todo.size, failed, configChanged)
    }

    /** Imports from the drive mounted at [volumeRoot], in the background. Safe to call repeatedly. */
    fun importFrom(context: Context, volumeRoot: File) {
        val source = File(volumeRoot, FOLDER)
        if (!source.isDirectory) return
        val target = File(Environment.getExternalStorageDirectory(), FOLDER)
        if (source.canonicalPath == target.canonicalPath) return
        val config = Monitor.instance?.configuration
        when {
            config?.driveImport == false -> return skip(context, source, "driveImport is off")
            config?.playlists.orEmpty().any { it.active && it.isUsingExternalStorage } ->
                return skip(context, source, "playlists play straight from the drive (usingExternalStorage)")
        }
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                val needed = pending(source, target).sumOf { it.first.length() }
                if (target.apply { mkdirs() }.usableSpace < needed + SPACE_MARGIN) {
                    skip(appContext, source, "not enough space on the box for ${FileUtils.byteCountToDisplaySize(needed)}")
                    return@Thread
                }
                val result = sync(source, target)
                Logger.logWithContext(appContext, AuditLog.Event.DRIVE_IMPORT, result.copied,
                    FileUtils.byteCountToDisplaySize(result.bytes), source.absolutePath, result.unchanged, result.failed)
                if (result.configChanged) {
                    // Picked up by the watchdog: reloads, and keeps the last good config if this one is broken
                    File(Logger.auditDirectory(appContext), "init.txt").apply { parentFile?.mkdirs(); createNewFile() }
                }
            } catch (e: Exception) {
                skip(appContext, source, e.message ?: e.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }, "telefyna-drive-import").start()
    }

    /** Drives that were already in the box when Telefyna started (no mount broadcast for those). */
    fun importFromMountedDrives(context: Context) {
        val roots = ContextCompat.getExternalFilesDirs(context, null).drop(1).filterNotNull()
            .map { File(it.absolutePath.substringBefore("/Android/")) } +
                (File("/mnt/usb").listFiles()?.toList() ?: emptyList())
        roots.distinctBy { it.absolutePath }.forEach { importFrom(context, it) }
    }

    private fun skip(context: Context, source: File, why: String) {
        Logger.logWithContext(context, AuditLog.Event.DRIVE_IMPORT_SKIPPED, source.absolutePath, why)
    }
}

/** A USB stick or SD card was mounted. */
class DriveMounted : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val root = intent.data?.path?.let { File(it) } ?: return
        // The receiver is exported (system broadcast), so only trust paths Android reports as a mounted removable drive
        val isRemovableDrive = runCatching {
            Environment.isExternalStorageRemovable(root) && Environment.getExternalStorageState(root) == Environment.MEDIA_MOUNTED
        }.getOrDefault(false)
        if (isRemovableDrive) DriveImporter.importFrom(context, root)
    }
}
