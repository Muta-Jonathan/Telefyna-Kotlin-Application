package org.avventomedia.app.telefyna.repo

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduleRepository(private val context: Context) {
    suspend fun buildMediaItems(urls: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        urls.map { url ->
            MediaItem.Builder().setUri(Uri.parse(url)).build()
        }
    }
}
