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
        REPORT_GENERATED("Broadcast report ready: %s (%s)"),
        CONFIG_INSTALLED("No configuration found: installed the default configuration and folders in %s. Add videos to playlist/default and playlist/fillers"),
        MAINTENANCE("Ran maintenance"),
        MAINTENANCE_CLEANUP("Deleted %d audit logs older than %d days"),
        CONNECTING("Connecting"),
        METRICS("%s"),
        ERROR("%s"),
        CACHE_NOW_PLAYING_RESUME("Playlist: %s will next be resuming after program: %s at: %s"),
        RETRIEVE_NOW_PLAYING_RESUME("Resuming Playlist: %s program: %s at: %d"),
        RESUME_REMAPPED("Playlist: %s folder changed; resume point moved from position %d to %d (%s)"),
        STUCK("Relaunching having been stuck for %d seconds"),
        EMPTY_FILLERS("There are no fillers installed"),
        CRASH("Relaunched Telefyna after crash because of: %s"),
        INTERNET_RESTORED("Internet has been restored"),
        BACK_UP("Running backup"),
        RESTARTING("Restarting Telefyna"),
        TIME_CHANGED("Time changed, rescheduling: %s"),
        SLATE_ON("Showing emergency slate: %s"),
        SLATE_OFF("Emergency slate cleared, back on air"),
        SAFE_MODE("Crash loop: %d crashes in %d minutes, starting in safe mode %s"),
        SAFE_MODE_OFF("No crash for %d minutes, leaving safe mode"),

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
        PLAYLIST_EXHAUSTED("$SPLITTER] Exhausted all media items in folder for playlist: %s. Last played item: %s. Switching to filler."),
        PLAYLIST_ERROR("$SPLITTER] Error playing playlist: %s"),
        PLAYLIST_PRE_APPENDED("Queued next playlist for gapless transition: %s"),
        PLAYLIST_GAPLESS("$SPLITTER] Gapless transition from playlist: %s to: %s"),
        RANDOM_FOLDER_PICKED("Playlist: %s picked folder: %s (of %d)"),
        SCHEDULE_CLASH("Slots clash at %s: airing %s instead of %s"),
        PLAYLIST_CARRY_OVER("Continuing playlist: %s past midnight until it ends or the next slot starts"),

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
                HEARTBEAT, KEY_PRESS, CONFIGURATION, CONFIG_INSTALLED, REPORT_GENERATED, MAINTENANCE, MAINTENANCE_CLEANUP,
                METRICS, ERROR, CACHE_NOW_PLAYING_RESUME, RETRIEVE_NOW_PLAYING_RESUME, RESUME_REMAPPED,
                STUCK, INTERNET_RESTORED, EMPTY_FILLERS, CRASH, BACK_UP,
                RESTARTING, TIME_CHANGED, SLATE_ON, SLATE_OFF, SAFE_MODE, SAFE_MODE_OFF
            )
            val schedulers = arrayOf(
                PLAYLIST, PLAYLIST_PLAY, PLAYLIST_SWITCH, PLAYING_NOW,
                PLAYLIST_EMPTY_PLAY, PLAYLIST_MODIFIED, PLAYLIST_ITEM_CHANGE,
                PLAYLIST_COMPLETED, PLAYLIST_ERROR, SCHEDULE_CLASH, RANDOM_FOLDER_PICKED, PLAYLIST_PRE_APPENDED, PLAYLIST_GAPLESS, PLAYLIST_CARRY_OVER, DISPLAY_LOGO_OFF, DISPLAY_LOGO_ON,
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
