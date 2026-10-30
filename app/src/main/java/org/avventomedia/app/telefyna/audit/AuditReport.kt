package org.avventomedia.app.telefyna.audit

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One audit log entry: "dd-MM-yyyy HH:mm:ss EVENT: \n\t<message>". */
data class AuditEntry(val time: Long, val event: String, val message: String)

object AuditParser {
    private val HEADER = Regex("""^(\d{2}-\d{2}-\d{4} \d{2}:\d{2}:\d{2}) ([A-Z_]+):\s*$""")

    fun parse(text: String): List<AuditEntry> {
        val format = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH)
        val entries = mutableListOf<AuditEntry>()
        var time = 0L
        var event: String? = null
        val message = StringBuilder()
        fun flush() {
            event?.let { entries.add(AuditEntry(time, it, message.toString().trim())) }
            message.setLength(0)
        }
        text.lineSequence().forEach { line ->
            val header = HEADER.matchEntire(line)
            val parsed = header?.let { runCatching { format.parse(it.groupValues[1]) }.getOrNull() }
            if (header != null && parsed != null) {
                flush()
                time = parsed.time
                event = header.groupValues[2]
            } else if (event != null) {
                message.append(line.removePrefix("\t")).append('\n')
            }
        }
        flush()
        return entries
    }

    /** Entries from the daily logs (yyyy-MM-dd.log) in [dir] covering [from, to), sorted by time. */
    fun read(dir: File, from: Long, to: Long): List<AuditEntry> {
        val name = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val day = Calendar.getInstance().apply { timeInMillis = from; truncateToDay() }
        val entries = mutableListOf<AuditEntry>()
        while (day.timeInMillis < to) {
            val file = File(dir, "${name.format(day.time)}${AuditLog.ENDPOINT}")
            if (file.isFile) entries += parse(file.readText()).filter { it.time in from until to }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return entries.sortedBy { it.time }
    }
}

internal fun Calendar.truncateToDay() {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** A reporting period [start, end): a Monday-to-Sunday week, a calendar month or a calendar year. */
data class ReportPeriod(val kind: Kind, val start: Long, val end: Long) {
    enum class Kind(val label: String) { WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly") }

    /** e.g. "Week 44, 2026", "October 2026", "2026" */
    val title: String
        get() {
            val c = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = start }
            return when (kind) {
                Kind.WEEKLY -> "Week ${c.get(Calendar.WEEK_OF_YEAR)}, ${c.getWeekYearCompat()}"
                Kind.MONTHLY -> SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(c.time)
                Kind.YEARLY -> c.get(Calendar.YEAR).toString()
            }
        }

    /** File-name friendly id, e.g. "weekly-2026-W44", "monthly-2026-10", "yearly-2026" */
    val id: String
        get() {
            val c = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4; timeInMillis = start }
            return when (kind) {
                Kind.WEEKLY -> "weekly-${c.getWeekYearCompat()}-W%02d".format(c.get(Calendar.WEEK_OF_YEAR))
                Kind.MONTHLY -> "monthly-" + SimpleDateFormat("yyyy-MM", Locale.ENGLISH).format(c.time)
                Kind.YEARLY -> "yearly-${c.get(Calendar.YEAR)}"
            }
        }

    companion object {
        // ISO week-year: the year of this week's Thursday (Calendar.getWeekYear() needs API 24)
        private fun Calendar.getWeekYearCompat(): Int {
            val isoDayOfWeek = (get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Monday = 1 ... Sunday = 7
            val thursday = clone() as Calendar
            thursday.add(Calendar.DAY_OF_YEAR, 4 - isoDayOfWeek)
            return thursday.get(Calendar.YEAR)
        }

        /** The period of [kind] that contains [time]. */
        fun containing(kind: Kind, time: Long): ReportPeriod {
            val start = Calendar.getInstance().apply { timeInMillis = time; truncateToDay() }
            when (kind) {
                Kind.WEEKLY -> while (start.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) start.add(Calendar.DAY_OF_YEAR, -1)
                Kind.MONTHLY -> start.set(Calendar.DAY_OF_MONTH, 1)
                Kind.YEARLY -> start.set(Calendar.DAY_OF_YEAR, 1)
            }
            val end = (start.clone() as Calendar).apply {
                when (kind) {
                    Kind.WEEKLY -> add(Calendar.DAY_OF_YEAR, 7)
                    Kind.MONTHLY -> add(Calendar.MONTH, 1)
                    Kind.YEARLY -> add(Calendar.YEAR, 1)
                }
            }
            return ReportPeriod(kind, start.timeInMillis, end.timeInMillis)
        }

        /** Periods that ended at the start of [today] (run at midnight maintenance). */
        fun endedBy(today: Long): List<ReportPeriod> {
            val todayStart = Calendar.getInstance().apply { timeInMillis = today; truncateToDay() }
            val yesterday = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            return Kind.values().map { containing(it, yesterday) }.filter { it.end == todayStart.timeInMillis }
        }
    }
}

/** What a report shows; built from audit entries, independent of how it is rendered. */
data class ReportData(
    val period: ReportPeriod,
    val coveredFrom: Long,
    val coveredTo: Long,
    val airtimeByPlaylist: Map<String, Long>,
    val fillersPlaylists: Set<String>,
    val programmes: List<ProgrammeStat>,
    val slateMs: Long,
    val offMs: Long,
    val buckets: List<Bucket>,
    val restarts: Int,
    val safeModeEntries: Int,
    val stuckRecoveries: Int,
    val watchdogRelaunches: Int,
    val internetOutages: Int,
    val internetOutageMs: Long,
    val scheduleClashes: Map<String, Int>,
    val topErrors: List<ErrorStat>,
    // time from a playlist switch to its first picture (FIRST_FRAME)
    val switchesMeasured: Int = 0,
    val averageSwitchMs: Long = 0,
    val slowestSwitchMs: Long = 0
) {
    val programmeMs: Long get() = airtimeByPlaylist.filterKeys { it !in fillersPlaylists }.values.sum()
    val fillersMs: Long get() = airtimeByPlaylist.filterKeys { it in fillersPlaylists }.values.sum()
    val coveredMs: Long get() = (coveredTo - coveredFrom).coerceAtLeast(0)
}

data class ErrorStat(val message: String, val count: Int)

data class ProgrammeStat(val name: String, val playlist: String, val airtimeMs: Long, val plays: Int)

/** On-air share per day (weekly/monthly) or per month (yearly). */
data class Bucket(val label: String, val onAirMs: Long, val slateMs: Long, val coveredMs: Long)

object AuditReport {
    // Midnight maintenance logs daily, so a longer silence means the box was off
    private const val MAX_SILENCE_MS = 25 * 60 * 60 * 1000L
    private val PLAYLIST_EVENT = Regex("""Playlist: (.+?) #(\d+) from: \S+ ?(.*)""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM_EVENT = Regex("""playlist: (.+?) #(\d+) now playing: (.*)""", RegexOption.DOT_MATCHES_ALL)
    private const val FILLERS_INDEX = "1" // by convention the second playlist is the fillers/offline fallback

    private sealed class State {
        data class Playing(val playlist: String, val index: String, val programme: String) : State()
        object Slate : State()
        object Off : State()
    }

    fun build(entries: List<AuditEntry>, period: ReportPeriod, now: Long = System.currentTimeMillis()): ReportData {
        val until = minOf(period.end, now)
        val inPeriod = entries.filter { it.time in period.start until until }.sortedBy { it.time }
        val coveredFrom = inPeriod.firstOrNull()?.time ?: until

        val airtime = linkedMapOf<String, Long>()
        val fillers = mutableSetOf<String>()
        val programmeTime = linkedMapOf<Pair<String, String>, Long>()
        val programmePlays = linkedMapOf<Pair<String, String>, Int>()
        var slateMs = 0L
        var offMs = 0L
        val bucketKind = if (period.kind == ReportPeriod.Kind.YEARLY) Calendar.MONTH else Calendar.DAY_OF_YEAR
        val buckets = linkedMapOf<Long, LongArray>() // start -> [onAir, slate, covered]

        fun bucketStart(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t; truncateToDay(); if (bucketKind == Calendar.MONTH) set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        fun nextBucket(start: Long): Long = Calendar.getInstance().apply { timeInMillis = start; add(bucketKind, 1) }.timeInMillis

        // Attribute [from, to) to a state, split across day/month buckets
        fun account(state: State, from: Long, to: Long) {
            if (to <= from) return
            var t = from
            while (t < to) {
                val b = bucketStart(t)
                val segmentEnd = minOf(to, nextBucket(b))
                val ms = segmentEnd - t
                val acc = buckets.getOrPut(b) { LongArray(3) }
                acc[2] += ms
                when (state) {
                    is State.Playing -> {
                        acc[0] += ms
                        airtime[state.playlist] = (airtime[state.playlist] ?: 0) + ms
                        val key = state.programme to state.playlist
                        if (state.programme.isNotBlank()) programmeTime[key] = (programmeTime[key] ?: 0) + ms
                    }
                    State.Slate -> { acc[1] += ms; slateMs += ms }
                    State.Off -> offMs += ms
                }
                t = segmentEnd
            }
        }

        var state: State = State.Off
        var lastPlaying: State? = null
        var cursor = coveredFrom
        var restarts = 0; var safeMode = 0; var stuck = 0; var relaunches = 0
        var outages = 0; var outageMs = 0L; var outageStart: Long? = null
        val clashes = linkedMapOf<String, Int>()
        val errors = linkedMapOf<String, Int>()
        val switchTimes = mutableListOf<Long>()
        val firstFrame = Regex("""First picture (\d+) ms""")

        fun moveTo(t: Long, next: State) {
            val silenceCap = minOf(t, cursor + MAX_SILENCE_MS)
            account(state, cursor, silenceCap)
            account(State.Off, silenceCap, t)
            cursor = t
            state = next
        }

        for (e in inPeriod) {
            when (e.event) {
                "PLAYLIST_PLAY", "PLAYLIST_SWITCH" -> PLAYLIST_EVENT.find(e.message)?.let { m ->
                    val (name, index, programme) = m.destructured
                    if (index == FILLERS_INDEX) fillers += name
                    val next = State.Playing(name, index, programme.trim().substringAfterLast('/'))
                    moveTo(e.time, next); lastPlaying = next
                    programmePlays.merge(next.programme to name, 1, Int::plus)
                }
                "PLAYLIST_ITEM_CHANGE" -> ITEM_EVENT.find(e.message)?.let { m ->
                    val (name, index, programme) = m.destructured
                    if (index == FILLERS_INDEX) fillers += name
                    val next = State.Playing(name, index, programme.trim().substringAfterLast('/'))
                    if (next != state) {
                        moveTo(e.time, next); lastPlaying = next
                        programmePlays.merge(next.programme to name, 1, Int::plus)
                    }
                }
                "SLATE_ON" -> moveTo(e.time, State.Slate)
                "SLATE_OFF" -> moveTo(e.time, lastPlaying ?: State.Off)
                "RESTARTING" -> { restarts++; moveTo(e.time, State.Off) }
                "HEARTBEAT" -> if (e.message.trim().endsWith("OFF")) moveTo(e.time, State.Off)
                "SAFE_MODE" -> safeMode++
                "STUCK" -> stuck++
                "NO_INTERNET" -> if (outageStart == null && e.message.contains("no internet")) { outages++; outageStart = e.time }
                "INTERNET_RESTORED" -> outageStart?.let { outageMs += e.time - it; outageStart = null }
                "FIRST_FRAME" -> firstFrame.find(e.message)?.let { switchTimes += it.groupValues[1].toLong() }
                "SCHEDULE_CLASH" -> clashes.merge(e.message.lineSequence().first(), 1, Int::plus)
                "ERROR" -> {
                    val first = e.message.lineSequence().first().trim()
                    if (first.startsWith("App Relaunch")) relaunches++
                    else if (!first.startsWith("Unable to resolve host")) errors.merge(normaliseError(first), 1, Int::plus)
                }
            }
        }
        moveTo(until, state)
        outageStart?.let { outageMs += until - it }

        val programmes = programmeTime.map { (key, ms) -> ProgrammeStat(key.first, key.second, ms, programmePlays[key] ?: 0) }
            .sortedByDescending { it.airtimeMs }
        val bucketLabel = SimpleDateFormat(if (bucketKind == Calendar.MONTH) "MMM yyyy" else "EEE dd MMM", Locale.ENGLISH)
        return ReportData(
            period = period,
            coveredFrom = coveredFrom,
            coveredTo = until,
            airtimeByPlaylist = airtime.toList().sortedByDescending { it.second }.toMap(),
            fillersPlaylists = fillers,
            programmes = programmes,
            slateMs = slateMs,
            offMs = offMs,
            buckets = buckets.map { (start, acc) -> Bucket(bucketLabel.format(start), acc[0], acc[1], acc[2]) },
            restarts = restarts,
            safeModeEntries = safeMode,
            stuckRecoveries = stuck,
            watchdogRelaunches = relaunches,
            internetOutages = outages,
            internetOutageMs = outageMs,
            scheduleClashes = clashes,
            topErrors = errors.map { ErrorStat(it.key, it.value) }.sortedByDescending { it.count }.take(8),
            switchesMeasured = switchTimes.size,
            averageSwitchMs = if (switchTimes.isEmpty()) 0 else switchTimes.sum() / switchTimes.size,
            slowestSwitchMs = switchTimes.maxOrNull() ?: 0
        )
    }

    /**
     * Yearly report from monthly summaries: daily logs are pruned after auditRetentionDays, so a year is built by
     * adding up the months (each month becomes one bucket).
     */
    fun combine(period: ReportPeriod, months: List<ReportData>): ReportData {
        val airtime = linkedMapOf<String, Long>()
        val programmes = linkedMapOf<Pair<String, String>, ProgrammeStat>()
        val clashes = linkedMapOf<String, Int>()
        val errors = linkedMapOf<String, Int>()
        months.forEach { m ->
            m.airtimeByPlaylist.forEach { (k, v) -> airtime[k] = (airtime[k] ?: 0) + v }
            m.programmes.forEach { p ->
                val key = p.name to p.playlist
                val prev = programmes[key]
                programmes[key] = if (prev == null) p else prev.copy(airtimeMs = prev.airtimeMs + p.airtimeMs, plays = prev.plays + p.plays)
            }
            m.scheduleClashes.forEach { (k, v) -> clashes.merge(k, v, Int::plus) }
            m.topErrors.forEach { errors.merge(it.message, it.count, Int::plus) }
        }
        val sorted = months.sortedBy { it.period.start }
        val monthLabel = SimpleDateFormat("MMM yyyy", Locale.ENGLISH)
        return ReportData(
            period = period,
            coveredFrom = sorted.firstOrNull()?.coveredFrom ?: period.start,
            coveredTo = sorted.lastOrNull()?.coveredTo ?: period.start,
            airtimeByPlaylist = airtime.toList().sortedByDescending { it.second }.toMap(),
            fillersPlaylists = months.flatMap { it.fillersPlaylists }.toSet(),
            programmes = programmes.values.sortedByDescending { it.airtimeMs },
            slateMs = months.sumOf { it.slateMs },
            offMs = months.sumOf { it.offMs },
            buckets = sorted.map { Bucket(monthLabel.format(it.period.start), it.programmeMs + it.fillersMs, it.slateMs, it.coveredMs) },
            restarts = months.sumOf { it.restarts },
            safeModeEntries = months.sumOf { it.safeModeEntries },
            stuckRecoveries = months.sumOf { it.stuckRecoveries },
            watchdogRelaunches = months.sumOf { it.watchdogRelaunches },
            internetOutages = months.sumOf { it.internetOutages },
            internetOutageMs = months.sumOf { it.internetOutageMs },
            scheduleClashes = clashes,
            topErrors = errors.map { ErrorStat(it.key, it.value) }.sortedByDescending { it.count }.take(8),
            switchesMeasured = months.sumOf { it.switchesMeasured },
            averageSwitchMs = months.sumOf { it.averageSwitchMs * it.switchesMeasured }.let { total ->
                val n = months.sumOf { it.switchesMeasured }; if (n == 0) 0 else total / n
            },
            slowestSwitchMs = months.maxOfOrNull { it.slowestSwitchMs } ?: 0
        )
    }

    // Group errors that differ only in paths, numbers or ids
    private fun normaliseError(message: String): String =
        message.replace(Regex("""(file://)?/\S+"""), "…").replace(Regex("""\b\d+\b"""), "#").take(110)

    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        val mins = minutes % 60
        // a day reads better as "24h 0m"; switch to days only for longer spans
        return when {
            minutes >= 48 * 60 -> "${minutes / (24 * 60)}d ${minutes / 60 % 24}h ${mins}m"
            minutes >= 60 -> "${minutes / 60}h ${mins}m"
            else -> "${mins}m"
        }
    }
}
