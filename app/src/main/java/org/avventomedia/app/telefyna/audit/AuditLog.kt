package org.avventomedia.app.telefyna.audit

class AuditLog {
    companion object {
        private const val SEPARATOR = "\n\n"
        private const val SPLITTER = "--------------------------------------------------------------"
        const val ENDPOINT = ".log"
    }

    enum class Event(val message: String) {
        // admin
        HEARTBEAT("TELEFYNA has been turned: %s"),
        KEY_PRESS("%s has been pressed"),
        CONFIGURATION("Initialized configurations"),
        MAINTENANCE("Ran maintenance"),
        CONNECTING("Connecting"),
        METRICS("%s"),
        ERROR("%s"),
        CACHE_NOW_PLAYING_RESUME("Playlist: %s will next be resuming after program: %s at: %s"),
        RETRIEVE_NOW_PLAYING_RESUME("Resuming Playlist: %s program: %s at: %d"),
        STUCK("Relaunching having been stuck for %d seconds"),
        EMPTY_FILLERS("There are no fillers installed"),
        CRASH("Relaunched Telefyna after crash because of: %s"),
        INTERNET_RESTORED("Internet has been restored"),
        BACK_UP("Running backup"),
        RESTARTING("Restarting Telefyna"),
        TIME_CHANGED("Time changed"),

        // scheduler
        PLAYLIST("$SPLITTER[ Preparing to play playlist: %s: %s"),
        PLAYLIST_PLAY("Playing Playlist: %s from: %s %s"),
        PLAYLIST_SWITCH("Switching to Playlist: %s from: %s %s"),
        PLAYLIST_LAST_ITEM("Now entering last item of playlist: %s"),
        PLAYING_NOW("Now playing"),
        PLAYLIST_EMPTY_PLAY("$SPLITTER Attempted to play an empty playlist: %s"),
        PLAYLIST_MODIFIED("Playlist: %s is resetting resuming since it was modified %s seconds ago"),
        PLAYLIST_ITEM_CHANGE("Playing playlist: %s now playing: %s"),
        PLAYLIST_COMPLETED("$SPLITTER] Completed playing playlist: %s"),
        PLAYLIST_ERROR("$SPLITTER] Error playing playlist: %s"),

        // player
        DISPLAY_LOGO_OFF("Turning OFF Logo"),
        DISPLAY_LOGO_ON("Turning ON Logo at the %s"),
        DISPLAY_NEWS_ON("Displaying news/info ticker with messages: %s"),
        DISPLAY_NEWS_OFF("Turning OFF news/info ticker"),
        DISPLAY_TIME_ON("Displaying time on ticker"),
        DISPLAY_TIME_OFF("Turning OFF time on ticker"),
        LOWER_THIRD_ON("Displaying %s lower third"),
        LOWER_THIRD_OFF("Turning OFF lower third"),
        DISPLAY_PROGRAM_WATERMARK_OFF("Turning OFF Program Watermark"),
        DISPLAY_LIVE_LOGO_ON("Turning ON Live Logo at the %s"),
        DISPLAY_REPEAT_PROGRAM_WATERMARK_ON("Turning ON Repeat Program Watermark"),
        FADE_PLAYED("Fade played, %s"),


        // system
        EMAIL("Sending email: '%s' to: %s %s"),
        NO_INTERNET("%s");

        fun formatMessage(): String {
            return String.format("$message $SEPARATOR ")
        }

        fun getCategory(): Category {
            val admins = arrayOf(
                HEARTBEAT, KEY_PRESS, CONFIGURATION, MAINTENANCE,
                METRICS, ERROR, CACHE_NOW_PLAYING_RESUME, RETRIEVE_NOW_PLAYING_RESUME,
                STUCK, INTERNET_RESTORED, EMPTY_FILLERS, CRASH, BACK_UP,
                RESTARTING, TIME_CHANGED
            )
            val schedulers = arrayOf(
                PLAYLIST, PLAYLIST_PLAY, PLAYLIST_SWITCH, PLAYING_NOW,
                PLAYLIST_EMPTY_PLAY, PLAYLIST_MODIFIED, PLAYLIST_ITEM_CHANGE,
                PLAYLIST_COMPLETED, PLAYLIST_ERROR, DISPLAY_LOGO_OFF, DISPLAY_LOGO_ON,
                DISPLAY_NEWS_ON, DISPLAY_NEWS_OFF, LOWER_THIRD_ON, LOWER_THIRD_OFF,
                DISPLAY_PROGRAM_WATERMARK_OFF, DISPLAY_LIVE_LOGO_ON,
                DISPLAY_REPEAT_PROGRAM_WATERMARK_ON
            )

            return when {
                admins.contains(this) -> Category.ADMIN
                schedulers.contains(this) -> Category.BROADCAST
                else -> Category.SYSTEM
            }
        }

        enum class Category {
            ADMIN, BROADCAST, SYSTEM
        }
    }
}
