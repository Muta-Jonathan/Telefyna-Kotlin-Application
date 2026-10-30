package org.avventomedia.app.telefyna.audit

import java.io.File

/**
 * Builds broadcast reports from the daily audit logs and files them as
 * reports/<year>/weekly|monthly/<Station>_<kind>_<period>[_to-date].pdf (yearly reports sit directly in <year>/).
 */
object ReportGenerator {

    /** e.g. reports/2026/weekly/Telefyna-TV_weekly_2026-W44.pdf */
    fun fileFor(auditDir: File, station: String, period: ReportPeriod, toDate: Boolean): File {
        val (kind, rest) = period.id.split("-", limit = 2)
        val year = rest.take(4)
        val folder = when (period.kind) {
            ReportPeriod.Kind.YEARLY -> File(auditDir, "reports/$year")
            else -> File(auditDir, "reports/$year/$kind")
        }
        val suffix = if (toDate) "_to-date" else ""
        return File(folder, "${safeName(station)}_${kind}_$rest$suffix.pdf")
    }

    fun safeName(station: String): String =
        station.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "Telefyna" }

    /** Monthly summaries (numbers only) that yearly reports are built from. */
    fun summaryFor(pdf: File): File = File(pdf.parentFile, pdf.nameWithoutExtension + ".json")

    /**
     * Writes a report for each period. Completed periods are written once (skipped if the file exists);
     * to-date reports are overwritten. Returns the files written.
     */
    fun generate(
        auditDir: File,
        station: String,
        periods: List<ReportPeriod>,
        toDate: Boolean,
        now: Long = System.currentTimeMillis(),
        render: (ReportData, File) -> Unit = { data, file -> PdfReportWriter.write(data, station, file, toDate) }
    ): List<File> {
        val gson = com.google.gson.Gson()
        // months first, so a year ending today can use the month that just ended
        return periods.sortedBy { it.kind.ordinal }.mapNotNull { period ->
            val out = fileFor(auditDir, station, period, toDate)
            if (!toDate && out.exists()) return@mapNotNull null
            val data = if (period.kind == ReportPeriod.Kind.YEARLY) {
                yearFromMonths(auditDir, station, period, now, gson)
            } else {
                val entries = AuditParser.read(auditDir, period.start, minOf(period.end, now))
                if (entries.isEmpty()) null else AuditReport.build(entries, period, now)
            } ?: return@mapNotNull null
            render(data, out)
            if (!toDate) {
                if (period.kind == ReportPeriod.Kind.MONTHLY) summaryFor(out).writeText(gson.toJson(data))
                // a completed report replaces any to-date one for the same period
                fileFor(auditDir, station, period, true).delete()
            }
            out
        }
    }

    private fun yearFromMonths(auditDir: File, station: String, year: ReportPeriod, now: Long, gson: com.google.gson.Gson): ReportData? {
        val months = mutableListOf<ReportData>()
        var month = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, year.start)
        while (month.start < minOf(year.end, now)) {
            val summary = summaryFor(fileFor(auditDir, station, month, false))
            val data = if (summary.isFile) {
                runCatching { gson.fromJson(summary.readText(), ReportData::class.java) }.getOrNull()
            } else {
                // no summary yet (e.g. the current month): use whatever daily logs remain
                AuditParser.read(auditDir, month.start, minOf(month.end, now)).takeIf { it.isNotEmpty() }
                    ?.let { AuditReport.build(it, month, now) }
            }
            data?.let { months += it }
            month = ReportPeriod.containing(ReportPeriod.Kind.MONTHLY, month.end)
        }
        return if (months.isEmpty()) null else AuditReport.combine(year, months)
    }
}
