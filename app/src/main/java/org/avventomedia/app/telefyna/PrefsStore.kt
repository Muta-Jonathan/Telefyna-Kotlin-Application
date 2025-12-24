package org.avventomedia.app.telefyna

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrefsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("telefyna_state", Context.MODE_PRIVATE)

    data class PlaybackState(
        val playlistIndex: Int = 0,
        val mediaItemIndex: Int = 0,
        val seekPosition: Long = 0L
    )

    suspend fun save(state: PlaybackState) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putInt("playlistIndex", state.playlistIndex)
            .putInt("mediaItemIndex", state.mediaItemIndex)
            .putLong("seekPosition", state.seekPosition)
            .apply()
    }

    suspend fun load(): PlaybackState = withContext(Dispatchers.IO) {
        PlaybackState(
            playlistIndex = prefs.getInt("playlistIndex", 0),
            mediaItemIndex = prefs.getInt("mediaItemIndex", 0),
            seekPosition = prefs.getLong("seekPosition", 0L)
        )
    }
}
