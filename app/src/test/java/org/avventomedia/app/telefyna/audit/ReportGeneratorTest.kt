package org.avventomedia.app.telefyna.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale

class ReportGeneratorTest {
    private val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH)
    private fun t(s: String) = fmt.parse(s)!!.time

    private fun withAuditDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("audit").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    /** A daily log with [hours] of the sermon starting at 10:00, then the box switched off. */
    private fun day(dir: File, date: String, hours: Int) {
        val (d, m, y) = date.split("-")
        File(dir, "$y-$m-$d.log").writeText(
            "$date 10:00:00 PLAYLIST_SWITCH: \n\tSwitching to Playlist: Sermon #0 from: 00:00:00 /p/s.mp4\n\n" +
            "$date ${"%02d".format(10 + hours)}:00:00 HEARTBEAT: \n\tTELEFYNA has been turned: OFF\n\n")
    }

    // stand-in for the Android PDF renderer
    private val stub: (ReportData, File) -> Unit = { _, f -> f.parentFile?.mkdirs(); f.writeText("pdf") }

    @Test
    fun filesAreGroupedByYearAndKindWithTheStationName() = withAuditDir { dir ->
        val week = ReportPeriod.containing(ReportPeriod.Kind.WEEKLY, t("28-10-2026 12:00:00"))
        val month = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, t("28-10-2026 12:00:00"))
        val year = ReportPeriod.containing(ReportPeriod.Kind.YEARLY, t("28-10-2026 12:00:00"))
        fun rel(f: File) = f.relativeTo(dir).path
        assertEquals("reports/2026/weekly/Telefyna-TV_weekly_2026-W44.pdf", rel(ReportGenerator.fileFor(dir, "Telefyna TV", week, false)))
        assertEquals("reports/2026/monthly/Telefyna-TV_monthly_2026-10_to-date.pdf", rel(ReportGenerator.fileFor(dir, "Telefyna TV", month, true)))
        assertEquals("reports/2026/Telefyna-TV_yearly_2026.pdf", rel(ReportGenerator.fileFor(dir, "Telefyna TV", year, false)))
        assertEquals("Telefyna", ReportGenerator.safeName("  ***  "))
    }

    @Test
    fun completedMonthSavesASummaryAndReplacesItsToDateReport() = withAuditDir { dir ->
        day(dir, "05-10-2026", 2); day(dir, "06-10-2026", 3)
        val october = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, t("05-10-2026 12:00:00"))
        ReportGenerator.generate(dir, "Telefyna TV", listOf(october), toDate = true, now = t("20-10-2026 00:00:00"), render = stub)
        val toDate = ReportGenerator.fileFor(dir, "Telefyna TV", october, true)
        assertTrue(toDate.exists())

        val written = ReportGenerator.generate(dir, "Telefyna TV", listOf(october), toDate = false, now = t("01-11-2026 00:00:05"), render = stub)
        assertEquals(1, written.size)
        assertFalse(toDate.exists())
        val summary = ReportGenerator.summaryFor(written[0])
        val data = com.google.gson.Gson().fromJson(summary.readText(), ReportData::class.java)
        assertEquals(5 * 3_600_000L, data.airtimeByPlaylist["Sermon"])
        // written once: a second run doesn't rewrite it
        assertTrue(ReportGenerator.generate(dir, "Telefyna TV", listOf(october), toDate = false, now = t("01-11-2026 00:00:05"), render = stub).isEmpty())
    }

    @Test
    fun yearAddsUpMonthlySummariesEvenAfterLogsArePruned() = withAuditDir { dir ->
        day(dir, "05-10-2026", 2)
        val october = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, t("05-10-2026 12:00:00"))
        ReportGenerator.generate(dir, "Telefyna TV", listOf(october), toDate = false, now = t("01-11-2026 00:00:05"), render = stub)
        File(dir, "2026-10-05.log").delete() // pruned by auditRetentionDays
        day(dir, "10-11-2026", 4)            // current month, still in the logs

        var yearly: ReportData? = null
        val year = ReportPeriod.containing(ReportPeriod.Kind.YEARLY, t("10-11-2026 12:00:00"))
        ReportGenerator.generate(dir, "Telefyna TV", listOf(year), toDate = true, now = t("15-11-2026 00:00:00"),
            render = { data, f -> yearly = data; stub(data, f) })
        assertEquals(6 * 3_600_000L, yearly!!.airtimeByPlaylist["Sermon"])
        assertEquals(listOf("Oct 2026", "Nov 2026"), yearly!!.buckets.map { it.label })
    }
}
