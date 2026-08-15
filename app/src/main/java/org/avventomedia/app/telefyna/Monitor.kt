package org.avventomedia.app.telefyna

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent

import android.content.Context
import android.content.Intent

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.StringUtils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.listen.Maintenance
import org.avventomedia.app.telefyna.listen.TelefynaUnCaughtExceptionHandler
import org.avventomedia.app.telefyna.modal.Config
import org.avventomedia.app.telefyna.modal.Graphics
import org.avventomedia.app.telefyna.modal.LowerThird
import org.avventomedia.app.telefyna.modal.News
import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.modal.Seek
import org.avventomedia.app.telefyna.player.TelefynaRenderersFactory
import org.avventomedia.app.telefyna.ticker.TickerAdapter
import org.avventomedia.app.telefyna.ticker.TickerItem
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

@UnstableApi
class Monitor : AppCompatActivity(), PlayerNotificationManager.NotificationListener, Player.Listener {

    companion object {
        const val PREFERENCES = "TelefynaPrefs"
        private const val PLAYLIST_PLAY = "PLAYLIST_PLAY"
        private const val PLAYLIST_LAST_MODIFIED = "PLAYLIST_LAST_MODIFIED"
        private const val PLAYLIST_LAST_PLAYED = "PLAYLIST_LAST_PLAYED"
        private const val PLAYLIST_SEEK_TO = "PLAYLIST_SEEK_TO"
        private const val PLAYLIST_PLAY_FORMAT = "%s-%d"
        private const val REQUEST_CODE_PERMISSIONS = 123
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MANAGE_STORAGE_REQUEST_CODE = 101
        var instance: Monitor? = null // for player am using media3
        private const val KEEP_ON_AIR_ACTION = "org.avventomedia.app.telefyna.KEEP_ON_AIR"
        private const val CROSS_FADE_DURATION = 1000L // Reduce fade duration for faster switching to 2seconds
        private val animationHandler = Handler(Looper.getMainLooper())
        // Define a reusable Gson instance outside the function to avoid repeated creation
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val possibleExtensions = listOf("webp", "png", "jpg", "gif")
        private val durationCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    }

    private var isMaintenanceStarted = false
    private var activeLogoState: String? = null
    private var activeTickerState: String? = null
    private lateinit var sharedPreferences: SharedPreferences

    var configuration: Config? = null
        private set

    var alarmManager: AlarmManager? = null
        private set

    private var handler: Handler? = null

    var maintenanceHandler: Handler? = null
        private set

    var maintenance: Maintenance? = null

    private var nowPlayingIndex: Int? = null
    private var failedBecauseOfInternetIndex: Int? = null

    private var player: ExoPlayer? = null
    private var previousPlayer: ExoPlayer? = null
    private var currentPlaylist: Playlist? = null
    private var playlistByIndex: MutableList<Playlist> = mutableListOf()
    private var programItems: MutableList<MediaItem> = mutableListOf()
    private lateinit var tickerRecyclerView: RecyclerView
    private lateinit var tickerAdapter: TickerAdapter
    private var lowerThirdView: VideoView? = null

    private var lowerThirdLoop = 1
    private var offAir = false
    private var fillingForLackOfInternet = false
    private var nowProgramItem: Int? = 0
    private var startOnePlayProgramItem: Int? = null


    var dateFormat: SimpleDateFormat? = null
        private set

    fun addPlayListByIndex(playlist: Playlist) {
        playlistByIndex.add(playlist)
    }

    // This is the first default playlist, it plays whenever automation is disabled or nothing is scheduled/available
    fun getFirstDefaultIndex(): Int {
        return 0
    }

    // This is the second default playlist, it plays whenever there is no internet connection
    fun getSecondDefaultIndex(): Int {
        return 1
    }

    private fun resetTrackingNowPlaying(index: Int) {
        trackingNowPlaying(index, -1, false)
    }

    private fun playlistModified(index: Int): Long {
        return getLastModifiedFor(index) - getSharedPlaylistLastModified(index)
    }

    private fun trackingNowPlaying(index: Int, seekTo: Long, noProgramTransition: Boolean) {
        if (playlistByIndex[index].isResuming()) {
            cachePlayingAt(index, seekTo, noProgramTransition)
        }
    }

    private fun cachePlayingAt(index: Int, seekTo: Long, noProgramTransition: Boolean) {
        val at = if (Playlist.Type.LOCAL_RESUMING_ONE == currentPlaylist?.type && startOnePlayProgramItem != null) {
            0
        } else {
            nowProgramItem ?: 0
        }

        var atValue = if (Playlist.Type.LOCAL_RESUMING_ONE == currentPlaylist?.type && startOnePlayProgramItem != null) {
            startOnePlayProgramItem ?: 0
        } else {
            nowProgramItem ?: 0
        }

        atValue = if (noProgramTransition && Playlist.Type.LOCAL_RESUMING_ONE != currentPlaylist?.type) {
            atValue - 1
        } else {
            atValue
        }

        val programName = getMediaItemName(programItems[at])
        if (programName.isNotBlank()) { // exclude bumpers
            val editor = sharedPreferences.edit()
            editor.putInt(getPlaylistPlayKey(index), atValue)
            editor.putLong(getPlaylistSeekTo(index), seekTo)
            editor.putLong(getPlaylistLastModified(index), getLastModifiedFor(index))
            editor.putString(getPlaylistLastPlayed(index),
                dateFormat?.format(Calendar.getInstance().time)
            )
            editor.apply() // Use apply instead of commit for asynchronous saving
            Logger.log(AuditLog.Event.CACHE_NOW_PLAYING_RESUME, getPlayingAtIndexLabel(index), programName, "$at-$seekTo")
        }
    }

    private fun getSharedPlaylistMediaItem(index: Int): Int {
        return sharedPreferences.getInt(getPlaylistPlayKey(index), 0)
    }

    private fun getSharedPlaylistLastModified(index: Int): Long {
        return sharedPreferences.getLong(getPlaylistLastModified(index), getLastModifiedFor(index))
    }

    private fun getSharedPlaylistSeekTo(index: Int): Long {
        return sharedPreferences.getLong(getPlaylistSeekTo(index), 0)
    }

    /**
     * TODO support more periods such as weekly, monthly etc
     * @param index
     * @param repeat
     * @return
     */
    private fun canResume(index: Int, repeat: Playlist.Repeat): Boolean {
        val nowStr = dateFormat?.format(Calendar.getInstance().time) ?: ""
        return try {
            val lastPlayed = Calendar.getInstance()
            val today = Calendar.getInstance()
            
            val lastPlayedStr = sharedPreferences.getString(getPlaylistLastPlayed(getPlaylistIndex(index)), nowStr) ?: nowStr
            val lastPlayedDate = dateFormat?.parse(lastPlayedStr) ?: Calendar.getInstance().time
            lastPlayed.time = lastPlayedDate

            val todayDate = dateFormat?.parse(nowStr) ?: Calendar.getInstance().time
            today.time = todayDate

            isRepeatable(repeat, lastPlayed, today)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isRepeatable(repeat: Playlist.Repeat, lastPlayed: Calendar, today: Calendar): Boolean {
        when (repeat) {
            Playlist.Repeat.DAILY -> {
                // maintain lastPlayed
            }
            Playlist.Repeat.WEEKLY -> lastPlayed.add(Calendar.WEEK_OF_MONTH, 1)
            Playlist.Repeat.MONTHLY -> lastPlayed.add(Calendar.MONTH, 1)
            Playlist.Repeat.QUARTERLY -> lastPlayed.add(Calendar.MONTH, 3)
            Playlist.Repeat.ANNUALLY -> lastPlayed.add(Calendar.YEAR, 1)
        }
        return today.after(lastPlayed)
    }

    @SuppressLint("DefaultLocale")
    private fun getPlaylistPlayKey(index: Int): String {
        return String.format(PLAYLIST_PLAY_FORMAT, PLAYLIST_PLAY, index)
    }

    @SuppressLint("DefaultLocale")
    private fun getPlaylistLastModified(index: Int): String {
        return String.format(PLAYLIST_PLAY_FORMAT, PLAYLIST_LAST_MODIFIED, index)
    }

    @SuppressLint("DefaultLocale")
    private fun getPlaylistSeekTo(index: Int): String {
        return String.format(PLAYLIST_PLAY_FORMAT, PLAYLIST_SEEK_TO, index)
    }

    @SuppressLint("DefaultLocale")
    private fun getPlaylistLastPlayed(index: Int): String {
        return String.format(PLAYLIST_PLAY_FORMAT, PLAYLIST_LAST_PLAYED, index)
    }

    @SuppressLint("MissingInflatedId", "UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.monitor)

        // HACK: Disable back press (Issue arises due to remote control by RustDesk)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing (Prevents back navigation)
            }
        })

        // handle any uncaught exception
        Thread.setDefaultUncaughtExceptionHandler(TelefynaUnCaughtExceptionHandler())
        if (intent.getBooleanExtra(TelefynaUnCaughtExceptionHandler.CRASH, false)) {
            intent.getStringExtra(TelefynaUnCaughtExceptionHandler.EXCEPTION)
                ?.let { Logger.log(AuditLog.Event.CRASH, it) }
        }

        instance = this
        dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        maintenance = Maintenance()
        maintenanceHandler = Handler()
        handler = Handler()
        sharedPreferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start the silent foreground service for 24/7 priority
        val serviceIntent = Intent(this, org.avventomedia.app.telefyna.listen.TelefynaForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // allow network etc actions since telefyna depends on all of these
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())

        // KeepOnAirReceiver is now manifest-registered (survives process death)

        // Initialize permissions and start maintenance only when permitted
        startMaintenanceIfPermitted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TelefynaUnCaughtExceptionHandler.CRASH, false)) {
            intent.getStringExtra(TelefynaUnCaughtExceptionHandler.EXCEPTION)
                ?.let { Logger.log(AuditLog.Event.CRASH, it) }
        }
    }

    /**
     * Returns the first location of app root directory on the system in precedence; external drive via usb, external sdcard, internal sdcard
     *
     * @return
     */
    private fun getAppRootDirectory(useExternalStorage: Boolean): File {
        val postfix = "/telefyna"
        if (useExternalStorage) {
            val mntUsb = "/mnt/usb"
            var storages = File(mntUsb).listFiles()
            if (storages == null) {
                storages = ContextCompat.getExternalFilesDirs(this, null)
            }
            storages.reverse() // Reverse the array
            for (storage in storages ?: emptyArray()) {
                storage?.let {
                    val occurrence = if (it.absolutePath.contains("emulated")) 4 else 3
                    val location = it.absolutePath.split("/").take(occurrence).joinToString("/")
                    return File(location + postfix)
                }
            }
        }
        return File(Environment.getExternalStorageDirectory().absolutePath + postfix)
    }

    private fun getRestartFile(): File {
        return File(getAuditFilePath(this,"restart.txt"))
    }

    private fun getRebootFile(): File {
        return File(getAuditFilePath(this,"reboot.txt"))
    }

    private fun getAuditConfigFile(): File {
        return File(getAuditFilePath(this,"config.json"))
    }

    private fun getBackupConfigFile(): File {
        return File(getAuditFilePath(this,"backupConfig.txt"))
    }

    private fun getBackupConfigResetFile(): File {
        return File(getAuditFilePath(this,"backupConfigReset.txt"))
    }

    private fun getReInitializerFile(): File {
        return File(getAuditFilePath(this,"init.txt"))
    }

    private fun getBumperDirectory(useExternalStorage: Boolean): String {
        return "${getProgramsFolderPath(useExternalStorage)}${File.separator}bumper"
    }

    fun getProgramsFolderPath(useExternalStorage: Boolean): String {
        return getAppRootDirectory(useExternalStorage).absolutePath
    }

    private fun getLowerThirdDirectory(useExternalStorage: Boolean): String {
        return "${getProgramsFolderPath(useExternalStorage)}${File.separator}lowerThird"
    }

    private fun getPlaylistDirectory(useExternalStorage: Boolean): String {
        return "${getProgramsFolderPath(useExternalStorage)}${File.separator}playlist"
    }

    private fun getWatermarkDirectory(useExternalStorage: Boolean): String {
        return "${getProgramsFolderPath(useExternalStorage)}${File.separator}watermark"
    }

    fun getConfigFile(): String {
        return "${getProgramsFolderPath(false)}${File.separator}config.json"
    }

    fun getAuditFilePath(context: Context, name: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For API 30 and above, use getExternalFilesDir() for Scoped Storage
            val directory = File(context.getExternalFilesDir(null), "telefynaAudit")
            if (!directory.exists()) {
                directory.mkdirs() // Create the directory if it doesn't exist
            }
            "${directory.absolutePath}/$name"
        } else {
            // For API 29 and lower, use getExternalStorageDirectory() (deprecated)
            val directory = File(Environment.getExternalStorageDirectory(), "telefynaAudit")
            if (!directory.exists()) {
                directory.mkdirs() // Create the directory if it doesn't exist
            }
            "${directory.absolutePath}/$name"
        }
    }

    fun getAuditLogsFilePath(name: String): String {
        return getAuditFilePath(this,"${name}${AuditLog.ENDPOINT}")
    }

    fun initialise() {
        playlistByIndex = mutableListOf()
        try {
            FileReader(getConfigFile()).use { reader ->
                configuration = Gson().fromJson(BufferedReader(reader), Config::class.java)
                Logger.log(AuditLog.Event.CONFIGURATION)
            }
        } catch (e: IOException) {
            Logger.log(AuditLog.Event.ERROR, e.message ?: "Unknown error")
        }
    }

    private fun cacheNowPlaying(noProgramTransition: Boolean) {
        val playerView = getPlayerView(false)
        val now = nowPlayingIndex?.let { getPlaylistIndex(it) }
        if (now != null && player != null) {
            trackingNowPlaying(now, player!!.currentPosition, noProgramTransition)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val renderersFactory = instance?.let { 
            TelefynaRenderersFactory(it).apply {
                setEnableAudioTrackPlaybackParams(true)
                setEnableDecoderFallback(true)
            }
        }
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = if (renderersFactory != null) {
            ExoPlayer.Builder(context, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        } else {
            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }

        return player as ExoPlayer
    }

    private fun addBumpers(bumpers: MutableList<MediaItem>, bumperFolder: File, addedFirstItem: Boolean) {
        if (bumperFolder.exists() && bumperFolder.listFiles()?.isNotEmpty() == true) {
            currentPlaylist?.let {
                Utils.setupLocalPrograms(bumpers, bumperFolder, addedFirstItem,
                    it
                )
                bumpers.reverse()
            }
        }
    }

    private fun getPlaylistIndex(index: Int): Int {
        return index.let {
            playlistByIndex[it].schedule ?: it
        }
    }

    private fun samePlaylistPlaying(index: Int): Boolean {
        return nowPlayingIndex?.let { now ->
            if (now >= playlistByIndex.size || index >= playlistByIndex.size) return false
            val current = getPlaylistIndex(now)
            val next = getPlaylistIndex(index)
            current == next
        } ?: false
    }

    private fun playTheSame(index: Int): Boolean {
        return player?.isPlaying == false && samePlaylistPlaying(index)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    fun switchNow(index: Int, isCurrentSlot: Boolean, context: Context) {
        val playlist = playlistByIndex[index]
        // Use the static Gson instance to reduce object creation
        Logger.log(AuditLog.Event.PLAYLIST, getPlayingAtIndexLabel(index), gson.toJson(playlist))

        // Re-maintain if init file exists; drop it and reload schedule
        if (getReInitializerFile().exists()) {
            performRealtimeConfigReload()
            return
        }

        if (!samePlaylistPlaying(index) || playTheSame(index)) { // Leave current program to proceed if it's the same being loaded
            // Setup objects; skip playlist with nothing to play
            nowPlayingIndex = index
            currentPlaylist = playlist
            programItems = maintenance?.retrievePrograms(currentPlaylist) as MutableList<MediaItem>

            val firstDefaultIndex = getFirstDefaultIndex()
            val secondDefaultIndex = getSecondDefaultIndex()

            if (currentPlaylist!!.type == Playlist.Type.ONLINE && !Utils.internetConnected() && secondDefaultIndex != nowPlayingIndex) {
                (configuration?.wait)?.times(1000L)?.let {
                    instance?.handler?.removeCallbacksAndMessages(null) // Cleanup before scheduling delay
                    lifecycleScope.launch {
                        delay(it)
                        if (Utils.internetConnected()) {
                            try {
                                switchNow(index, isCurrentSlot, context)
                            } catch (e: Exception) {
                                e.message?.let { it1 -> Logger.log(AuditLog.Event.PLAYLIST_ERROR, it1) }
                                switchNow(getSecondDefaultIndex(), isCurrentSlot, context)
                            }
                        } else {
                            fillingForLackOfInternet = true
                            failedBecauseOfInternetIndex = nowPlayingIndex
                            switchNow(getSecondDefaultIndex(), isCurrentSlot, context)
                        }
                    }
                }
            } else {
                keepBroadcasting()
                if (secondDefaultIndex == nowPlayingIndex && (currentPlaylist!!.type == Playlist.Type.ONLINE && !Utils.internetConnected() || currentPlaylist!!.type != Playlist.Type.ONLINE && programItems.isEmpty())) {
                    Logger.log(AuditLog.Event.EMPTY_FILLERS)
                    switchNow(firstDefaultIndex, isCurrentSlot, context)
                    return
                }
                if (programItems.isEmpty()) {
                    Logger.log(AuditLog.Event.PLAYLIST_EMPTY_PLAY, getPlayingAtIndexLabel(nowPlayingIndex))
                    switchNow(currentPlaylist!!.emptyReplacer ?: firstDefaultIndex, isCurrentSlot, context)
                    return
                }

                if (player == null) {
                    player = buildPlayer(context)
                } else {
                    player!!.stop()
                    player!!.clearMediaItems()
                }

                        // Reset tracking now playing if the playlist programs were modified
                        val modifiedOffset = playlistModified(nowPlayingIndex!!)
                        if (modifiedOffset > 0) {
                            Logger.log(AuditLog.Event.PLAYLIST_MODIFIED, getPlayingAtIndexLabel(nowPlayingIndex), modifiedOffset / 1000)
                            resetTrackingNowPlaying(nowPlayingIndex!!)
                        }

                        nowProgramItem = currentPlaylist!!.seekTo.program
                        startOnePlayProgramItem = null
                        var nowPosition = currentPlaylist!!.seekTo.position

                        if (currentPlaylist!!.type != Playlist.Type.ONLINE) {
                            // Resume local resumable programs
                            if (currentPlaylist!!.isResuming()) {
                                val previousProgram = getSharedPlaylistMediaItem(getPlaylistIndex(nowPlayingIndex!!))
                                var previousSeekTo = getSharedPlaylistSeekTo(getPlaylistIndex(nowPlayingIndex!!))
                                if (nowProgramItem == 0 && (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_NEXT || currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_ONE)) {
                                    if (previousProgram == -1 || previousProgram == (programItems.size).minus(1)) {
                                        nowProgramItem = 0
                                        previousSeekTo = 0
                                    } else if (currentPlaylist!!.repeat?.let { canResume(nowPlayingIndex!!, it) } == true) {
                                        nowProgramItem = previousProgram.plus(1)
                                        previousSeekTo = 0
                                    } else {
                                        nowProgramItem = previousProgram
                                    }
                                } else if (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_SAME) {
                                    nowProgramItem = previousProgram
                                    previousSeekTo = 0
                                } else if (nowProgramItem == 0 && currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING) {
                                    nowProgramItem = previousProgram
                                }

                                currentPlaylist!!.name?.let {
                                    nowProgramItem?.let { it1 -> programItems[it1] }?.let { it2 -> getMediaItemName(it2) }?.let { it3 ->
                                        Logger.log(AuditLog.Event.RETRIEVE_NOW_PLAYING_RESUME, it, it3, previousSeekTo)
                                    }
                                }
                                if (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_ONE) {
                                    val item = nowProgramItem?.let { programItems[it] }
                                    programItems.clear()
                                    if (item != null) {
                                        programItems.add(item)
                                    }
                                    startOnePlayProgramItem = nowProgramItem
                                    nowProgramItem = 0
                                }
                                if (currentPlaylist!!.isResuming()) {
                                    nowPosition = if (nowPosition > 0) nowPosition else previousSeekTo
                                }
                            } else {
                                val bumperFolder = getBumperDirectory(currentPlaylist!!.isUsingExternalStorage)
                                val generalBumpersIntro = mutableListOf<MediaItem>()
                                val generalBumpersOutro = mutableListOf<MediaItem>()
                                val specialBumpersIntro = mutableListOf<MediaItem>()
                                val specialBumpersOutro = mutableListOf<MediaItem>()
                                val playListIntroBumpers = mutableListOf<MediaItem>()
                                val playListOutroBumpers = mutableListOf<MediaItem>()
                                // Prepare intro general bumpers
                                if (currentPlaylist!!.isPlayingGeneralBumpers) {
                                    addBumpers(generalBumpersIntro, File("$bumperFolder${File.separator}General-INTRO"), false)
                                    addBumpers(generalBumpersOutro, File("$bumperFolder${File.separator}General-OUTRO"), false)
                                }
                                val specialBumperFolder = currentPlaylist!!.specialBumperFolder
                                if (!specialBumperFolder.isNullOrBlank()) {
                                    addBumpers(specialBumpersIntro, File("$bumperFolder${File.separator}$specialBumperFolder-INTRO"), false)
                                    addBumpers(specialBumpersOutro, File("$bumperFolder${File.separator}$specialBumperFolder-OUTRO"), false)
                                }

                                // Prepare playlist specific bumpers
                                addBumpers(playListIntroBumpers, File("$bumperFolder${File.separator}${currentPlaylist!!.urlOrFolder?.split("#")
                                    ?.get(0)}-INTRO"), false)
                                addBumpers(playListOutroBumpers, File("$bumperFolder${File.separator}${currentPlaylist!!.urlOrFolder?.split("#")
                                    ?.get(0)}-OUTRO"), false)

                                // Add intro bumpers
                                val currentBumpers = mutableListOf<MediaItem>().apply {
                                    addAll(generalBumpersIntro)
                                    addAll(playListIntroBumpers)
                                    addAll(specialBumpersIntro)
                                }

                                programItems.addAll(0, currentBumpers)

                                // Add outro bumpers
                                val allOutroBumpers = mutableListOf<MediaItem>().apply {
                                    addAll(specialBumpersOutro)
                                    addAll(playListOutroBumpers)
                                    addAll(generalBumpersOutro)
                                }

                                // Look ahead to see what time the very next scheduled playlist is set to start today
                                val nextScheduledTime = getNextScheduledTime(currentPlaylist!!)

                                // If this is a scheduled playlist (not a filler), and there's another scheduled playlist after it today,
                                // we want to make sure the outro bumpers don't overrun into the next slot's time.
                                // If they overrun, they get abruptly cut off. It's better to drop bumpers that don't fit
                                // and let the player naturally switch to fillers for the remaining time.
                                if (isCurrentSlot && nextScheduledTime != null && currentPlaylist!!.getStartTime() != null && allOutroBumpers.isNotEmpty()) {
                                    val capturedProgramItems = programItems.toList()
                                    val capturedPlaylist = currentPlaylist

                                    // Evaluating bumper durations uses MediaMetadataRetriever, which can be extremely slow
                                    // (blocking the thread for tens of milliseconds per file). To prevent freezing the UI (ANR)
                                    // while starting playback, we calculate the remaining time asynchronously on an IO thread.
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        // Calculate the estimated wall-clock time the main program finishes
                                        var accumulatedTime = capturedPlaylist!!.getStartTime()!!.timeInMillis
                                        for (item in capturedProgramItems) {
                                            accumulatedTime += getDuration(item.mediaId)
                                        }

                                        // Only keep outro bumpers that can fully finish playing before the next scheduled slot starts
                                        val fittingOutroBumpers = mutableListOf<MediaItem>()
                                        for (bumper in allOutroBumpers) {
                                            val bumperDuration = getDuration(bumper.mediaId)
                                            if (accumulatedTime + bumperDuration <= nextScheduledTime) {
                                                fittingOutroBumpers.add(bumper)
                                                accumulatedTime += bumperDuration
                                            }
                                        }

                                        // Safely switch back to the main thread and dynamically append the fitting bumpers
                                        // into ExoPlayer's playlist queue while the main program is already playing.
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            if (player != null && currentPlaylist == capturedPlaylist) {
                                                programItems.addAll(fittingOutroBumpers)
                                                player!!.addMediaItems(fittingOutroBumpers)
                                            }
                                        }
                                    }
                                } else {
                                    // If this is just a filler or there's no upcoming schedule, append everything immediately
                                    programItems.addAll(allOutroBumpers)
                                }
                            }

                            if (isCurrentSlot && nowPlayingIndex != secondDefaultIndex) { // Not fillers
                                val seek = seekImmediateNonCompletedSlot(currentPlaylist!!, programItems)
                                if (seek != null) {
                                    nowProgramItem = seek.program
                                    nowPosition = seek.position
                                } else { // Slot is ended, switch to fillers
                                    Logger.log(AuditLog.Event.PLAYLIST_COMPLETED, getPlayingAtIndexLabel(nowPlayingIndex))
                                    switchNow(secondDefaultIndex, false, context)
                                    return
                                }
                            }
                        }

                        val playerView = getPlayerView(true)
                        playerView.player?.removeListener(this) // Remove old listener
                        // Load the new media items
                        programItems.let { player!!.setMediaItems(it) }
                        nowProgramItem?.let { player!!.seekTo(it, nowPosition) }
                        player!!.prepare()
                        // Fade-in for new player (if desired, otherwise skip)
                        if (!currentPlaylist!!.isResuming()) {
                            player!!.volume = 0f
                            val fadeInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = CROSS_FADE_DURATION
                                addUpdateListener { player?.volume = it.animatedValue as Float }
                            }
                            fadeInAnimator.start()
                            Logger.log(AuditLog.Event.FADE_PLAYED, "fade in transition played")
                        } else {
                            player!!.volume = 1f
                        }
                        instance?.let { player!!.addListener(it) }
                        player!!.playWhenReady = true
                        playerView.player = player // Explicitly attach new player to PlayerView
                        player!!.addListener(this) // Add listener once
                        nowProgramItem?.let { programItems[it] }?.let { getMediaItemName(it) }?.let {
                            Logger.log(
                                if (isCurrentSlot) AuditLog.Event.PLAYLIST_PLAY else AuditLog.Event.PLAYLIST_SWITCH,
                                getNowPlayingPlaylistLabel(),
                                Utils.formatDuration(nowPosition),
                                it
                            )
                        }
                        // Log now playing
                        cacheNowPlaying(false)
                        triggerGraphics(nowPosition)
            }
        } else {
            // Already playing this playlist. Just update reference to apply any graphics/config changes
            nowPlayingIndex = index
            currentPlaylist = playlist
            triggerGraphics(player?.currentPosition ?: 0L)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val playerView = getPlayerView(false)
        val current = playerView.player
        if (current == null || player != current) { // Change of player is proof of a switch
            while (player?.isPlaying == true) {
                endPlayerSafely(current)
                Logger.log(AuditLog.Event.PLAYING_NOW)
                playerView.player = player
                break
            }
        }
        if (previousPlayer != null && previousPlayer != current) { // Switching too fast, consider on in view
            endPlayerSafely(previousPlayer)
        }
    }

    // Retrieve video duration in milliseconds safely without creating MediaPlayer decoder instances
    private fun getDuration(path: String): Long {
        val cleanPath = path.replace("file://", "")
        val file = File(cleanPath)
        if (!file.exists()) return 0L
        val lastModified = file.lastModified()

        durationCache[cleanPath]?.let { (cachedTime, cachedDuration) ->
            if (cachedTime == lastModified) {
                return cachedDuration
            }
        }

        var duration = 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(cleanPath)
            val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (timeStr != null) {
                duration = timeStr.toLong()
            }
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "Error reading duration for $cleanPath: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        if (duration > 0) {
            durationCache[cleanPath] = Pair(lastModified, duration)
        }
        return duration
    }

    /**
     * Determines the start time (in ms) of the very next scheduled playlist for today.
     * This is used to ensure the current playlist doesn't overrun its allotted time slot.
     */
    private fun getNextScheduledTime(currentPlaylist: Playlist): Long? {
        val currentStart = currentPlaylist.getStartTime()?.timeInMillis ?: return null
        var nextTime: Long? = null
        for (playlist in playlistByIndex) {
            if (playlist.scheduledToday()) {
                val start = playlist.getStartTime()?.timeInMillis
                if (start != null && start > currentStart) {
                    if (nextTime == null || start < nextTime) {
                        nextTime = start
                    }
                }
            }
        }
        return nextTime
    }

    private fun seekImmediateNonCompletedSlot(playlist: Playlist, mediaItems: List<MediaItem>): Seek? {
        if (playlist.type == Playlist.Type.ONLINE) {
            return Seek(0, 0L)
        }
        val start = playlist.getStartTime()
        if (start != null) {
            var currentItemStartTime = start.timeInMillis
            val now = Calendar.getInstance().timeInMillis
            mediaItems.forEachIndexed { i, mediaItem ->
                val duration = getDuration(mediaItem.mediaId)
                if ((currentItemStartTime + duration) > now || duration == 0L) {
                    val seekTime = now - currentItemStartTime
                    return Seek(i, if (seekTime < 0) 0L else seekTime)
                }
                currentItemStartTime += duration
            }
        }
        // unseekable, slot is ended
        return null
    }

    @OptIn(UnstableApi::class)
    private fun getPlayerView(reset: Boolean): PlayerView {
        val playerView: PlayerView = findViewById(R.id.player)
        if (reset) {
            playerView.showController()
            playerView.invalidate()
        }
        return playerView
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPlaybackStateChanged(state: Int) {
        nowPlayingIndex?.let {
            when (state) {
                Player.STATE_ENDED -> {
                    val playlist = playlistByIndex[it]
                    val isFiniteType = playlist.type == Playlist.Type.LOCAL_SEQUENCED ||
                            playlist.type == Playlist.Type.LOCAL_RANDOMIZED ||
                            playlist.isResuming()
                    val isAtLastItem = player?.currentMediaItemIndex == (programItems.size - 1)

                    // Only switch to fillers when a truly finite playlist finishes
                    if (isFiniteType && isAtLastItem) {
                        val lastItemName = player?.currentMediaItemIndex?.let { programItems.getOrNull(it) }?.let { getMediaItemName(it) } ?: "Unknown"
                        Logger.log(AuditLog.Event.PLAYLIST_EXHAUSTED, getPlayingAtIndexLabel(nowPlayingIndex), lastItemName)
                        switchNow(getSecondDefaultIndex(), false, this)
                        return
                    }
                }

                Player.STATE_BUFFERING -> {
                    if (currentPlaylist?.type == Playlist.Type.ONLINE) {
                        player?.seekTo(player!!.contentDuration) // hack
                    }
                    player?.playWhenReady = true // Ensure playback resumes smoothly
                }
            }
        }
    }

    private fun getMediaItemName(mediaItem: MediaItem): String {
        return try {
            URLDecoder.decode(mediaItem.mediaId.replace("file://", ""), "utf-8")
        } catch (e: UnsupportedEncodingException) {
            Logger.log(AuditLog.Event.ERROR, e.message ?: "Unknown error")
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        nowPlayingIndex?.let {
            val isFiniteType = currentPlaylist?.type == Playlist.Type.LOCAL_SEQUENCED ||
                    currentPlaylist?.type == Playlist.Type.LOCAL_RANDOMIZED ||
                    currentPlaylist?.isResuming() == true

            // ✅ We are *about to* play the final item — don't switch yet
            val aboutToPlayLast = nowProgramItem != null && nowProgramItem!! == programItems.size - 1
            val playlistName =  getNowPlayingPlaylistLabel()

            if (isFiniteType && aboutToPlayLast) {
                Logger.log(
                    AuditLog.Event.PLAYLIST_LAST_ITEM,
                    "$playlistName — waiting for STATE_ENDED to switch"
                )
            }

            // ✅ Only switch after final item has fully played — in STATE_ENDED (not here!)
            // So DO NOT switch here — only log

            // ✅ Now safely increment AFTER the check
            nowProgramItem = player?.currentMediaItemIndex
            cacheNowPlaying(false)

            mediaItem?.let { item ->
                Logger.log(
                    AuditLog.Event.PLAYLIST_ITEM_CHANGE,
                    getNowPlayingPlaylistLabel(),
                    getMediaItemName(item)
                )
                triggerRepeatWatermark(item)
            }
        }
    }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPlayerError(error: PlaybackException) {

        val currentItem = player?.currentMediaItem
        val filePath = currentItem?.localConfiguration?.uri?.path

        /** this only works if
         * A file gets deleted or unmounted during playback (e.g. USB drive ejected, SD card removed).
         * The playlist was valid at prepare() time, but the file vanishes after playback starts.
         */
        val isLocal = currentItem?.localConfiguration?.uri?.scheme?.let { it != "http" && it != "https" } ?: true
        if (isLocal && !filePath.isNullOrEmpty() && !File(filePath).exists()) {
            Logger.log(AuditLog.Event.ERROR, "Missing file detected: $filePath — switching to filler.")
            switchNow(getSecondDefaultIndex(), false, this)
            return
        }

        Logger.log(AuditLog.Event.ERROR, "${error.cause}: ${error.message}")
        currentPlaylist?.type?.name?.let { cacheNowPlaying(it.startsWith("LOCAL_RESUMING")) }

        // keep reloading existing program if internet is on and off
        when (error.cause?.cause) {
            is UnknownHostException, is IOException -> {
                Logger.log(AuditLog.Event.NO_INTERNET, "Failing to play program because of no internet connection")
                failedBecauseOfInternetIndex = nowPlayingIndex
                
                // Fall back to fillers if the internet goes down, but only if fillers are actually installed
                // (to prevent an infinite loop of bouncing between the online stream and empty fillers)
                if (nowPlayingIndex != getSecondDefaultIndex()) {
                    val fillersPlaylist = configuration?.playlists?.getOrNull(getSecondDefaultIndex())
                    if (fillersPlaylist != null && maintenance?.retrievePrograms(fillersPlaylist)?.isNotEmpty() == true) {
                        fillingForLackOfInternet = true
                        switchNow(getSecondDefaultIndex(), false, this)
                    }
                }
            }
            is UnrecognizedInputFormatException -> {
                Logger.log(AuditLog.Event.ERROR, "Broken video format detected: ${currentItem?.mediaId}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    handlePlaybackCrash(currentItem)
                }
            }
            is MediaCodecRenderer.DecoderInitializationException -> {
                Logger.log(AuditLog.Event.ERROR, "Decoder error on: ${currentItem?.mediaId}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    handlePlaybackCrash(currentItem)
                }
            }
            else -> {
                if (!player?.isPlaying!!) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        handlePlaybackCrash(currentItem)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handlePlaybackCrash(currentItem: MediaItem?) {
        if (currentPlaylist?.type == Playlist.Type.ONLINE) {
            if (nowPlayingIndex != getSecondDefaultIndex()) {
                Logger.log(AuditLog.Event.ERROR, "Switching to filler due to broken stream.")
                switchNow(getSecondDefaultIndex(), false, this)
            } else {
                nowPlayingIndex?.let { switchNow(it, false, this) }
            }
        } else if (player?.isCurrentWindowSeekable == true) {
            Logger.log(AuditLog.Event.ERROR, "Skipping corrupted local file: ${currentItem?.mediaId}")
            val nextItemIndex = (player?.currentMediaItemIndex ?: 0) + 1
            if (nextItemIndex < programItems.size) {
                player!!.seekTo(nextItemIndex, 0)
            } else {
                if (programItems.size <= 1) {
                    Logger.log(AuditLog.Event.ERROR, "Only 1 item in folder and it crashed. Switching to filler to prevent infinite loop.")
                    switchNow(getSecondDefaultIndex(), false, this)
                } else {
                    val isFiniteType = currentPlaylist?.type == Playlist.Type.LOCAL_SEQUENCED ||
                            currentPlaylist?.type == Playlist.Type.LOCAL_RANDOMIZED ||
                            currentPlaylist?.isResuming() == true
                    if (isFiniteType) {
                        val lastItemName = currentItem?.let { getMediaItemName(it) } ?: "Unknown"
                        Logger.log(AuditLog.Event.PLAYLIST_EXHAUSTED, getPlayingAtIndexLabel(nowPlayingIndex), "Corrupted: $lastItemName")
                        switchNow(getSecondDefaultIndex(), false, this)
                    } else {
                        player!!.seekTo(0, 0)
                    }
                }
            }
        } else if (nowPlayingIndex != getSecondDefaultIndex()) {
            switchNow(getSecondDefaultIndex(), false, this)
        }
    }


    private fun getPlayingAtIndexLabel(index: Int?): String {
        if (index == null || index >= playlistByIndex.size) return "Unknown #$index"
        val playlistName = playlistByIndex[getPlaylistIndex(index)].name
        return "$playlistName #$index"
    }

    private fun getNowPlayingPlaylistLabel(): String {
        val playlistName = nowPlayingIndex?.let { currentPlaylist?.name } ?: ""
        return "$playlistName #$nowPlayingIndex"
    }

    override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
        if (configuration?.isNotificationsDisabled == true) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
        }
    }

    fun endPlayerSafely(targetPlayer: Player?) {
        if (targetPlayer == null) return  // Avoid unnecessary execution
        try {
            targetPlayer.stop()  // Stop playback
            targetPlayer.clearMediaItems()  // Remove media items
            targetPlayer.release()  // Synchronously release ExoPlayer
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.PLAYLIST_ERROR, "${e.localizedMessage}")
        }
    }


    private fun shutDownHook() {
        Logger.log(AuditLog.Event.HEARTBEAT, "OFF")
    }

    override fun onDestroy() {
        super.onDestroy()
        shutDownHook()
        endPlayerSafely(player);
        endPlayerSafely(previousPlayer);

        maintenanceHandler?.removeCallbacksAndMessages(null)
        handler?.removeCallbacksAndMessages(null)
        // Cancel keepOnAir alarm
        val intent = Intent(KEEP_ON_AIR_ACTION)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.cancel(pendingIntent)

        Glide.get(this).clearMemory()
        Glide.get(this).trimMemory(TRIM_MEMORY_COMPLETE)

        if (instance == this) {
            instance = null
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            Glide.get(this).clearMemory()
            Glide.get(this).trimMemory(level)
        } catch (e: Exception) {
            // Ignore glide trim exceptions
        }
    }

    override fun onStop() {
        super.onStop()
        // DO NOT pause ExoPlayer on stop — Telefyna is a 24/7 broadcasting app
        // that must continue playing even when the Activity is in the background
        // (e.g. TV screen off, HDMI-CEC standby, display sleep)
    }

    private fun getLastModifiedFor(index: Int): Long {
        return getDirectoryFromPlaylist(playlistByIndex[getPlaylistIndex(index)]).lastModified()
    }

    fun getDirectoryFromPlaylist(playlist: Playlist, i: Int): File {
        return File(getPlaylistDirectory(playlist.isUsingExternalStorage) + File.separator + (playlist.urlOrFolder?.split("#")
            ?.get(i)
            ?.trim()))
    }

    private fun getDirectoryFromPlaylist(playlist: Playlist): File {
        return getDirectoryFromPlaylist(playlist, 0)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startMaintenanceIfPermitted() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            if (!isMaintenanceStarted) {
                isMaintenanceStarted = true
                maintenance?.run()
            }
        } else if (isAndroidTV()) {
            // Android TV / Google TV has no Settings UI for MANAGE_EXTERNAL_STORAGE.
            // The permission must be granted via ADB: adb shell appops set <package> MANAGE_EXTERNAL_STORAGE allow
            // Proceed with maintenance anyway — the app will handle missing files gracefully.
            Logger.log(AuditLog.Event.ERROR, "MANAGE_EXTERNAL_STORAGE not granted on TV. Grant via ADB: adb shell appops set ${packageName} MANAGE_EXTERNAL_STORAGE allow")
            if (!isMaintenanceStarted) {
                isMaintenanceStarted = true
                maintenance?.run()
            }
        } else {
            askForPermissions(missing)
        }
    }

    private fun isAndroidTV(): Boolean {
        return packageManager.hasSystemFeature("android.software.leanback")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onResume() {
        super.onResume()
        startMaintenanceIfPermitted()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        startMaintenanceIfPermitted()
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun askForPermissions(permissions: List<String>) {
        instance?.let {
            if (permissions.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
                    // Redirect to system settings for `MANAGE_EXTERNAL_STORAGE`
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts("package", it.packageName, null)
                        }
                        it.startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE)
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            it.startActivityForResult(fallbackIntent, MANAGE_STORAGE_REQUEST_CODE)
                        } catch (e2: Exception) {
                            // No Settings UI available (common on TV devices) — log and proceed
                            Logger.log(AuditLog.Event.ERROR, "No file access settings UI available. Grant via ADB: adb shell appops set ${it.packageName} MANAGE_EXTERNAL_STORAGE allow")
                            if (!isMaintenanceStarted) {
                                isMaintenanceStarted = true
                                maintenance?.run()
                            }
                        }
                    }
                } else {
                    // Request permissions normally
                    ActivityCompat.requestPermissions(it, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
                }
            }
        }
    }

    private fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): MANAGE_EXTERNAL_STORAGE is checked via Environment, NOT ContextCompat.checkSelfPermission
            if (!Environment.isExternalStorageManager()) {
                missing.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            }
        } else {
            // Android 10 and below (API < 30): Check runtime storage permissions
            instance?.let { ctx ->
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        return missing
    }

    /**
     * Handle the result of the permission requests.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val deniedPermissions = mutableListOf<String>()

            // Iterate through grantResults to check which permissions were denied
            grantResults.forEachIndexed { index, result ->
                if (result != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[index])
                }
            }

            if (deniedPermissions.isNotEmpty()) {
                // Optionally, show a dialog explaining why the permissions are needed
                // and then request them again or guide the user to settings

                // For simplicity, re-request the denied permissions
                askForPermissions(deniedPermissions)
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // moveTaskToBack(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Logger.log(AuditLog.Event.KEY_PRESS, "${KeyEvent.keyCodeToString(event.keyCode)}#${event.keyCode}")
        }
        return super.dispatchKeyEvent(event)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun triggerGraphics(nowPosition: Long) {
        val graphics = currentPlaylist?.graphics

        // Calculate new logo state
        val newLogoState = if (graphics != null && (graphics.displayLogo || graphics.displayLiveLogo)) {
            "${graphics.displayLogo}#${graphics.displayLiveLogo}#${graphics.logoPosition}"
        } else null

        // Only hide/reload logo if logo configuration has changed across playlists
        if (newLogoState != activeLogoState) {
            hideLogo()
            activeLogoState = newLogoState
            graphics?.let {
                if (it.displayLiveLogo) {
                    showLiveLogo(it.logoPosition)
                } else if (it.displayLogo) {
                    showLogo(it.logoPosition)
                }
            }
        }

        // Calculate new ticker state
        val news = graphics?.news
        val newTickerState = if (news != null && news.getMessagesArray().isNotEmpty()) {
            "${news.messages}#${news.showTime}"
        } else null

        // Only hide/reload ticker if ticker configuration has changed across playlists
        if (newTickerState != activeTickerState) {
            if (::tickerRecyclerView.isInitialized) {
                hideTicker()
            }
            activeTickerState = newTickerState
            news?.let { newsData ->
                val messages = newsData.getMessagesArray()
                if (messages.isNotEmpty()) {
                    initTickers(newsData)
                    val s = newsData.startMinute
                    val start = Math.round(s * 60 * 1000) // s is in minutes, send in ms
                    if (start <= nowPosition) {
                        // Start time has arrived or passed -> show ticker immediately!
                        showTicker(newsData)
                    } else {
                        // Future start time -> schedule delay
                        val delayMillis = start - nowPosition
                        lifecycleScope.launch {
                            delay(delayMillis)
                            showTicker(newsData)
                        }
                    }
                }
            }
        }

        hideWatermark()
        hideLowerThird()

        // handle repeat Watermark display
        if (graphics?.displayRepeatWatermark == true) {
            nowProgramItem?.let { it1 -> programItems[it1] }
                ?.let { it2 -> triggerRepeatWatermark(it2) }
        }

        // Handle lowerThird
        val lowerThirds = graphics?.lowerThirds
        lowerThirds?.forEach { ltd ->
            if (StringUtils.isNotBlank(ltd.starts) && ltd.file != null) {
                ltd.getStartsArray().forEach { s ->
                    val start = Math.round(s * 60 * 1000) // s is in minutes, send in ms
                    if (start >= nowPosition) {
                        val delayMillis = start - nowPosition
                        if (delayMillis > 0) {
                            lifecycleScope.launch {
                                delay(delayMillis)
                                showLowerThird(ltd)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hideLowerThird() {
        lowerThirdView?.let {
            if (it.visibility != View.GONE) {
                // TODO lowerThirdView.animate().translationX(lowerThirdView.width); etc should be in the clip
                it.visibility = View.GONE
                Logger.log(AuditLog.Event.LOWER_THIRD_OFF)
            }
        }
    }

    private fun hideTicker() {
        activeTickerState = null
        if (::tickerRecyclerView.isInitialized) {
            tickerRecyclerView.let {
                if (it.visibility != View.GONE) {
                    it.visibility = View.GONE
                    Logger.log(AuditLog.Event.DISPLAY_NEWS_OFF)
                    Logger.log(AuditLog.Event.DISPLAY_TIME_OFF)
                }
            }
        }
    }

    /**
     * Triggers display of the repeat watermark on the program itself, not on intros and outros.
     * @param mediaItem This helps retrieve the current on-change (on transition) program.
     */
    private fun triggerRepeatWatermark(mediaItem: MediaItem) {
        hideWatermark() // Hide watermark after program onChange
        val graphics = currentPlaylist?.graphics // Retrieve graphics

        if (!mediaItem.mediaId.contains("INTRO") && !mediaItem.mediaId.contains("OUTRO")) {
            // Handle repeat watermark display
            if (graphics != null) {
                if (graphics.displayRepeatWatermark) {
                    showRepeatProgramWatermark()
                }
            }
        }
    }

    private fun hideLogo() {
        activeLogoState = null
        val topLogo = findViewById<View>(R.id.topLogo)
        val bottomLogo = findViewById<View>(R.id.bottomLogo)

        if (topLogo.visibility != View.GONE || bottomLogo.visibility != View.GONE) {
            topLogo.visibility = View.GONE
            bottomLogo.visibility = View.GONE
            Logger.log(AuditLog.Event.DISPLAY_LOGO_OFF)
        }
    }

    private fun hideWatermark() {
        val watermark: View = findViewById(R.id.watermark)
        if (watermark.visibility != View.GONE) {
            watermark.visibility = View.GONE
            Logger.log(AuditLog.Event.DISPLAY_PROGRAM_WATERMARK_OFF)
        }
    }

    private fun showLowerThird(lowerThird: LowerThird) {
        val path = currentPlaylist?.let { getLowerThirdDirectory(it.isUsingExternalStorage) } + File.separator + lowerThird.file
        val lowerThirdClip = File(path)

        if (Utils.validPlayableItem(lowerThirdClip)) {
            Logger.log(AuditLog.Event.LOWER_THIRD_ON, path)
            lowerThirdView = findViewById(R.id.lowerThird) // initiate a video view
            lowerThirdView!!.setVideoURI(Uri.fromFile(lowerThirdClip))
            lowerThirdView!!.start()
            lowerThirdView!!.visibility = View.VISIBLE

            lowerThirdView!!.setOnCompletionListener {
                if (lowerThird.replays >= lowerThirdLoop) {
                    lowerThirdLoop++
                    lowerThirdView!!.start()
                } else {
                    hideLowerThird()
                    lowerThirdLoop = 1
                }
            }

            lowerThirdView!!.setOnErrorListener { _, _, _ ->
                Logger.log(AuditLog.Event.ERROR, "Failed to play ${lowerThird.file}")
                true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun initTickers(news: News) {
        // Initialize the RecyclerView
        tickerRecyclerView = findViewById(R.id.tickerRecyclerView)
        tickerRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val tickerItems = listOf(
            TickerItem(text = news.getMessagesArray().joinToString(" • "), time = news.showTime)
        )
        // Initialize the adapter with ticker items
        tickerAdapter = TickerAdapter(
            tickerItems
        )
        tickerRecyclerView.adapter = tickerAdapter
    }

    private fun showRepeatProgramWatermark() {
        val watermarkFolder = currentPlaylist?.let { getWatermarkDirectory(it.usingExternalStorage) }
        // Check for different possible file extensions
        val watermarkFile = possibleExtensions
            .map { File("$watermarkFolder${File.separator}repeat.$it") }
            .firstOrNull { it.exists() } // Get the first valid file

        if (watermarkFile?.exists() == true) {
            val watermarkView: ImageView = findViewById(R.id.watermark)

            // Clear previous image to prevent memory leaks
            Glide.with(watermarkView.context).clear(watermarkView)

            // Use Glide to load and display the image
            Glide.with(watermarkView.context)
                .asDrawable() // allows gifs and static images
                .load(watermarkFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache in memory & disk for faster loads
                .skipMemoryCache(true) // Avoid memory cache to reflect changes instantly
                .into(watermarkView)
            watermarkView.visibility = View.VISIBLE
            Logger.log(AuditLog.Event.DISPLAY_REPEAT_PROGRAM_WATERMARK_ON)
        }
    }

    private fun showLiveLogo(logoPosition: Graphics.LogoPosition?) {
        val watermarkFolder = currentPlaylist?.let { getWatermarkDirectory(it.usingExternalStorage) }
        // Check for different possible file extensions
        val logoFile = possibleExtensions
            .map { File("$watermarkFolder${File.separator}live.$it") }
            .firstOrNull { it.exists() } // Get the first valid file

        if (logoFile?.exists() == true && logoPosition != null) {
            val logoView: ImageView = if (Graphics.LogoPosition.TOP == logoPosition) {
                findViewById<ImageView>(R.id.topLogo).apply {
                    Logger.log(AuditLog.Event.DISPLAY_LIVE_LOGO_ON, Graphics.LogoPosition.TOP.name)
                }
            } else {
                findViewById<ImageView>(R.id.bottomLogo).apply {
                    Logger.log(AuditLog.Event.DISPLAY_LIVE_LOGO_ON, Graphics.LogoPosition.BOTTOM.name)
                }
            }

            // Clear previous image to prevent memory leaks
            Glide.with(logoView.context).clear(logoView)

            // Use Glide to load and display the image
            Glide.with(logoView.context)
                .asDrawable() // allows gifs and static images
                .load(logoFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache in memory & disk for faster loads
                .skipMemoryCache(true) // Avoid memory cache to reflect changes instantly
                .into(logoView)

            logoView.visibility = View.VISIBLE
        }
    }

     private fun showTicker(news: News) {
         news.messages?.let {
             Logger.log(AuditLog.Event.DISPLAY_NEWS_ON, it)
         }
         news.startMinute.let {
             Logger.log(AuditLog.Event.DISPLAY_TIME_ON, it.toString())
         }

         fadeInRecyclerView(tickerRecyclerView)
    }

    private fun fadeInRecyclerView(recyclerView: RecyclerView) {
        animationHandler.removeCallbacksAndMessages(null)
        animationHandler.post {
            recyclerView.visibility = View.VISIBLE
            recyclerView.animate().alpha(1f).setDuration(1000).start()
        }
    }

    private fun showLogo(logoPosition: Graphics.LogoPosition?) {
        val logoFolder = getProgramsFolderPath(false)
        val logoFile = possibleExtensions
            .map { File("${logoFolder}${File.separator}logo.$it") }
            .firstOrNull { it.exists() } // Get the first valid file
        if (logoFile?.exists() == true && logoPosition != null) {
            val logoView: ImageView = when (logoPosition) {
                Graphics.LogoPosition.TOP -> {
                    Logger.log(AuditLog.Event.DISPLAY_LOGO_ON, Graphics.LogoPosition.TOP.name)
                    findViewById(R.id.topLogo)
                }
                else -> {
                    Logger.log(AuditLog.Event.DISPLAY_LOGO_ON, Graphics.LogoPosition.BOTTOM.name)
                    findViewById(R.id.bottomLogo)
                }
            }

            // Clear previous image to prevent memory leaks
            Glide.with(logoView.context).clear(logoView)

            // Use Glide to load and display the image
            Glide.with(logoView.context)
                .asDrawable() // allows gifs and static images
                .load(logoFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache in memory & disk for faster loads
                .skipMemoryCache(true) // Avoid memory cache to reflect changes instantly
                .into(logoView)
            logoView.visibility = View.VISIBLE
        }
    }

    private fun regenerateConfiguration(resetSeekTo: Boolean): Config? {
        val config = configuration
        val playlists = config?.playlists
        if (playlists != null) {
            for (i in playlists.indices) {
                val playlist = playlists[i]
                if (playlist.isResuming()) {
                    playlist.seekTo = if (resetSeekTo) Seek(0, 0) else Seek(getSharedPlaylistMediaItem(i), getSharedPlaylistSeekTo(i))
                    playlists[i] = playlist
                }
            }
        }
        if (config != null) {
            config.playlists = playlists
        }
        return config
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun keepBroadcasting() {
        nowPlayingIndex?.let {
            scheduleKeepOnAir()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun scheduleKeepOnAir() {
        val delay = (configuration?.wait ?: 30) * 1000L // Default to 30 seconds if null
        // Use explicit component intent targeting the manifest-registered KeepOnAirReceiver
        val intent = Intent(this, org.avventomedia.app.telefyna.listen.KeepOnAirReceiver::class.java)
        intent.action = KEEP_ON_AIR_ACTION
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.cancel(pendingIntent)
        alarmManager?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delay,
            pendingIntent
        )
    }



    private fun rebootDevice() {
        try {
            Runtime.getRuntime().exec("su -c reboot")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun restartApp() {
        maintenance?.cancelPendingIntents()
        val intent = Intent(instance, instance!!::class.java)
        val mPendingIntent = PendingIntent.getActivity(
            instance, 700000001, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager?.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
        Logger.log(AuditLog.Event.RESTARTING)
        instance?.finish()
        exitProcess(2)
    }

    private fun backupConfig(resetSeekTo: Boolean) {
        if (resetSeekTo) {
            getBackupConfigResetFile().delete()
        } else {
            getBackupConfigFile().delete()
        }

        val config = regenerateConfiguration(resetSeekTo)
        try {
            Logger.log(AuditLog.Event.BACK_UP)
            FileWriter(getAuditConfigFile(), false).use { writer ->
                GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(config, writer)
            }
        } catch (e: IOException) {
            e.message?.let { Logger.log(AuditLog.Event.ERROR, it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun performRealtimeConfigReload() {
        if (getReInitializerFile().exists()) {
            getReInitializerFile().delete()
        }
        cacheNowPlaying(false)
        maintenance?.run()
    }

    /**
     * Public entry point for the standalone KeepOnAirReceiver.
     * Contains the keepOnAir logic that was previously in the inner class receiver.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun handleKeepOnAir() {
        when {
            getRebootFile().exists() -> {
                getRebootFile().delete()
                rebootDevice()
            }
            getRestartFile().exists() -> {
                getRestartFile().delete()
                restartApp()
            }
            getReInitializerFile().exists() -> {
                performRealtimeConfigReload()
                scheduleKeepOnAir()
            }
            else -> {
                if (getBackupConfigFile().exists()) backupConfig(false)
                if (getBackupConfigResetFile().exists()) backupConfig(true)

                if (nowPlayingIndex == getSecondDefaultIndex() &&
                    fillingForLackOfInternet &&
                    Utils.internetConnected() &&
                    failedBecauseOfInternetIndex != null) {
                    fillingForLackOfInternet = false
                    Logger.log(AuditLog.Event.INTERNET_RESTORED)
                    switchNow(failedBecauseOfInternetIndex!!, false, this)
                    failedBecauseOfInternetIndex = null
                } else {
                    offAir = player == null || !player!!.isPlaying
                    if (offAir) {
                        offAir = false
                        Logger.log(AuditLog.Event.STUCK, (configuration?.wait ?: 0).toLong())
                        switchNow(nowPlayingIndex!!, false, this)
                    }
                    scheduleKeepOnAir() // Schedule the next execution
                }
            }
        }
    }
}