package org.avventomedia.app.telefyna.modal

import java.text.SimpleDateFormat
import java.util.Calendar

data class Playlist(
    private val DATE_FORMAT: String = "dd-MM-yyyy",

    var active: Boolean = true,
    // lastModified date: Date#toLocaleString
    var lastModified: String? = null,
    var name: String? = null,
    var description: String? = null,
    // preview web color
    var color: String? = null,

    // graphics
    var graphics: Graphics? = null,

    /*
     * Each playlist can access 3 bumper folders and bumpers are only for local non-resuming playlists;
     *  general (can be disabled by setting playingGeneralBumpers = false),
     *  specialBumpers
     *  one named after urlOrFolder
     */
    var playingGeneralBumpers: Boolean = false,
    // a name for folder in bumpers for special ones, this can be shared by other playlists by using the same name
    var specialBumperFolder: String? = null,
    var type: Type = Type.ONLINE,
    /*
     * set url for non-local folder or local folder where files should be added in the order with which they should play
     * use subfolders named in alphabetical order and symbolic links for fill ups
     * to maintain an order when playing, name programs or folders alphabetically
     */
    var urlOrFolder: String? = null,
    var usingExternalStorage: Boolean = false,
    // days of the week [1-7=Sun-Sat]: if null, runs daily
    var days: Array<Int>? = null,
    // dates to schedule for, must be in DATE_FORMAT(dd-MM-yyyy)
    var dates: Array<String>? = null,
    // time to start stream in (HH:mm)
    var start: String? = null,
    // index to a playlist count from top this is scheduling, must be above it. use only with day, repeats and start fields
    var schedule: Int? = null,
    // Index of playlist to replace with when empty
    var emptyReplacer: Int? = null,
    var seekTo: Seek = Seek(0, 0),
    var repeat: Repeat? = null
) {

    val isPlayingGeneralBumpers: Boolean
        get() = playingGeneralBumpers

    val isUsingExternalStorage: Boolean
        get() = usingExternalStorage

    fun isStarted(): Boolean {
        val current = Calendar.getInstance()
        val (hour, min) = start?.split(":")?.map { it.toInt() } ?: return false
        return hour < current[Calendar.HOUR_OF_DAY] || (hour == current[Calendar.HOUR_OF_DAY] && min <= current[Calendar.MINUTE])
    }

    fun getScheduledTime(): Long {
        val time = getStartTime() ?: return 0
        val hour = time[Calendar.HOUR_OF_DAY]
        val min = time[Calendar.MINUTE]

        if (hour == 0 && min == 0) { // midnight
            time[Calendar.SECOND] = 5 // at midnight, switch after 5 seconds since maintenance scheduler runs at 0 seconds
        }
        time[Calendar.MILLISECOND] = 0
        return time.timeInMillis
    }

    fun getStartTime(): Calendar? {
        val start = start ?: return null
        if (start.isNotBlank()) {
            val startTime = Calendar.getInstance()
            val (hour, min) = start.split(":").map { it.toInt() }
            startTime.set(Calendar.HOUR_OF_DAY, hour)
            startTime.set(Calendar.MINUTE, min)
            startTime.set(Calendar.SECOND, 0)
            startTime.set(Calendar.MILLISECOND, 0)
            return startTime
        }
        return null
    }

    fun scheduledToday(): Boolean {
        if (active == null || !active || start.isNullOrBlank()) {
            return false
        } else if (days.isNullOrEmpty()) {
            return true
        }
        val now = Calendar.getInstance()
        val dateFormat = SimpleDateFormat(DATE_FORMAT)
        val playoutDays = days?.toList() ?: emptyList()
        val playoutDates = dates?.toList() ?: emptyList()
        val dayScheduled = playoutDays.contains(now[Calendar.DAY_OF_WEEK])
        val dateScheduled = playoutDates.contains(dateFormat.format(now.time))
        return dayScheduled || dateScheduled
    }

    // only overrides days, dates and start but maintains the rest
    fun schedule(parent: Playlist): Playlist {
        type = parent.type
        name = parent.name
        description = parent.description
        urlOrFolder = parent.urlOrFolder
        usingExternalStorage = parent.usingExternalStorage
        playingGeneralBumpers = parent.playingGeneralBumpers
        specialBumperFolder = parent.specialBumperFolder
        emptyReplacer = parent.emptyReplacer
        color = parent.color
        active = when {
            parent.active == false -> false
            active == null -> parent.active
            else -> active
        }
        return this
    }

    // if playlist is resuming, no bumpers play; next plays next program, same plays the former uncompleted else exact time is resumed
    fun isResuming(): Boolean {
        return type.name.startsWith(Type.LOCAL_RESUMING.name)
    }

    enum class Type {
        ONLINE, // An Online streaming playlist using a stream URL
        LOCAL_SEQUENCED, // A local playlist starting from the first to the last alphabetical program by file naming
        LOCAL_RANDOMIZED, // A local playlist randomly selecting programs
        LOCAL_RESUMING, // A local playlist resuming from the previous program at exact stopped time
        LOCAL_RESUMING_SAME, // A local playlist restarting the previous non-completed program on the next playout
        LOCAL_RESUMING_NEXT, // A local playlist resuming from the next program
        LOCAL_RESUMING_ONE // A local one program selection playlist resuming from the next program
    }

    enum class Repeat {
        DAILY, WEEKLY, MONTHLY, QUARTERLY, ANNUALLY
    }
}
