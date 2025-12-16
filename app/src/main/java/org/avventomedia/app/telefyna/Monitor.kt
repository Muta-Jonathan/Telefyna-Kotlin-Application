package org.avventomedia.app.telefyna

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
    }

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
    private val keepOnAirReceiver = KeepOnAirReceiver()

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
        val now = dateFormat?.format(Calendar.getInstance().time)
        return try {
            val lastPlayed = Calendar.getInstance()
            val today = Calendar.getInstance()
            lastPlayed.time = ((sharedPreferences.getString(
                getPlaylistLastPlayed(
                    getPlaylistIndex(index)
                ), now) ?: now)?.let {
                dateFormat?.parse(it)
            } ?: now) as Date // Fallback to `now` if the parsing fails or result is null

            today.time = (now?.let { dateFormat?.parse(it) } ?: now) as Date
            isRepeatable(repeat, lastPlayed, today)
        } catch (e: ParseException) {
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

        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // allow network etc actions since telefyna depends on all of these
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())

        // Register receiver with RECEIVER_NOT_EXPORTED flag for Android 14+
        val filter = IntentFilter(KEEP_ON_AIR_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(keepOnAirReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keepOnAirReceiver, filter)
        }

        // Initialize permissions
        initialiseWithPermissions()
        maintenance!!.run()
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
        /*
        val delay = getConfiguration().wait * 1000
        val builder = DefaultLoadControl.Builder()
        builder.setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS + delay,
            (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS + (delay * 2)) * 2,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS + delay,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS + delay
        )
        val player = SimpleExoPlayer.Builder(instance).setLoadControl(builder.build()).build()
        */
        // Create a custom RenderersFactory if needed
        val renderersFactory = instance?.let { TelefynaRenderersFactory(it) }
        player = renderersFactory?.let { ExoPlayer.Builder(context, it).build() }

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
        val reInitializerFile = getReInitializerFile()
        if (reInitializerFile.exists()) {
            reInitializerFile.delete()
            maintenance?.run()
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
                } else {
                    if (programItems.isEmpty()) {
                        Logger.log(AuditLog.Event.PLAYLIST_EMPTY_PLAY, getPlayingAtIndexLabel(nowPlayingIndex))
                        switchNow(currentPlaylist!!.emptyReplacer ?: firstDefaultIndex, isCurrentSlot, context)
                        return
                    } else {
                        // Assign current player to previousPlayer before creating a new one
                        previousPlayer = player

                        // Immediately release previous player
                        if (previousPlayer != null) {
                            endPlayerSafely(previousPlayer)
                            previousPlayer = null
                        }
                        player = buildPlayer(context) // Create a new player

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
                                    nowProgramItem = if (previousProgram == -1 || previousProgram == (programItems.size).minus(1)) {
                                        0
                                    } else if (currentPlaylist!!.repeat?.let { canResume(nowPlayingIndex!!, it) } == true) {
                                        previousProgram.plus(1)
                                    } else {
                                        previousProgram
                                    }
                                    previousSeekTo = 0
                                } else if (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_SAME) {
                                    nowProgramItem = previousProgram
                                    previousSeekTo = 0
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
                                } else if (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING) {
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
                                programItems.addAll(specialBumpersOutro)
                                programItems.addAll(playListOutroBumpers)
                                programItems.addAll(generalBumpersOutro)
                            }

                            if (isCurrentSlot && nowPlayingIndex != secondDefaultIndex) { // Not fillers
                                val seek = seekImmediateNonCompletedSlot(currentPlaylist!!, programItems)
                                if (seek != null) {
                                    nowProgramItem = if (seek.program == (programItems.size).minus(1)) seek.program else nowProgramItem?.plus(seek.program)
                                    nowPosition = if (seek.program == (programItems.size).minus(1)) seek.position else nowProgramItem?.plus(seek.position)!!
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
                                addUpdateListener { player!!.volume = it.animatedValue as Float }
                            }
                            fadeInAnimator.start()
                            Logger.log(AuditLog.Event.FADE_PLAYED, "fade in transition played")
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
                }
            }
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

    // Retrieve video duration in milliseconds
    private fun getDuration(path: String): Long {
        var mediaPlayer: MediaPlayer? = null
        var duration: Long = 0
        try {
            mediaPlayer = MediaPlayer().apply {
                instance?.let { setDataSource(it, Uri.parse(path)) }
                prepare()
            }
            duration = mediaPlayer.duration.toLong()
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, e.message ?: "Unknown error")
        } finally {
            mediaPlayer?.release()
        }
        return duration
    }

    private fun seekImmediateNonCompletedSlot(playlist: Playlist, mediaItems: List<MediaItem>): Seek? {
        val start = playlist.getStartTime()
        if (start != null) {
            val startTime = start.timeInMillis
            val now = Calendar.getInstance().timeInMillis
            mediaItems.forEachIndexed { i, mediaItem ->
                val duration = getDuration(mediaItem.mediaId)
                if ((duration + startTime) > now) {
                    return Seek(i, now - startTime)
                }
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
                            playlist.type == Playlist.Type.LOCAL_RANDOMIZED
                    val isAtLastItem = player?.currentMediaItemIndex == (programItems.size - 1)

                    // Only switch to fillers when a truly finite playlist finishes
                    if (isFiniteType && isAtLastItem) {
                        Logger.log(AuditLog.Event.PLAYLIST_COMPLETED, "Playlist fully finished, switching to filler")
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
                    currentPlaylist?.type == Playlist.Type.LOCAL_RANDOMIZED

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
        if (!filePath.isNullOrEmpty() && !File(filePath).exists()) {
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
                // this will wait for set time on config before reloading
            }
            is UnrecognizedInputFormatException -> {
                if (player?.isCurrentWindowSeekable == true) {
                    nowProgramItem?.plus(1)?.let { player!!.seekTo(it, 0) }
                }
            }
            is MediaCodecRenderer.DecoderInitializationException -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nowPlayingIndex?.let { switchNow(it, false, this) }  // Added context parameter
                }
            }
            else -> {
                if (!player?.isPlaying!!) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        nowPlayingIndex?.let { switchNow(it, false, this) }  // Added context parameter
                    }
                }
            }
        }
    }


    private fun getPlayingAtIndexLabel(index: Int?): String {
        val playlistName = playlistByIndex[index?.let { getPlaylistIndex(it) }!!].name
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

    fun endPlayerSafely(player: Player?) {
        if (player == null) return  // Avoid unnecessary execution
        try {
            player.stop()  // Stop playback
            player.clearMediaItems()  // Remove media items
            GlobalScope.launch(Dispatchers.Main) {
                delay(300)
                player.release()  // Fully release ExoPlayer
            }
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
        // Unregister receiver and cancel alarm
        unregisterReceiver(keepOnAirReceiver)
        val intent = Intent(KEEP_ON_AIR_ACTION)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.cancel(pendingIntent)

        Glide.get(this).clearMemory()
        Glide.get(this).trimMemory(TRIM_MEMORY_COMPLETE)

    }

    override fun onStop() {
        super.onStop()
        player?.pause()
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
    private fun initialiseWithPermissions() {
        val permissionsToRequest = missingPermissions()
        if (permissionsToRequest.isNotEmpty()) {
            askForPermissions(permissionsToRequest)
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun askForPermissions(permissions: List<String>) {
        instance?.let {
            if (permissions.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
                    // Redirect to system settings for `MANAGE_EXTERNAL_STORAGE`
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.fromParts("package", it.packageName, null)
                    if (intent.resolveActivity(it.packageManager) != null) {
                        it.startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE)
                    } else {
                        //TODO: Incase no permissions show a screen like no permission (this prevents the restart due to permission failing)
                        Toast.makeText(it, "Unable to open settings for file access permission", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Request permissions normally
                    ActivityCompat.requestPermissions(it, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
                }
            }
        }
    }

    private fun missingPermissions(): List<String> {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // For Android 10 and below (API 30 and lower)
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // For Android 11 (API 30) and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ (API 31+)
                if (!Environment.isExternalStorageManager()) {
                    requiredPermissions.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
                }
            }
        }

        requiredPermissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        requiredPermissions.add(Manifest.permission.INTERNET)
        requiredPermissions.add(Manifest.permission.ACCESS_NETWORK_STATE)

        // Filter missing permissions
        return requiredPermissions.filter {
            instance?.let { ctx ->
                ContextCompat.checkSelfPermission(ctx, it)
            } != PackageManager.PERMISSION_GRANTED
        }
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
        hideLogo()
        // Always check initialization before hiding
        if (::tickerRecyclerView.isInitialized) {
            hideTicker()
        }
        hideWatermark(); //hide watermark after program completed
        hideLowerThird()
        val graphics = currentPlaylist?.graphics
        graphics?.let {
            // handle live logo display
            if(it.displayLiveLogo) {
                showLiveLogo(graphics.logoPosition);
            }
            // handle repeat Watermark display
            if(it.displayRepeatWatermark) {
                nowProgramItem?.let { it1 -> programItems[it1] }
                    ?.let { it2 -> triggerRepeatWatermark(it2) };
            }

            // Handle logo
            if (it.displayLogo) {
                showLogo(it.logoPosition)
            }

            // Handle lowerThird
            val lowerThirds = it.lowerThirds
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

            // Handle ticker
            val news = it.news
            news?.let { newsData ->
                val messages = newsData.getMessagesArray()
                if (messages.isNotEmpty()) {
                    initTickers(newsData)
                    newsData.getStartsArray().forEach { s ->
                        val start = Math.round(s * 60 * 1000) // s is in minutes, send in ms
                        if (start >= nowPosition) {
                            val delayMillis = start - nowPosition
                            if (delayMillis > 0) {
                                lifecycleScope.launch {
                                    delay(delayMillis)
                                    showTicker(newsData)
                                }
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
        tickerRecyclerView.let {
            if (it.visibility != View.GONE) {
                it.visibility = View.GONE
                Logger.log(AuditLog.Event.DISPLAY_NEWS_OFF)
                Logger.log(AuditLog.Event.DISPLAY_TIME_OFF)
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
            TickerItem(text = news.messages, time = news.showTime)
        )
        // Initialize the adapter with ticker items
        tickerAdapter = TickerAdapter(
            tickerItems,
            displacement = news.speed.getDisplacement(),
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
         news.starts.let {
             Logger.log(AuditLog.Event.DISPLAY_TIME_ON, it)
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
    private fun scheduleKeepOnAir() {
        val delay = (configuration?.wait ?: 30) * 1000L // Default to 30 seconds if null
        val intent = Intent("org.avventomedia.app.telefyna.KEEP_ON_AIR")
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delay,
            pendingIntent
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun keepOnAir() {
        val delay = (configuration?.wait)?.times(1000L)
        when {
            getRebootFile().exists() -> {
                getRebootFile().delete()
                rebootDevice()
            }
            getRestartFile().exists() -> {
                getRestartFile().delete()
                restartApp()
            }
            else -> {
                if (getBackupConfigFile().exists()) backupConfig(false)
                if (getBackupConfigResetFile().exists()) backupConfig(true)

                if (nowPlayingIndex == getSecondDefaultIndex() && fillingForLackOfInternet && Utils.internetConnected() && failedBecauseOfInternetIndex != null) {
                    fillingForLackOfInternet = false
                    Logger.log(AuditLog.Event.INTERNET_RESTORED)
                    switchNow(failedBecauseOfInternetIndex!!, false, this)
                    failedBecauseOfInternetIndex = null
                } else {
                    if (delay != null) {
                        scheduleKeepAlive()
                    }
                    if (offAir) {
                        offAir = false
                        if (delay != null) {
                            Logger.log(AuditLog.Event.STUCK, delay / 1000)
                        }
                        switchNow(nowPlayingIndex!!, false, this)
                    } else {
                        offAir = player == null || !player!!.isPlaying
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun scheduleKeepAlive(delayMillis: Long = (configuration?.wait ?: 30) * 1000L) {
        lifecycleScope.launch {
            delay(delayMillis)
            keepOnAir()
        }
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

    inner class KeepOnAirReceiver : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            when {
                getRebootFile().exists() -> {
                    getRebootFile().delete()
                    rebootDevice()
                }
                getRestartFile().exists() -> {
                    getRestartFile().delete()
                    restartApp()
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
                        switchNow(failedBecauseOfInternetIndex!!, false, this@Monitor)
                        failedBecauseOfInternetIndex = null
                    } else {
                        offAir = player == null || !player!!.isPlaying
                        if (offAir) {
                            offAir = false
                            Logger.log(AuditLog.Event.STUCK, (configuration?.wait ?: 0).toLong())
                            switchNow(nowPlayingIndex!!, false, this@Monitor)
                        }
                        scheduleKeepOnAir() // Schedule the next execution
                    }
                }
            }
        }
    }
}