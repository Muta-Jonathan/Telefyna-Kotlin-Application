package org.avventomedia.app.telefyna

import org.avventomedia.app.telefyna.modal.Config
import org.avventomedia.app.telefyna.modal.Playlist
import java.util.Calendar

/** A timed slot running on the day: [index] is its position in [DaySchedule.playlists]. */
data class ScheduledSlot(val index: Int, val playlist: Playlist, val start: String, val startMinutes: Int, val dated: Boolean)

/** Two slots at the same start time; [kept] airs. */
data class SlotClash(val start: String, val kept: String, val dropped: String)

/** One day's playout plan: the playlist table (base playlists, then schedules) and the slots that run that day. */
data class DaySchedule(val playlists: List<Playlist>, val slots: List<ScheduledSlot>, val clashes: List<SlotClash>) {

    /** The slot on air at [minutes] past midnight: the latest one that has started, or null before the first. */
    fun current(minutes: Int): ScheduledSlot? = slots.lastOrNull { it.startMinutes <= minutes }

    /** Slots still to start after [minutes] past midnight, in order. */
    fun upcoming(minutes: Int): List<ScheduledSlot> = slots.filter { it.startMinutes > minutes }
}

/**
 * Pure scheduling logic (no Android, no clock of its own), so every rule is unit-tested:
 * expanding schedules into the playlist table, which slots run on a day, and resolving clashes.
 */
object ScheduleEngine {

    fun minutesOf(time: Calendar): Int = time[Calendar.HOUR_OF_DAY] * 60 + time[Calendar.MINUTE]

    /**
     * Base playlists keep their indexes (0 = default, 1 = fillers); each schedule is appended as a copy of its
     * playlist with its own start/days/dates/overrides, linked back to the base through [Playlist.schedule].
     * Schedules pointing at an unknown playlist id are skipped.
     */
    fun expand(config: Config): List<Playlist> {
        val base = config.playlists.orEmpty().toList()
        val byId = base.withIndex().filter { it.value.id != null }.associate { it.value.id!! to it.index }
        val scheduled = config.schedules.orEmpty().withIndex().mapNotNull { (i, schedule) ->
            val baseIndex = byId[schedule.playlistId] ?: return@mapNotNull null
            base[baseIndex].copy().apply {
                start = schedule.start
                days = schedule.days
                dates = schedule.dates
                active = active && schedule.active
                this.schedule = baseIndex // resume state is kept on the base playlist
                id = "sched_$i"
                schedule.name?.let { name = it }
                schedule.type?.let { type = it }
                schedule.color?.let { color = it }
                schedule.graphics?.let { graphics = it }
                schedule.emptyReplacer?.let { emptyReplacer = it }
                schedule.seekTo?.let { seekTo = it }
            }
        }
        return base + scheduled
    }

    /**
     * The plan for [day]. When two slots start at the same time, one booked for that date (a special event) beats
     * a weekly/daily one whatever their order; otherwise the later entry wins.
     */
    fun plan(config: Config, day: Calendar): DaySchedule {
        val playlists = expand(config)
        val byStart = linkedMapOf<Int, ScheduledSlot>()
        val clashes = mutableListOf<SlotClash>()
        playlists.withIndex().filter { it.value.start != null && it.value.scheduledOn(day) }.forEach { (index, playlist) ->
            val (hour, minute) = playlist.parseStart() ?: return@forEach
            val slot = ScheduledSlot(index, playlist, playlist.start!!, hour * 60 + minute, playlist.scheduledByDateOn(day))
            val existing = byStart[slot.startMinutes]
            if (existing == null) {
                byStart[slot.startMinutes] = slot
            } else if (existing.dated && !slot.dated) {
                clashes += SlotClash(slot.start, existing.playlist.name.orEmpty(), slot.playlist.name.orEmpty())
            } else {
                clashes += SlotClash(slot.start, slot.playlist.name.orEmpty(), existing.playlist.name.orEmpty())
                byStart[slot.startMinutes] = slot
            }
        }
        return DaySchedule(playlists, byStart.values.sortedBy { it.startMinutes }, clashes)
    }
}
