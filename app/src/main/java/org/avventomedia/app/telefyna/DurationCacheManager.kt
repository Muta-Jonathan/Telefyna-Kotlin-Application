package org.avventomedia.app.telefyna

import android.content.Context
import android.media.MediaMetadataRetriever
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent media-duration index. Measuring a file (MediaMetadataRetriever) costs tens of milliseconds, and slot
 * joins and intelligent sorting need the duration of every file in a folder, so durations are measured once,
 * kept across restarts, and re-measured only when a file changes (the key includes its size and modification time).
 * Thread-safe; the index is written to disk in the background, batched.
 */
object DurationCacheManager {
    private const val CACHE_FILE_NAME = "durations.json"

    private val durations = ConcurrentHashMap<String, Long>()
    private val loaded = AtomicBoolean(false)
    private val saveScheduled = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "telefyna-durations") }

    /** Same file, same size and modification time: same duration. */
    fun key(file: File): String = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    private fun load(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.isFile) return
        runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            Gson().fromJson<Map<String, Long>>(cacheFile.readText(), type)?.let { durations.putAll(it) }
        }
    }

    private fun scheduleSave(context: Context) {
        if (!saveScheduled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        writer.execute {
            saveScheduled.set(false)
            runCatching {
                val target = File(appContext.filesDir, CACHE_FILE_NAME)
                val temp = File(appContext.filesDir, "$CACHE_FILE_NAME.tmp")
                temp.writeText(Gson().toJson(HashMap(durations)))
                if (!temp.renameTo(target)) temp.delete()
            }
        }
    }

    /** Duration in ms (0 if unknown/unreadable); measured once per file version. */
    fun getDuration(context: Context, file: File): Long {
        if (!file.isFile) return 0L
        load(context)
        val key = key(file)
        durations[key]?.let { return it }
        val duration = measure(file)
        if (duration > 0) {
            durations[key] = duration
            scheduleSave(context)
        }
        return duration
    }

    /**
     * Forgets files that were deleted or replaced (their key no longer matches), so the index and its file don't
     * grow for as long as the box runs. Call off the main thread, e.g. during midnight maintenance.
     */
    fun prune(context: Context) {
        load(context)
        val stale = durations.keys.filter { key ->
            val file = File(key.substringBeforeLast('|').substringBeforeLast('|'))
            !file.isFile || key(file) != key
        }
        if (stale.isNotEmpty()) {
            stale.forEach { durations.remove(it) }
            scheduleSave(context)
        }
    }

    /** Measures every playable file under [directory] that isn't indexed yet (call off the main thread). */
    fun indexDirectory(context: Context, directory: File) {
        Utils.playableFiles(directory).forEach { getDuration(context, it) }
    }

    private fun measure(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
