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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.StringUtils
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.audit.ReportBranding
import org.avventomedia.app.telefyna.audit.ReportGenerator
import org.avventomedia.app.telefyna.audit.ReportPeriod
import org.avventomedia.app.telefyna.listen.Maintenance
import org.avventomedia.app.telefyna.listen.TelefynaUnCaughtExceptionHandler
import org.avventomedia.app.telefyna.modal.Config
import org.avventomedia.app.telefyna.modal.ConfigValidator
import org.avventomedia.app.telefyna.modal.Graphics
import org.avventomedia.app.telefyna.modal.LowerThird
import org.avventomedia.app.telefyna.modal.News
import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.modal.Seek
import org.avventomedia.app.telefyna.player.LoudnessLevelingAudioProcessor
import org.avventomedia.app.telefyna.player.SchemeRoutingDataSource
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
        private const val PLAYLIST_MEDIA_ID = "PLAYLIST_MEDIA_ID"
        private const val PLAYLIST_PLAY_FORMAT = "%s-%d"
        private const val REQUEST_CODE_PERMISSIONS = 123
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MANAGE_STORAGE_REQUEST_CODE = 101
        private const val OVERLAY_PERMISSION_ASKED = "OVERLAY_PERMISSION_ASKED"
        var instance: Monitor? = null // for player am using media3
        private const val KEEP_ON_AIR_ACTION = "org.avventomedia.app.telefyna.KEEP_ON_AIR"
        private const val CROSS_FADE_DURATION = 1000L // Reduce fade duration for faster switching to 2seconds
        private val animationHandler = Handler(Looper.getMainLooper())
        // Define a reusable Gson instance outside the function to avoid repeated creation
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val possibleExtensions = listOf("webp", "png", "jpg", "gif")
        private const val RESUME_SAVE_INTERVAL = 15_000L
        private const val RETRY_PREPARE_DELAY = 5000L
        // PlaybackException.ERROR_CODE_IO_* (2000-2999)
        private val IO_ERROR_CODES = 2000..2999

        // Crash-loop safe mode
        private const val CRASH_TIMES = "CRASH_TIMES"
        private const val CRASH_WINDOW_MINUTES = 10
        private const val FILLERS_ONLY_CRASHES = 3
        private const val SLATE_ONLY_CRASHES = 5
        private const val SAFE_MODE_MINUTES = 15L

        /** Records a crash time synchronously (the process is about to die), keeping only the crash window. */
        fun recordCrash(context: Context) {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val times = recentCrashes(prefs) + System.currentTimeMillis()
            prefs.edit().putString(CRASH_TIMES, times.joinToString(",")).commit()
        }

        private fun recentCrashes(prefs: SharedPreferences): List<Long> {
            val since = System.currentTimeMillis() - CRASH_WINDOW_MINUTES * 60_000L
            return prefs.getString(CRASH_TIMES, "").orEmpty().split(",")
                .mapNotNull { it.toLongOrNull() }.filter { it >= since }
        }
    }

    enum class SafeMode { OFF, FILLERS_ONLY, SLATE_ONLY }

    /** Set at startup when Telefyna keeps crashing: airs only fillers (or only the slate) without schedules or graphics. */
    var safeMode = SafeMode.OFF
        private set
    private var slateShown = false
    /** This screen joined a playout that kept running while no screen existed (until its first schedule). */
    private var reattachedToPlayout = false
    // Guards emptyReplacer chains (A -> B -> A) from recursing forever
    private var emptyHops = 0

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

    // On-air state lives in Playout so it survives the Activity being destroyed and re-created
    private var nowPlayingIndex: Int?
        get() = Playout.nowPlayingIndex
        set(value) { Playout.nowPlayingIndex = value }
    private var failedBecauseOfInternetIndex: Int?
        get() = Playout.failedBecauseOfInternetIndex
        set(value) { Playout.failedBecauseOfInternetIndex = value }

    private var player: ExoPlayer?
        get() = Playout.player
        set(value) { Playout.player = value }
    // Always in the audio chain; enabled/disabled from config on every (re)load without rebuilding the player
    private val loudnessLeveling get() = Playout.loudnessLeveling
    private var previousPlayer: ExoPlayer? = null
    private var currentPlaylist: Playlist?
        get() = Playout.currentPlaylist
        set(value) { Playout.currentPlaylist = value }
    private var playlistByIndex: MutableList<Playlist>
        get() = Playout.playlistByIndex
        set(value) { Playout.playlistByIndex = value }
    private var programItems: MutableList<MediaItem>
        get() = Playout.programItems
        set(value) { Playout.programItems = value }
    // Next playlist queued behind the last item for a gapless handover
    private var upcomingProgramItems: MutableList<MediaItem>?
        get() = Playout.upcomingProgramItems
        set(value) { Playout.upcomingProgramItems = value }
    // When the current switch started loading, to log how long the new picture takes (0 = nothing pending)
    private var switchStartedAt = 0L
    private lateinit var tickerRecyclerView: RecyclerView
    private var graphicsScale = 0f
    private lateinit var tickerAdapter: TickerAdapter
    private var lowerThirdView: VideoView? = null
    // Pending delayed graphics; cancelled on switch so the previous playlist's graphics never air over the next one
    private var tickerJob: Job? = null
    private val lowerThirdJobs = mutableListOf<Job>()

    private var lowerThirdLoop = 1
    private var fillingForLackOfInternet: Boolean
        get() = Playout.fillingForLackOfInternet
        set(value) { Playout.fillingForLackOfInternet = value }
    private var nowProgramItem: Int?
        get() = Playout.nowProgramItem
        set(value) { Playout.nowProgramItem = value }
    private var startOnePlayProgramItem: Int?
        get() = Playout.startOnePlayProgramItem
        set(value) { Playout.startOnePlayProgramItem = value }


    var dateFormat: SimpleDateFormat? = null
        private set

    // Swap the whole table at once so player callbacks never see a half-built (or empty) schedule
    fun replacePlaylists(playlists: List<Playlist>) {
        playlistByIndex = playlists.toMutableList()
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

    private fun trackingNowPlaying(index: Int, seekTo: Long, noProgramTransition: Boolean, log: Boolean = true) {
        if (playlistByIndex[index].isResuming()) {
            cachePlayingAt(index, seekTo, noProgramTransition, log)
        }
    }

    private fun cachePlayingAt(index: Int, seekTo: Long, noProgramTransition: Boolean, log: Boolean = true) {
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
            // Remember which file that position is, so resuming survives files being added or removed
            val resumeFile = if (Playlist.Type.LOCAL_RESUMING_ONE == currentPlaylist?.type && startOnePlayProgramItem != null) {
                programItems.getOrNull(at)?.mediaId
            } else {
                programItems.getOrNull(atValue)?.mediaId
            }
            if (resumeFile != null) {
                editor.putString(getPlaylistMediaIdKey(index), resumeFile)
            } else {
                editor.remove(getPlaylistMediaIdKey(index))
            }
            editor.putLong(getPlaylistSeekTo(index), seekTo)
            editor.putLong(getPlaylistLastModified(index), getLastModifiedFor(index))
            editor.putString(getPlaylistLastPlayed(index),
                dateFormat?.format(Calendar.getInstance().time)
            )
            editor.apply() // Use apply instead of commit for asynchronous saving
            if (log) {
                Logger.log(AuditLog.Event.CACHE_NOW_PLAYING_RESUME, getPlayingAtIndexLabel(index), programName, "$at-$seekTo")
            }
        }
    }

    private fun getSharedPlaylistMediaId(index: Int): String? {
        return sharedPreferences.getString(getPlaylistMediaIdKey(index), null)
    }

    @SuppressLint("DefaultLocale")
    private fun getPlaylistMediaIdKey(index: Int): String {
        return String.format(PLAYLIST_PLAY_FORMAT, PLAYLIST_MEDIA_ID, index)
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
        // Size the on-air graphics as a share of the picture, whatever the screen's density
        findViewById<View>(R.id.player).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val scale = GraphicsScale.of(v.width, v.height)
            if (scale != graphicsScale) {
                graphicsScale = scale
                applyGraphicsScale(scale)
            }
        }

        // HACK: Disable back press (Issue arises due to remote control by RustDesk)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing (Prevents back navigation)
            }
        })

        // handle any uncaught exception
        // Install once per process: on Activity recreation the current default is already ours, and wrapping it again
        // would chain our handler to itself
        if (Thread.getDefaultUncaughtExceptionHandler() !is TelefynaUnCaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(TelefynaUnCaughtExceptionHandler())
        }
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
        evaluateSafeMode()
        handler?.postDelayed(resumePositionSaver, RESUME_SAVE_INTERVAL)

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start the silent foreground service for 24/7 priority
        val serviceIntent = Intent(this, org.avventomedia.app.telefyna.listen.TelefynaForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        // allow network etc actions since telefyna depends on all of these
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())

        // KeepOnAirReceiver is now manifest-registered (survives process death)

        ensureBackgroundRelaunchAllowed()

        // A playout that survived a previous screen: show it and keep receiving its events before maintenance runs
        if (Playout.isOnAir) {
            reattachedToPlayout = true
            Playout.attach(this)
            getPlayerView(true).player = player
            Logger.log(AuditLog.Event.PLAYOUT_REATTACHED, getNowPlayingPlaylistLabel())
        }

        // Initialize permissions and start maintenance only when permitted
        startMaintenanceIfPermitted()
    }

    /**
     * From Android 10, the watchdog receivers can't start this Activity once the app is in the background or dead,
     * unless the app may draw overlays (or is the HOME app). Without this, "App Relaunch" is logged but nothing appears.
     */
    private fun ensureBackgroundRelaunchAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) return
        Logger.log(AuditLog.Event.ERROR, "Watchdog relaunch is blocked until 'Display over other apps' is allowed. Grant via ADB: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        if (isAndroidTV() || sharedPreferences.getBoolean(OVERLAY_PERMISSION_ASKED, false)) return
        sharedPreferences.edit().putBoolean(OVERLAY_PERMISSION_ASKED, true).apply()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "No overlay permission settings UI: ${e.message}")
        }
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

    /**
     * Operator control flags (init.txt, restart.txt, ...) live in the telefynaAudit folder next to the logs.
     * Flags dropped in the old app-private location (Android/data/.../telefynaAudit) are still honoured.
     */
    private fun getControlFile(name: String): File {
        val primary = File(Logger.auditDirectory(this), name)
        val legacy = File(Logger.legacyAuditDirectory(this), name)
        return if (!primary.exists() && legacy.exists()) legacy else primary
    }

    private fun getRestartFile(): File {
        return getControlFile("restart.txt")
    }

    private fun getRebootFile(): File {
        return getControlFile("reboot.txt")
    }

    private fun getAuditConfigFile(): File {
        return File(getAuditFilePath(this,"config.json"))
    }

    private fun getBackupConfigFile(): File {
        return getControlFile("backupConfig.txt")
    }

    private fun getBackupConfigResetFile(): File {
        return getControlFile("backupConfigReset.txt")
    }

    private fun getReInitializerFile(): File {
        return getControlFile("init.txt")
    }

    private fun getReportRequestFile(): File {
        return getControlFile("report.txt")
    }

    /**
     * Writes broadcast reports (PDF) for [periods] into telefynaAudit/reports/ and announces each one, so subscribers
     * with attachReports get it by email. Call off the main thread: a year of summaries/logs is read.
     */
    fun generateReports(periods: List<ReportPeriod>, toDate: Boolean) {
        try {
            val auditDir = File(getAuditFilePath(this, ""))
            Logger.flush() // today's lines must be on disk before they are read back
            val station = configuration?.name ?: getString(R.string.app_name)
            val logo = possibleExtensions.map { File("${getProgramsFolderPath(false)}${File.separator}logo.$it") }.firstOrNull { it.exists() }
            val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            val branding = ReportBranding(station, logo, appVersion, configuration?.version?.takeIf { it.isNotBlank() })
            ReportGenerator.generate(auditDir, station, periods, toDate, branding = branding).forEach { file ->
                Logger.log(AuditLog.Event.REPORT_GENERATED, file.nameWithoutExtension, file.absolutePath)
            }
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "Report generation failed: ${e.message}")
        }
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
        return "${Logger.auditDirectory(context).absolutePath}/$name"
    }

    fun getAuditLogsFilePath(name: String): String {
        return getAuditFilePath(this,"${name}${AuditLog.ENDPOINT}")
    }

    // Copy of the last config that loaded and validated, kept in app-private storage
    private fun getLastGoodConfigFile(): File = File(filesDir, "last-good-config.json")

    fun initialise() {
        installDefaultConfigIfMissing()
        val config = readConfig(File(getConfigFile()))
        if (config != null) {
            configuration = config
            try {
                File(getConfigFile()).copyTo(getLastGoodConfigFile(), overwrite = true)
            } catch (e: IOException) {
                Logger.log(AuditLog.Event.ERROR, "Could not back up config: ${e.message}")
            }
            Logger.log(AuditLog.Event.CONFIGURATION)
        } else {
            // Never crash-loop on a bad upload: keep airing the last config that worked
            val lastGood = readConfig(getLastGoodConfigFile())
            if (lastGood != null) {
                configuration = lastGood
                Logger.log(AuditLog.Event.ERROR, "Using last known good configuration")
            }
        }
        // Applies to a freshly loaded and a last-known-good config alike; no player rebuild needed
        configuration?.let {
            loudnessLeveling.enabled = it.loudnessLeveling
            loudnessLeveling.targetDbfs = it.loudnessTargetDbfs
            Playout.applyAudioStream(this, it.audioStream?.let { s -> if (s.name.isNullOrBlank()) s.copy(name = it.name) else s })
        }
    }

    /**
     * First run only: install the bundled default config and folder layout when there is no config.json.
     * Skipped once any config has ever loaded (a last-good copy exists), so a missing operator config keeps
     * airing its last good version instead of being replaced by the blank default.
     */
    private fun installDefaultConfigIfMissing() {
        if (getLastGoodConfigFile().exists()) return
        try {
            val root = File(getProgramsFolderPath(false))
            if (DefaultConfigInstaller.installIfMissing(root) { assets.open(DefaultConfigInstaller.ASSET) }) {
                Logger.log(AuditLog.Event.CONFIG_INSTALLED, root.absolutePath)
            }
        } catch (e: IOException) {
            Logger.log(AuditLog.Event.ERROR, "Could not install the default configuration: ${e.message}")
        }
    }

    private fun readConfig(file: File): Config? {
        if (!file.exists()) {
            Logger.log(AuditLog.Event.ERROR, "Configuration not found: ${file.absolutePath}")
            return null
        }
        return try {
            val config = FileReader(file).use { reader -> Gson().fromJson(BufferedReader(reader), Config::class.java) }
            val problem = validateConfig(config)
            if (problem != null) {
                Logger.log(AuditLog.Event.ERROR, "Invalid configuration ${file.name}: $problem")
                null
            } else {
                config
            }
        } catch (e: Exception) { // IOException, JsonParseException, etc.
            Logger.log(AuditLog.Event.ERROR, "Unreadable configuration ${file.name}: ${e.message}")
            null
        }
    }

    private fun validateConfig(config: Config?): String? = ConfigValidator.problem(config)

    private fun cacheNowPlaying(noProgramTransition: Boolean, log: Boolean = true) {
        val now = nowPlayingIndex?.let { getPlaylistIndex(it) }
        if (now != null && player != null) {
            trackingNowPlaying(now, player!!.currentPosition, noProgramTransition, log)
        }
    }

    // Saves the resume point of resuming playlists while they play, so a power cut or crash resumes close to where
    // it stopped instead of at the start of the program (positions were only saved on item transitions)
    private val resumePositionSaver = object : Runnable {
        override fun run() {
            try {
                if (player?.isPlaying == true && currentPlaylist?.isResuming() == true) {
                    cacheNowPlaying(false, log = false)
                }
            } catch (e: Exception) {
                Logger.log(AuditLog.Event.ERROR, "Saving resume position failed: ${e.message}")
            }
            handler?.postDelayed(this, RESUME_SAVE_INTERVAL)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(activityContext: Context): ExoPlayer {
        val context = activityContext.applicationContext // the player outlives this Activity
        val renderersFactory = instance?.let { 
            TelefynaRenderersFactory(it.applicationContext, loudnessLeveling, Playout.audioTap).apply {
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
                .setMediaSourceFactory(DefaultMediaSourceFactory(SchemeRoutingDataSource.Factory(context)))
                // Broadcast appliance: never yield to other apps' audio focus or pause on output route changes,
                // and hold wake + wifi locks so the stream isn't dropped by power saving
                .setAudioAttributes(audioAttributes, false)
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .build()
        } else {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(SchemeRoutingDataSource.Factory(context)))
                // Broadcast appliance: never yield to other apps' audio focus or pause on output route changes,
                // and hold wake + wifi locks so the stream isn't dropped by power saving
                .setAudioAttributes(audioAttributes, false)
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .build()
        }

        return player as ExoPlayer
    }

    private fun addBumpers(bumpers: MutableList<MediaItem>, bumperFolder: File, addedFirstItem: Boolean, playlist: Playlist) {
        if (bumperFolder.exists() && bumperFolder.listFiles()?.isNotEmpty() == true) {
            Utils.setupLocalPrograms(bumpers, bumperFolder, addedFirstItem, playlist)
            bumpers.reverse()
        }
    }

    /**
     * Intro and outro bumpers of a non-resuming local playlist: intros general, playlist, special;
     * outros special, playlist, general. Used for a normal switch and for a playlist queued gaplessly.
     */
    private fun bumpersFor(playlist: Playlist): Pair<List<MediaItem>, List<MediaItem>> {
        val bumperFolder = getBumperDirectory(playlist.isUsingExternalStorage)
        val generalBumpersIntro = mutableListOf<MediaItem>()
        val generalBumpersOutro = mutableListOf<MediaItem>()
        val specialBumpersIntro = mutableListOf<MediaItem>()
        val specialBumpersOutro = mutableListOf<MediaItem>()
        val playListIntroBumpers = mutableListOf<MediaItem>()
        val playListOutroBumpers = mutableListOf<MediaItem>()
        if (playlist.isPlayingGeneralBumpers) {
            addBumpers(generalBumpersIntro, File("$bumperFolder${File.separator}General-INTRO"), false, playlist)
            addBumpers(generalBumpersOutro, File("$bumperFolder${File.separator}General-OUTRO"), false, playlist)
        }
        val specialBumperFolder = playlist.specialBumperFolder
        if (!specialBumperFolder.isNullOrBlank()) {
            addBumpers(specialBumpersIntro, File("$bumperFolder${File.separator}$specialBumperFolder-INTRO"), false, playlist)
            addBumpers(specialBumpersOutro, File("$bumperFolder${File.separator}$specialBumperFolder-OUTRO"), false, playlist)
        }
        val playlistFolder = playlist.urlOrFolder?.split(Utils.COMMA_SPLITTER)?.get(0)
        addBumpers(playListIntroBumpers, File("$bumperFolder${File.separator}$playlistFolder-INTRO"), false, playlist)
        addBumpers(playListOutroBumpers, File("$bumperFolder${File.separator}$playlistFolder-OUTRO"), false, playlist)
        return (generalBumpersIntro + playListIntroBumpers + specialBumpersIntro) to
                (specialBumpersOutro + playListOutroBumpers + generalBumpersOutro)
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
        // Also when no player was ever built (e.g. the slate is up because the playlist was empty): retry it
        return player?.isPlaying != true && samePlaylistPlaying(index)
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
                    val replacer = currentPlaylist!!.emptyReplacer ?: firstDefaultIndex
                    if (replacer == index || emptyHops >= playlistByIndex.size) {
                        // Nothing anywhere to fall back to: put up the slate instead of a black screen.
                        // KeepOnAir keeps retrying this playlist, so playout resumes once media appears.
                        emptyHops = 0
                        showSlate("Nothing to play in ${getPlayingAtIndexLabel(index)} or its fallbacks")
                        return
                    }
                    emptyHops++
                    switchNow(replacer, isCurrentSlot, context)
                    return
                }
                emptyHops = 0

                // No stop()/clearMediaItems() on a switch: stopping releases the video decoder, and re-creating it on a
                // TV box froze the last frame for seconds. The old programme keeps playing until the new items replace it.
                if (player == null) {
                    player = buildPlayer(context)
                }

                        // Reset tracking now playing if the playlist programs were modified
                        // (only for resume points saved before file identities were stored; those are found by file now)
                        val modifiedOffset = playlistModified(nowPlayingIndex!!)
                        if (modifiedOffset > 0 && getSharedPlaylistMediaId(getPlaylistIndex(nowPlayingIndex!!)) == null) {
                            Logger.log(AuditLog.Event.PLAYLIST_MODIFIED, getPlayingAtIndexLabel(nowPlayingIndex), modifiedOffset / 1000)
                            resetTrackingNowPlaying(nowPlayingIndex!!)
                        }

                        nowProgramItem = currentPlaylist!!.seekTo.program
                        startOnePlayProgramItem = null
                        var nowPosition = currentPlaylist!!.seekTo.position

                        if (currentPlaylist!!.type != Playlist.Type.ONLINE) {
                            // Resume local resumable programs
                            if (currentPlaylist!!.isResuming()) {
                                val resumeIndex = getPlaylistIndex(nowPlayingIndex!!)
                                val storedProgram = getSharedPlaylistMediaItem(resumeIndex)
                                val willAdvance = nowProgramItem == 0 &&
                                        (currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_NEXT || currentPlaylist!!.type == Playlist.Type.LOCAL_RESUMING_ONE) &&
                                        currentPlaylist!!.repeat?.let { canResume(nowPlayingIndex!!, it) } == true
                                val (previousProgram, sameFile) = ResumeResolver.resolve(
                                    storedProgram, getSharedPlaylistMediaId(resumeIndex), programItems.map { it.mediaId }, willAdvance
                                )
                                if (previousProgram != storedProgram || !sameFile) {
                                    Logger.log(AuditLog.Event.RESUME_REMAPPED, getPlayingAtIndexLabel(nowPlayingIndex), storedProgram, previousProgram,
                                        if (sameFile) "same file" else "file removed, continuing with the next one")
                                }
                                var previousSeekTo = if (sameFile) getSharedPlaylistSeekTo(resumeIndex) else 0L
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
                                val (introBumpers, allOutroBumpers) = bumpersFor(currentPlaylist!!)
                                programItems.addAll(0, introBumpers)

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
                                                // Right after this playlist's own items: the next playlist may already be
                                                // queued behind them, and bumpers must stay part of (and tagged as) this playlist
                                                val bumpers = fittingOutroBumpers.map { b -> b.buildUpon().setTag(index).build() }
                                                val insertAt = programItems.size
                                                programItems.addAll(bumpers)
                                                player!!.addMediaItems(insertAt, bumpers)
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
                        // Tag every item with its playlist index so a gapless handover can be recognised
                        programItems = programItems.map { it.buildUpon().setTag(index).build() }.toMutableList()
                        upcomingProgramItems = null
                        // Load the new media items
                        switchStartedAt = android.os.SystemClock.elapsedRealtime()
                        player!!.setMediaItems(programItems, nowProgramItem ?: 0, nowPosition)
                        if (player!!.playbackState == Player.STATE_IDLE) {
                            player!!.prepare() // first start, or after an error; otherwise the player moves on by itself
                        }
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
                        Playout.attach(this) // this screen receives the player's events (once)
                        player!!.playWhenReady = true
                        playerView.player = player // Explicitly attach new player to PlayerView
                        nowProgramItem?.let { programItems[it] }?.let { getMediaItemName(it) }?.let {
                            Logger.log(
                                if (isCurrentSlot) AuditLog.Event.PLAYLIST_PLAY else AuditLog.Event.PLAYLIST_SWITCH,
                                getNowPlayingPlaylistLabel(),
                                Utils.formatDuration(nowPosition),
                                it
                            )
                        }
                        nowProgramItem?.let { programItems.getOrNull(it) }?.let { announceOnStream(it) }
                        // Log now playing
                        cacheNowPlaying(false)
                        triggerGraphics(nowPosition)
                        // The listener is attached after setMediaItems, so the first transition isn't seen: queue the
                        // next playlist now when starting on the last item (e.g. single-item playlists)
                        if (currentPlaylist!!.isFiniteLocal() && nowProgramItem == programItems.size - 1) {
                            queueNextPlaylist()
                        }
            }
        } else {
            // Already playing this playlist. Just update reference to apply any graphics/config changes
            nowPlayingIndex = index
            currentPlaylist = playlist
            triggerGraphics(player?.currentPosition ?: 0L)
        }
    }

    private val slotCommitToken = Any()

    /**
     * Pre-roll for a timed slot: warm it up now (measure its media in the background) and switch exactly at
     * [startAt]. An alarm that arrives at or after the start time switches immediately.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun prepareSlot(index: Int, startAt: Long) {
        val playlist = playlistByIndex.getOrNull(index) ?: return
        val delay = startAt - System.currentTimeMillis()
        if (delay <= 0) {
            switchNow(index, false, this)
            return
        }
        Logger.log(AuditLog.Event.SLOT_PREROLL, playlist.name ?: "", delay)
        if (playlist.type != Playlist.Type.ONLINE) {
            Thread {
                playlist.urlOrFolder?.split(Utils.COMMA_SPLITTER)?.indices?.forEach {
                    runCatching { DurationCacheManager.indexDirectory(this, getDirectoryFromPlaylist(playlist, it)) }
                }
            }.start()
        }
        handler?.postAtTime({ switchNow(index, false, this) }, slotCommitToken, android.os.SystemClock.uptimeMillis() + delay)
    }

    /** Drops pending slot commits, e.g. when maintenance rebuilds the schedule. */
    fun cancelPendingSlotCommits() {
        handler?.removeCallbacksAndMessages(slotCommitToken)
    }

    override fun onRenderedFirstFrame() {
        if (switchStartedAt > 0) {
            val ms = android.os.SystemClock.elapsedRealtime() - switchStartedAt
            switchStartedAt = 0
            Logger.log(AuditLog.Event.FIRST_FRAME, ms, getNowPlayingPlaylistLabel())
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            hideSlate()
        }
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
    // Duration of a media item (by mediaId) from the persistent index; ids are URIs, so decode (e.g. %20 for spaces)
    private fun getDuration(path: String): Long {
        val file = File(android.net.Uri.parse(path).path ?: path.removePrefix("file://"))
        return DurationCacheManager.getDuration(this, file)
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
                    val playlist = playlistByIndex.getOrNull(it) ?: return
                    val isFiniteType = playlist.isFiniteLocal()
                    val isAtLastItem = player?.currentMediaItemIndex == (programItems.size - 1)

                    // Only switch to fillers when a truly finite playlist finishes and nothing was queued gaplessly
                    if (isFiniteType && isAtLastItem && player?.hasNextMediaItem() != true) {
                        val lastItemName = player?.currentMediaItemIndex?.let { programItems.getOrNull(it) }?.let { getMediaItemName(it) } ?: "Unknown"
                        Logger.log(AuditLog.Event.PLAYLIST_EXHAUSTED, getPlayingAtIndexLabel(nowPlayingIndex), lastItemName)
                        switchNow(getSecondDefaultIndex(), false, this)
                        return
                    }
                }

                Player.STATE_BUFFERING -> {
                    // Let live streams buffer: seeking here restarted RTMP/SRT connections on every rebuffer.
                    // Falling behind the live window is handled in onPlayerError.
                    player?.playWhenReady = true // Ensure playback resumes smoothly
                }
            }
        }
    }

    /** Stream title for listeners: playlist name, then the programme's file name (not for online streams). */
    private fun announceOnStream(item: MediaItem) {
        val programme = if (currentPlaylist?.type == Playlist.Type.ONLINE) "" else File(getMediaItemName(item)).nameWithoutExtension
        Playout.streamTitle(listOfNotNull(currentPlaylist?.name, programme).filter { it.isNotBlank() }.joinToString(" - "))
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
    /**
     * Gapless handover: queue the fillers (or the first default when the fillers can't play) behind the last item,
     * so the player rolls straight into them instead of stopping, reloading and fading in.
     */
    private fun queueNextPlaylist() {
        if (upcomingProgramItems != null || (player?.mediaItemCount ?: 0) > programItems.size) return
        var nextIndex = getSecondDefaultIndex()
        var next = playlistByIndex.getOrNull(nextIndex) ?: return
        var items = if (next.type == Playlist.Type.ONLINE && !Utils.internetConnected()) emptyList() else maintenance?.retrievePrograms(next).orEmpty()
        if (items.isEmpty()) {
            nextIndex = getFirstDefaultIndex()
            next = playlistByIndex.getOrNull(nextIndex) ?: return
            items = maintenance?.retrievePrograms(next).orEmpty()
        }
        if (items.isEmpty()) return // nothing to queue: STATE_ENDED handles it (slate)
        if (next.type != Playlist.Type.ONLINE && !next.isResuming()) { // same bumpers as a normal switch would play
            val (intros, outros) = bumpersFor(next)
            items = intros + items + outros
        }
        upcomingProgramItems = items.map { it.buildUpon().setTag(nextIndex).build() }.toMutableList()
        player?.addMediaItems(upcomingProgramItems!!)
        Logger.log(AuditLog.Event.PLAYLIST_PRE_APPENDED, getPlayingAtIndexLabel(nextIndex))
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        nowPlayingIndex?.let {
            val itemPlaylistIndex = mediaItem?.localConfiguration?.tag as? Int
            // Rolled over into the queued playlist? A playlist that queues itself (fillers after fillers) keeps the
            // same tag, so playing past our own items also counts as a handover
            val pastOwnItems = (player?.currentMediaItemIndex ?: 0) >= programItems.size
            if (itemPlaylistIndex != null && (itemPlaylistIndex != it || pastOwnItems) && itemPlaylistIndex < playlistByIndex.size) {
                val previous = getNowPlayingPlaylistLabel()
                nowPlayingIndex = itemPlaylistIndex
                currentPlaylist = playlistByIndex[itemPlaylistIndex]
                programItems = upcomingProgramItems ?: maintenance?.retrievePrograms(currentPlaylist).orEmpty()
                    .map { item -> item.buildUpon().setTag(itemPlaylistIndex).build() }.toMutableList()
                upcomingProgramItems = null
                val current = player?.currentMediaItemIndex ?: 0
                if (current > 0) player?.removeMediaItems(0, current)
                Logger.log(AuditLog.Event.PLAYLIST_GAPLESS, previous, getNowPlayingPlaylistLabel())
                triggerGraphics(0L) // the new playlist's logo/ticker, and cancel the old one's pending graphics
            }

            nowProgramItem = player?.currentMediaItemIndex
            cacheNowPlaying(false)

            // Entering the last item of a finite playlist: queue what comes next
            if (currentPlaylist?.isFiniteLocal() == true && nowProgramItem == programItems.size - 1) {
                queueNextPlaylist()
            }

            mediaItem?.let { item ->
                Logger.log(
                    AuditLog.Event.PLAYLIST_ITEM_CHANGE,
                    getNowPlayingPlaylistLabel(),
                    getMediaItemName(item)
                )
                triggerRepeatWatermark(item)
                announceOnStream(item)
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

        Logger.log(AuditLog.Event.ERROR, "${error.errorCodeName}: ${error.cause}: ${error.message}")
        currentPlaylist?.type?.name?.let { cacheNowPlaying(it.startsWith("LOCAL_RESUMING")) }

        // Classify by errorCode: the cause chain depth varies between data sources
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                // Routine HLS/DASH hiccup: rejoin the live edge instead of leaving the stream
                Logger.log(AuditLog.Event.ERROR, "Fell behind live window, rejoining live edge")
                player?.seekToDefaultPosition()
                player?.prepare()
            }
            currentPlaylist?.type == Playlist.Type.ONLINE && error.errorCode in IO_ERROR_CODES -> {
                Logger.log(AuditLog.Event.NO_INTERNET, "Failing to play program because of no internet connection")
                failedBecauseOfInternetIndex = nowPlayingIndex

                // Fall back to fillers if the internet goes down, but only if fillers are actually installed
                // (to prevent an infinite loop of bouncing between the online stream and empty fillers)
                val fillersPlaylist = configuration?.playlists?.getOrNull(getSecondDefaultIndex())
                if (nowPlayingIndex != getSecondDefaultIndex() && fillersPlaylist != null &&
                    maintenance?.retrievePrograms(fillersPlaylist)?.isNotEmpty() == true) {
                    fillingForLackOfInternet = true
                    switchNow(getSecondDefaultIndex(), false, this)
                } else {
                    retryPrepareLater()
                }
            }
            else -> {
                Logger.log(AuditLog.Event.ERROR, "Unplayable item: ${currentItem?.mediaId}")
                handlePlaybackCrash(currentItem)
            }
        }
    }

    // A failed player sits in STATE_IDLE until prepared again; retry instead of waiting for the KeepOnAir watchdog
    private fun retryPrepareLater() {
        handler?.postDelayed({
            if (player?.playbackState == Player.STATE_IDLE) {
                player?.prepare()
            }
        }, RETRY_PREPARE_DELAY)
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
        } else if (currentPlaylist?.type != Playlist.Type.ONLINE) {
            // Local playlist: skip the bad file and carry on with the rest of the programme. A file that failed to parse
            // is never "seekable", so gating on seekability sent the whole playlist to fillers over one bad file.
            Logger.log(AuditLog.Event.ERROR, "Skipping corrupted local file: ${currentItem?.mediaId}")
            val nextItemIndex = (player?.currentMediaItemIndex ?: 0) + 1
            if (nextItemIndex < programItems.size) {
                player!!.seekTo(nextItemIndex, 0)
                player!!.prepare()
            } else {
                if (programItems.size <= 1) {
                    Logger.log(AuditLog.Event.ERROR, "Only 1 item in folder and it crashed. Switching to filler to prevent infinite loop.")
                    switchNow(getSecondDefaultIndex(), false, this)
                } else {
                    val isFiniteType = currentPlaylist?.isFiniteLocal() == true
                    if (isFiniteType) {
                        val lastItemName = currentItem?.let { getMediaItemName(it) } ?: "Unknown"
                        Logger.log(AuditLog.Event.PLAYLIST_EXHAUSTED, getPlayingAtIndexLabel(nowPlayingIndex), "Corrupted: $lastItemName")
                        switchNow(getSecondDefaultIndex(), false, this)
                    } else {
                        player!!.seekTo(0, 0)
                        player!!.prepare()
                    }
                }
            }
        } else if (nowPlayingIndex != getSecondDefaultIndex()) {
            switchNow(getSecondDefaultIndex(), false, this)
        } else {
            retryPrepareLater()
        }
    }


    private fun getPlayingAtIndexLabel(index: Int?): String {
        if (index == null || index >= playlistByIndex.size) return "Unknown #$index"
        val playlistName = playlistByIndex[getPlaylistIndex(index)].name
        return "$playlistName #$index"
    }

    /** True while a timed (scheduled) slot, not a default/filler playlist, is actually on air. */
    fun isPlayingScheduledSlot(): Boolean {
        return !currentPlaylist?.start.isNullOrBlank() && player?.isPlaying == true
    }

    fun getNowPlayingLabel(): String = getNowPlayingPlaylistLabel()

    /** True once, for the first schedule after this screen reattached to a running playout. */
    fun consumeReattach(): Boolean = reattachedToPlayout.also { reattachedToPlayout = false }

    /** Re-apply the on-air playlist's graphics on this (new) screen without touching playback. */
    @RequiresApi(Build.VERSION_CODES.M)
    fun redrawGraphics() {
        triggerGraphics(player?.currentPosition ?: 0L)
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
        // Keep the player (owned by Playout/TelefynaForegroundService) airing: just let go of it.
        // A new Monitor reattaches; it is released only when the service stops.
        Playout.detach(this)
        getPlayerView(false).player = null
        endPlayerSafely(previousPlayer)

        maintenanceHandler?.removeCallbacksAndMessages(null)
        handler?.removeCallbacksAndMessages(null)
        // Deliberately keep the KeepOnAir alarm armed: it is what relaunches Telefyna if the Activity is gone

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
        return File(getPlaylistDirectory(playlist.isUsingExternalStorage) + File.separator + (playlist.urlOrFolder?.split(Utils.COMMA_SPLITTER)
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
                scheduleKeepOnAir() // arm the watchdog even if nothing could be put on air
            }
        } else if (isAndroidTV()) {
            // Android TV / Google TV has no Settings UI for MANAGE_EXTERNAL_STORAGE.
            // The permission must be granted via ADB: adb shell appops set <package> MANAGE_EXTERNAL_STORAGE allow
            // Proceed with maintenance anyway — the app will handle missing files gracefully.
            Logger.log(AuditLog.Event.ERROR, "MANAGE_EXTERNAL_STORAGE not granted on TV. Grant via ADB: adb shell appops set ${packageName} MANAGE_EXTERNAL_STORAGE allow")
            if (!isMaintenanceStarted) {
                isMaintenanceStarted = true
                maintenance?.run()
                scheduleKeepOnAir()
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
        if (safeMode != SafeMode.OFF) return // graphics may be what keeps crashing
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
            tickerJob?.cancel()
            tickerJob = null
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
                        tickerJob = lifecycleScope.launch {
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

        // Handle lowerThird (always rescheduled from the current position)
        lowerThirdJobs.forEach { it.cancel() }
        lowerThirdJobs.clear()
        val lowerThirds = graphics?.lowerThirds
        lowerThirds?.forEach { ltd ->
            if (StringUtils.isNotBlank(ltd.starts) && ltd.file != null) {
                ltd.getStartsArray().forEach { s ->
                    val start = Math.round(s * 60 * 1000) // s is in minutes, send in ms
                    if (start >= nowPosition) {
                        val delayMillis = start - nowPosition
                        if (delayMillis > 0) {
                            lowerThirdJobs += lifecycleScope.launch {
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

    /** Logo, watermarks, lower third and ticker sized for this screen (see [GraphicsScale]). */
    private fun applyGraphicsScale(scale: Float) {
        val px = { reference: Int -> GraphicsScale.px(reference, scale) }
        fun size(id: Int, width: Int, height: Int, fit: ImageView.ScaleType, margins: (android.widget.RelativeLayout.LayoutParams) -> Unit) {
            val view = findViewById<ImageView>(id) ?: return
            view.setPadding(0, 0, 0, 0)
            view.scaleType = fit // keep the image's shape, pinned to its corner
            (view.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.width = width
                it.height = height
                margins(it)
                view.layoutParams = it
            }
        }
        size(R.id.topLogo, px(GraphicsScale.LOGO_WIDTH), px(GraphicsScale.LOGO_HEIGHT), ImageView.ScaleType.FIT_END) {
            it.topMargin = px(GraphicsScale.LOGO_TOP); it.marginEnd = px(GraphicsScale.LOGO_END)
        }
        size(R.id.bottomLogo, px(GraphicsScale.LOGO_WIDTH), px(GraphicsScale.LOGO_HEIGHT), ImageView.ScaleType.FIT_END) {
            it.bottomMargin = px(GraphicsScale.LOGO_ABOVE_TICKER); it.marginEnd = px(GraphicsScale.LOGO_END)
        }
        size(R.id.watermark, px(GraphicsScale.WATERMARK_WIDTH), px(GraphicsScale.WATERMARK_HEIGHT), ImageView.ScaleType.FIT_START) {
            it.topMargin = px(GraphicsScale.WATERMARK_TOP); it.marginStart = px(GraphicsScale.WATERMARK_START)
        }
        findViewById<View>(R.id.lowerThird)?.let { view ->
            view.layoutParams = view.layoutParams.apply { height = px(GraphicsScale.LOWER_THIRD_HEIGHT) }
        }
        findViewById<View>(R.id.tickerRecyclerView)?.let { view ->
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = px(GraphicsScale.TICKER_BOTTOM)
                view.layoutParams = it
            }
        }
        // A ticker already on screen was built at the old size: rebuild its rows
        if (::tickerRecyclerView.isInitialized && ::tickerAdapter.isInitialized) {
            tickerAdapter = TickerAdapter(tickerAdapter.items, scale)
            tickerRecyclerView.adapter = tickerAdapter
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
        tickerAdapter = TickerAdapter(tickerItems, graphicsScale)
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
        alarmManager?.let { Utils.setExactAlarm(it, System.currentTimeMillis() + delay, pendingIntent) }
    }



    /**
     * Full-screen "We'll be right back" card so viewers never get a black screen. Uses the operator's
     * <telefyna>/slate.(png|jpg|webp|gif) when present, otherwise the built-in card.
     */
    fun showSlate(reason: String) {
        runOnUiThread {
            if (!slateShown) {
                Logger.log(AuditLog.Event.SLATE_ON, reason)
            }
            slateShown = true
            player?.stop() // free the decoder and silence any frozen audio
            val slateFile = possibleExtensions
                .map { File("${getProgramsFolderPath(false)}${File.separator}slate.$it") }
                .firstOrNull { it.exists() }
            val slateImage: ImageView = findViewById(R.id.slateImage)
            if (slateFile != null) {
                Glide.with(this).load(slateFile).skipMemoryCache(true).into(slateImage)
                slateImage.visibility = View.VISIBLE
                findViewById<View>(R.id.slateDefault).visibility = View.GONE
            } else {
                slateImage.visibility = View.GONE
                findViewById<View>(R.id.slateDefault).visibility = View.VISIBLE
            }
            findViewById<View>(R.id.slate).visibility = View.VISIBLE
        }
    }

    private fun hideSlate() {
        if (!slateShown) return
        slateShown = false
        findViewById<View>(R.id.slate).visibility = View.GONE
        Logger.log(AuditLog.Event.SLATE_OFF)
    }

    private fun evaluateSafeMode() {
        val crashes = recentCrashes(sharedPreferences).size
        safeMode = when {
            crashes >= SLATE_ONLY_CRASHES -> SafeMode.SLATE_ONLY
            crashes >= FILLERS_ONLY_CRASHES -> SafeMode.FILLERS_ONLY
            else -> SafeMode.OFF
        }
        if (safeMode == SafeMode.OFF) return
        Logger.log(AuditLog.Event.SAFE_MODE, crashes, CRASH_WINDOW_MINUTES, safeMode.name)
        // Leave safe mode after a quiet period: forget the crashes and run the normal schedule again
        handler?.postDelayed({
            sharedPreferences.edit().remove(CRASH_TIMES).apply()
            safeMode = SafeMode.OFF
            Logger.log(AuditLog.Event.SAFE_MODE_OFF, SAFE_MODE_MINUTES)
            maintenance?.run()
        }, SAFE_MODE_MINUTES * 60_000L)
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
        Logger.flush()
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
        // Re-arm first so no branch below (failed reboot, exception, early return) can stop the heartbeat
        scheduleKeepOnAir()
        try {
            when {
                getRebootFile().exists() -> {
                    getRebootFile().delete()
                    rebootDevice()
                }
                getRestartFile().exists() -> {
                    getRestartFile().delete()
                    restartApp()
                }
                getReInitializerFile().exists() -> performRealtimeConfigReload()
                getReportRequestFile().exists() -> {
                    getReportRequestFile().delete()
                    // week, month and year so far, off the main thread
                    val now = System.currentTimeMillis()
                    Thread { generateReports(ReportPeriod.Kind.values().map { ReportPeriod.containing(it, now) }, toDate = true) }.start()
                }
                else -> {
                    if (getBackupConfigFile().exists()) backupConfig(false)
                    if (getBackupConfigResetFile().exists()) backupConfig(true)

                    val failedIndex = failedBecauseOfInternetIndex
                    if (nowPlayingIndex == getSecondDefaultIndex() &&
                        fillingForLackOfInternet &&
                        failedIndex != null &&
                        Utils.internetConnected()) {
                        fillingForLackOfInternet = false
                        failedBecauseOfInternetIndex = null
                        Logger.log(AuditLog.Event.INTERNET_RESTORED)
                        switchNow(failedIndex, false, this)
                    } else if (player == null || !player!!.isPlaying) {
                        Logger.log(AuditLog.Event.STUCK, (configuration?.wait ?: 0).toLong())
                        val index = nowPlayingIndex
                        if (index != null) {
                            switchNow(index, false, this)
                        } else {
                            // Nothing was ever put on air (e.g. config missing or unreadable): retry loading it
                            maintenance?.run()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "KeepOnAir check failed: ${e.message}")
        }
    }
}
