package org.avventomedia.app.telefyna.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaRepository(private val context: Context) {
    suspend fun readText(file: File): String = withContext(Dispatchers.IO) {
        file.readText()
    }

    suspend fun exists(file: File): Boolean = withContext(Dispatchers.IO) {
        file.exists()
    }
}
