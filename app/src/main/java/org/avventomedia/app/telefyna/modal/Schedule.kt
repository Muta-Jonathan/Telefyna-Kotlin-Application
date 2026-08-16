package org.avventomedia.app.telefyna.modal

import com.google.gson.annotations.SerializedName
import java.util.Calendar

data class Schedule(
    @SerializedName("schedule", alternate = ["playlistId"])
    var playlistId: String? = null,
    var name: String? = null,
    var active: Boolean = true,
    var start: String? = null,
    // days of the week [1-7=Sun-Sat]: if null, runs daily
    var days: Array<Int>? = null,
    // dates to schedule for, must be in dd-MM-yyyy format
    var dates: Array<String>? = null,
    var color: String? = null,
    var type: Playlist.Type? = null,
    var graphics: Graphics? = null,
    var emptyReplacer: Int? = null,
    var seekTo: Seek? = null
) {
    fun scheduledToday(): Boolean {
        if (!active) return false
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val todayDate = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(cal.time)

        val hasDates = dates != null && dates!!.isNotEmpty()
        val hasDays = days != null && days!!.isNotEmpty()

        if (hasDates && dates!!.contains(todayDate)) return true
        if (hasDays && days!!.contains(today)) return true

        // If it has no restrictions, it runs daily
        if (!hasDates && !hasDays) return true

        return false
    }

    fun isStarted(): Boolean {
        val current = Calendar.getInstance()
        val (hour, min) = start?.split(":")?.map { it.toInt() } ?: return false
        return hour < current[Calendar.HOUR_OF_DAY] || (hour == current[Calendar.HOUR_OF_DAY] && min <= current[Calendar.MINUTE])
    }

    fun getScheduledTime(): Long {
        val (hour, min) = start?.split(":")?.map { it.toInt() } ?: return 0L
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis
    }
}
