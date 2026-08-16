package org.avventomedia.app.telefyna

import android.content.Context
import android.media.MediaMetadataRetriever
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object DurationCacheManager {
    private const val CACHE_FILE_NAME = "durations.json"
    private val durationsMap = mutableMapOf<String, Long>()
    private var isLoaded = false

    fun init(context: Context) {
        if (isLoaded) return
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists()) {
            try {
                val json = cacheFile.readText()
                val type = object : TypeToken<Map<String, Long>>() {}.type
                val map: Map<String, Long>? = Gson().fromJson(json, type)
                if (map != null) {
                    durationsMap.putAll(map)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        isLoaded = true
    }

    private fun save(context: Context) {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        try {
            val json = Gson().toJson(durationsMap)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDuration(context: Context, file: File): Long {
        init(context)
        val path = file.absolutePath
        if (durationsMap.containsKey(path)) {
            return durationsMap[path]!!
        }

        // Fallback if missing (will extract on the fly, but should be rare)
        val duration = extractDuration(file)
        durationsMap[path] = duration
        save(context)
        return duration
    }

    fun indexDirectory(context: Context, directory: File) {
        init(context)
        var modified = false
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                indexDirectory(context, file)
            } else if (Utils.validPlayableItem(file)) {
                val path = file.absolutePath
                if (!durationsMap.containsKey(path)) {
                    val duration = extractDuration(file)
                    durationsMap[path] = duration
                    modified = true
                }
            }
        }
        if (modified) save(context)
    }

    private fun extractDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
