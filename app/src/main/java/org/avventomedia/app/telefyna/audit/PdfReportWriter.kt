package org.avventomedia.app.telefyna.audit

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a [ReportData] to an A4 PDF with Android's built-in PdfDocument (no extra library). */
object PdfReportWriter {
    private const val WIDTH = 595  // A4 at 72 dpi
    private const val HEIGHT = 842
    private const val MARGIN = 40f
    private val ACCENT = Color.rgb(13, 71, 161)
    private val FILLERS = Color.rgb(100, 181, 246)
    private val SLATE = Color.rgb(255, 179, 0)
    private val MUTED = Color.rgb(110, 110, 110)
    private val RULE = Color.rgb(220, 220, 220)

    private class Writer(val doc: PdfDocument, val footer: String) {
        var pageNumber = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun newPage() {
            if (pageNumber > 0) finishPage()
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun finishPage() {
            canvas.drawText("$footer · page $pageNumber", MARGIN, HEIGHT - 20f, paint(8f, MUTED))
            doc.finishPage(page)
        }

        fun ensure(space: Float) {
            if (y + space > HEIGHT - 50f) newPage()
        }
    }

    private fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fill(color: Int) = Paint().apply { this.color = color; style = Paint.Style.FILL }

    private fun pct(part: Long, whole: Long) = if (whole <= 0) "–" else "%.1f%%".format(Locale.ENGLISH, 100.0 * part / whole)

    private fun fit(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** The station's on-air logo, top-right of the header band, scaled to fit (first frame of a GIF). */
    private fun drawLogo(canvas: Canvas, logo: File?) {
        val bitmap = logo?.takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() } ?: return
        val maxW = 130f; val maxH = 70f
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        val left = WIDTH - MARGIN - w; val top = (92f - h) / 2
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
    }

    fun write(report: ReportData, branding: ReportBranding, out: File, toDate: Boolean) {
        val station = branding.station
        val doc = PdfDocument()
        val version = listOfNotNull(branding.appVersion?.let { "Telefyna $it" } ?: "Telefyna", branding.configVersion?.let { "config $it" })
            .joinToString(" · ")
        val w = Writer(doc, "$version · $station · ${report.period.title}")
        val day = SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH)
        val stamp = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH)
        val contentWidth = WIDTH - 2 * MARGIN
        w.newPage()

        // Header band
        w.canvas.drawRect(0f, 0f, WIDTH.toFloat(), 92f, fill(ACCENT))
        drawLogo(w.canvas, branding.logo)
        w.canvas.drawText(station, MARGIN, 34f, paint(20f, Color.WHITE, bold = true))
        w.canvas.drawText("${report.period.kind.label} broadcast report — ${report.period.title}${if (toDate) " (to date)" else ""}",
            MARGIN, 56f, paint(12f, Color.WHITE))
        val lastDay = Date(minOf(report.period.end, report.coveredTo) - 1)
        w.canvas.drawText("${day.format(Date(report.period.start))} – ${day.format(lastDay)} · generated ${stamp.format(Date())} · $version",
            MARGIN, 76f, paint(9f, Color.WHITE))
        w.y = 118f

        fun section(title: String) {
            w.ensure(40f)
            w.y += 8f
            w.canvas.drawText(title, MARGIN, w.y, paint(13f, ACCENT, bold = true))
            w.y += 6f
            w.canvas.drawLine(MARGIN, w.y, WIDTH - MARGIN, w.y, Paint().apply { color = RULE })
            w.y += 16f
        }

        fun row(cells: List<String>, widths: List<Float>, p: Paint = paint(9.5f)) {
            w.ensure(16f)
            var x = MARGIN
            cells.forEachIndexed { i, c -> w.canvas.drawText(fit(c, p, widths[i] - 6f), x, w.y, p); x += widths[i] }
            w.y += 15f
        }

        // Summary
        section("Summary")
        val covered = report.coveredMs
        val onAir = report.programmeMs + report.fillersMs
        val summary = listOf(
            "Logged time" to AuditReport.formatDuration(covered),
            "On air (programmes + fillers)" to "${AuditReport.formatDuration(onAir)}  (${pct(onAir, covered)})",
            "Programmes" to "${AuditReport.formatDuration(report.programmeMs)}  (${pct(report.programmeMs, covered)})",
            "Fillers" to "${AuditReport.formatDuration(report.fillersMs)}  (${pct(report.fillersMs, covered)})",
            "\"We'll be right back\" slate" to "${AuditReport.formatDuration(report.slateMs)}  (${pct(report.slateMs, covered)})",
            "Off / restarting / unknown" to "${AuditReport.formatDuration(report.offMs)}  (${pct(report.offMs, covered)})"
        )
        summary.forEach { (k, v) -> row(listOf(k, v), listOf(230f, contentWidth - 230f)) }
        // stacked share bar
        w.ensure(24f)
        var x = MARGIN
        listOf(report.programmeMs to ACCENT, report.fillersMs to FILLERS, report.slateMs to SLATE, report.offMs to RULE).forEach { (ms, color) ->
            val width = if (covered > 0) contentWidth * ms / covered else 0f
            w.canvas.drawRect(x, w.y - 8f, x + width, w.y + 6f, fill(color)); x += width
        }
        w.y += 22f

        // Airtime by playlist
        if (report.airtimeByPlaylist.isNotEmpty()) {
            section("Airtime by playlist")
            val max = report.airtimeByPlaylist.values.maxOrNull() ?: 1L
            report.airtimeByPlaylist.forEach { (name, ms) ->
                w.ensure(18f)
                val label = if (name in report.fillersPlaylists) "$name (fillers)" else name
                w.canvas.drawText(fit(label, paint(9.5f), 150f), MARGIN, w.y, paint(9.5f))
                val barWidth = (contentWidth - 260f) * ms / max
                w.canvas.drawRect(MARGIN + 160f, w.y - 9f, MARGIN + 160f + barWidth, w.y + 2f,
                    fill(if (name in report.fillersPlaylists) FILLERS else ACCENT))
                w.canvas.drawText("${AuditReport.formatDuration(ms)}  ${pct(ms, covered)}", WIDTH - MARGIN - 90f, w.y, paint(9f, MUTED))
                w.y += 17f
            }
        }

        // Per day / per month
        if (report.buckets.isNotEmpty()) {
            section(if (report.period.kind == ReportPeriod.Kind.YEARLY) "On air by month" else "On air by day")
            row(listOf("Period", "On air", "Slate", "Logged"), listOf(130f, 150f, 120f, contentWidth - 400f), paint(9f, MUTED, bold = true))
            report.buckets.forEach { b ->
                row(listOf(b.label, "${AuditReport.formatDuration(b.onAirMs)}  (${pct(b.onAirMs, b.coveredMs)})",
                    AuditReport.formatDuration(b.slateMs), AuditReport.formatDuration(b.coveredMs)),
                    listOf(130f, 150f, 120f, contentWidth - 400f))
            }
        }

        // Top programmes
        // fillers are summarised under "Airtime by playlist"; this list is for actual programmes
        val programmes = report.programmes.filter { it.playlist !in report.fillersPlaylists }
        if (programmes.isNotEmpty()) {
            section("Most aired programmes")
            val widths = listOf(240f, 120f, 85f, contentWidth - 445f)
            row(listOf("Programme", "Playlist", "Airtime", "Plays"), widths, paint(9f, MUTED, bold = true))
            programmes.take(15).forEach { p ->
                row(listOf(p.name, p.playlist, AuditReport.formatDuration(p.airtimeMs), p.plays.toString()), widths)
            }
        }

        // Reliability
        section("Reliability")
        val outage = if (report.internetOutages == 0) "none" else "${report.internetOutages} (${AuditReport.formatDuration(report.internetOutageMs)} in total)"
        listOf(
            "App restarts" to report.restarts.toString(),
            "Crash-loop safe mode entries" to report.safeModeEntries.toString(),
            "Stuck player recoveries" to report.stuckRecoveries.toString(),
            "Watchdog relaunches" to report.watchdogRelaunches.toString(),
            "Internet outages" to outage,
            "Switch time (to first picture)" to if (report.switchesMeasured == 0) "not measured" else
                "average ${report.averageSwitchMs} ms, slowest ${report.slowestSwitchMs} ms (${report.switchesMeasured} switches)"
        ).forEach { (k, v) -> row(listOf(k, v), listOf(230f, contentWidth - 230f)) }

        if (report.scheduleClashes.isNotEmpty()) {
            section("Schedule clashes")
            report.scheduleClashes.forEach { (msg, n) -> row(listOf(msg, "×$n"), listOf(contentWidth - 50f, 50f)) }
        }

        if (report.topErrors.isNotEmpty()) {
            section("Most frequent errors")
            report.topErrors.forEach { e -> row(listOf(e.message, "×${e.count}"), listOf(contentWidth - 50f, 50f), paint(8.5f)) }
        }

        w.finishPage()
        out.parentFile?.mkdirs()
        val temp = File(out.parentFile, "${out.name}.tmp")
        temp.outputStream().use { doc.writeTo(it) }
        doc.close()
        if (!temp.renameTo(out)) {
            out.delete(); temp.renameTo(out)
        }
    }
}
