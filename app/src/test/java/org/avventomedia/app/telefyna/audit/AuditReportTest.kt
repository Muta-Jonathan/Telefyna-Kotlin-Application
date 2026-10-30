package org.avventomedia.app.telefyna.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AuditReportTest {

    private val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH)
    private fun t(s: String) = fmt.parse(s)!!.time
    private fun log(vararg lines: Pair<String, String>) =
        lines.joinToString("") { (time, rest) -> val (ev, msg) = rest.split("|", limit = 2); "$time $ev: \n\t$msg\n\n" }

    // Monday 26 Oct 2026 .. Sunday 1 Nov 2026 is ISO week 44
    private val week = ReportPeriod.containing(ReportPeriod.Kind.WEEKLY, t("28-10-2026 12:00:00"))

    @Test
    fun parsesHeadersAndMultiLineMessages() {
        val entries = AuditParser.parse(log(
            "26-10-2026 10:00:00" to "PLAYLIST|----[ Preparing to play playlist: Sermon #0: {\n  \"a\": 1\n}",
            "26-10-2026 10:00:01" to "PLAYLIST_SWITCH|Switching to Playlist: Sermon #0 from: 00:00:00 /x/sermon.mp4"
        ))
        assertEquals(listOf("PLAYLIST", "PLAYLIST_SWITCH"), entries.map { it.event })
        assertTrue(entries[0].message.contains("\"a\": 1"))
        assertEquals(t("26-10-2026 10:00:01"), entries[1].time)
    }

    @Test
    fun periodsAreIsoWeeksMonthsAndYears() {
        assertEquals(t("26-10-2026 00:00:00"), week.start)
        assertEquals(t("02-11-2026 00:00:00"), week.end)
        assertEquals("Week 44, 2026", week.title)
        assertEquals("weekly-2026-W44", week.id)
        val month = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, t("15-10-2026 08:00:00"))
        assertEquals("monthly-2026-10", month.id)
        // 28 Dec 2026 belongs to ISO week 53 of 2026; 1 Jan 2027 too (its Thursday is 31 Dec 2026)
        assertEquals("weekly-2026-W53", ReportPeriod.containing(ReportPeriod.Kind.WEEKLY, t("01-01-2027 10:00:00")).id)
    }

    @Test
    fun periodsEndedByMidnightMaintenance() {
        // Monday 2 Nov 2026 00:00: week 44 just ended; October ended the day before, not now
        assertEquals(listOf(ReportPeriod.Kind.WEEKLY), ReportPeriod.endedBy(t("02-11-2026 00:00:05")).map { it.kind })
        // Sunday 1 Nov 2026: October just ended
        assertEquals(listOf(ReportPeriod.Kind.MONTHLY), ReportPeriod.endedBy(t("01-11-2026 00:00:05")).map { it.kind })
        // Friday 1 Jan 2027: December and 2026 ended (the week didn't)
        assertEquals(setOf(ReportPeriod.Kind.MONTHLY, ReportPeriod.Kind.YEARLY), ReportPeriod.endedBy(t("01-01-2027 00:00:05")).map { it.kind }.toSet())
    }

    @Test
    fun airtimeIsAttributedPerPlaylistProgrammeAndDay() {
        val entries = AuditParser.parse(log(
            "26-10-2026 10:00:00" to "PLAYLIST_SWITCH|Switching to Playlist: Sermon #0 from: 00:00:00 /p/sermon-1.mp4",
            "26-10-2026 11:00:00" to "PLAYLIST_ITEM_CHANGE|Playing playlist: Sermon #0 now playing: /p/sermon-2.mp4",
            "26-10-2026 11:30:00" to "PLAYLIST_SWITCH|Switching to Playlist: Fillers #1 from: 00:00:00 /f/jelly.mp4",
            "26-10-2026 12:00:00" to "SLATE_ON|Showing emergency slate: Nothing to play",
            "26-10-2026 12:10:00" to "SLATE_OFF|Emergency slate cleared, back on air",
            "26-10-2026 12:20:00" to "HEARTBEAT|TELEFYNA has been turned: OFF"
        ))
        val r = AuditReport.build(entries, week, now = t("26-10-2026 13:00:00"))
        assertEquals(90 * 60_000L, r.airtimeByPlaylist["Sermon"])
        assertEquals(40 * 60_000L, r.airtimeByPlaylist["Fillers"]) // 30m before the slate + 10m after it
        assertEquals(setOf("Fillers"), r.fillersPlaylists)
        assertEquals(90 * 60_000L, r.programmeMs)
        assertEquals(40 * 60_000L, r.fillersMs)
        assertEquals(10 * 60_000L, r.slateMs)
        assertEquals(40 * 60_000L, r.offMs) // 12:20 -> 13:00
        assertEquals(listOf("sermon-1.mp4", "jelly.mp4", "sermon-2.mp4"), r.programmes.map { it.name }) // by airtime
        assertEquals(1, r.buckets.size)
        assertEquals("Mon 26 Oct", r.buckets[0].label)
    }

    @Test
    fun longSilencesCountAsOffAndReliabilityEventsAreCounted() {
        val entries = AuditParser.parse(log(
            "26-10-2026 09:00:00" to "PLAYLIST_SWITCH|Switching to Playlist: Sermon #0 from: 00:00:00 /p/a.mp4",
            "26-10-2026 09:30:00" to "NO_INTERNET|Failing to play program because of no internet connection",
            "26-10-2026 09:45:00" to "INTERNET_RESTORED|Internet has been restored",
            "26-10-2026 10:00:00" to "ERROR|RemoteServiceException: shell-induced crash",
            "26-10-2026 10:00:01" to "RESTARTING|Restarting Telefyna",
            "26-10-2026 10:00:05" to "ERROR|App Relaunch [KeepOnAirReceiver]. OS Kill Reason: CRASH",
            "26-10-2026 10:00:06" to "SAFE_MODE|Crash loop: 3 crashes in 10 minutes, starting in safe mode FILLERS_ONLY",
            "26-10-2026 10:00:07" to "SCHEDULE_CLASH|Slots clash at 10:00: airing Special instead of Weekly",
            "26-10-2026 10:00:08" to "PLAYLIST_SWITCH|Switching to Playlist: Sermon #0 from: 00:00:00 /p/a.mp4",
            // box off for more than a day: only 25 h may be counted as airtime
            "28-10-2026 12:00:00" to "PLAYLIST_SWITCH|Switching to Playlist: Sermon #0 from: 00:00:00 /p/a.mp4"
        ))
        val r = AuditReport.build(entries, week, now = t("28-10-2026 12:00:00"))
        assertEquals(1, r.restarts)
        assertEquals(1, r.safeModeEntries)
        assertEquals(1, r.watchdogRelaunches)
        assertEquals(1, r.internetOutages)
        assertEquals(15 * 60_000L, r.internetOutageMs)
        assertEquals(mapOf("Slots clash at 10:00: airing Special instead of Weekly" to 1), r.scheduleClashes)
        assertEquals(listOf(ErrorStat("RemoteServiceException: shell-induced crash", 1)), r.topErrors)
        val sermon = r.airtimeByPlaylist["Sermon"]!!
        assertEquals(60 * 60_000L + 1_000L + 25 * 60 * 60_000L, sermon) // 1h 1s until the 10:00:01 restart + capped 25h
        assertTrue(r.offMs > 0)
    }

    @Test
    fun durationsAndErrorGroupingReadWell() {
        assertEquals("45m", AuditReport.formatDuration(45 * 60_000L))
        assertEquals("24h 0m", AuditReport.formatDuration(24 * 3_600_000L))
        assertEquals("2d 3h 5m", AuditReport.formatDuration((51 * 60 + 5) * 60_000L))
        val entries = AuditParser.parse(log(
            "26-10-2026 10:00:00" to "ERROR|ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: androidx.media3.datasource.HttpDataSource failed after 3 tries",
            "26-10-2026 11:00:00" to "ERROR|ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: androidx.media3.datasource.HttpDataSource failed after 5 tries"
        ))
        val errors = AuditReport.build(entries, week, now = t("26-10-2026 12:00:00")).topErrors
        assertEquals(listOf(ErrorStat("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: androidx.media3.datasource.HttpDataSource failed after # tries", 2)), errors)
    }
}
