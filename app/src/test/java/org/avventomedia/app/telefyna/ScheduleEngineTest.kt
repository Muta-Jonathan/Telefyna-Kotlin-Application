package org.avventomedia.app.telefyna

import org.avventomedia.app.telefyna.modal.Config
import org.avventomedia.app.telefyna.modal.Graphics
import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.modal.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleEngineTest {

    // Wednesday 28 October 2026
    private val wednesday = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 12, 0, 0) }

    private fun config(vararg schedules: Schedule) = Config(
        playlists = arrayOf(
            Playlist(id = "default", name = "Default", type = Playlist.Type.LOCAL_SEQUENCED),
            Playlist(id = "fillers", name = "Fillers", type = Playlist.Type.LOCAL_RANDOMIZED),
            Playlist(id = "sermon", name = "Sermon", type = Playlist.Type.LOCAL_RESUMING),
            Playlist(id = "special", name = "Special", type = Playlist.Type.LOCAL_SEQUENCED)
        ),
        schedules = arrayOf(*schedules)
    )

    @Test
    fun expandKeepsBaseIndexesAndLinksSchedulesBack() {
        val table = ScheduleEngine.expand(config(
            Schedule(playlistId = "sermon", name = "Morning Sermon", start = "09:00", graphics = Graphics(displayLogo = true)),
            Schedule(playlistId = "missing", start = "10:00")
        ))
        assertEquals(listOf("Default", "Fillers", "Sermon", "Special", "Morning Sermon"), table.map { it.name })
        val copy = table[4]
        assertEquals(2, copy.schedule)          // resume state on the base playlist
        assertEquals("09:00", copy.start)
        assertEquals(Playlist.Type.LOCAL_RESUMING, copy.type)
        assertTrue(copy.graphics!!.displayLogo)
        assertNull(table[2].start)              // the base playlist itself is untouched
    }

    @Test
    fun onlySlotsRunningThatDayArePlanned() {
        val plan = ScheduleEngine.plan(config(
            Schedule(playlistId = "sermon", start = "09:00"),                              // daily
            Schedule(playlistId = "sermon", start = "10:00", days = arrayOf(Calendar.WEDNESDAY)),
            Schedule(playlistId = "sermon", start = "11:00", days = arrayOf(Calendar.SUNDAY)),
            Schedule(playlistId = "special", start = "12:00", dates = arrayOf("28-10-2026")),
            Schedule(playlistId = "special", start = "13:00", dates = arrayOf("29-10-2026")),
            Schedule(playlistId = "sermon", start = "14:00", active = false)
        ), wednesday)
        assertEquals(listOf("09:00", "10:00", "12:00"), plan.slots.map { it.start })
        assertEquals(listOf(false, false, true), plan.slots.map { it.dated })
    }

    @Test
    fun datedSlotWinsAClashInEitherOrderOtherwiseTheLaterOne() {
        for (order in listOf(true, false)) {
            val weekly = Schedule(playlistId = "sermon", name = "Weekly", start = "10:00", days = arrayOf(Calendar.WEDNESDAY))
            val dated = Schedule(playlistId = "special", name = "Christmas", start = "10:00", dates = arrayOf("28-10-2026"))
            val plan = ScheduleEngine.plan(if (order) config(weekly, dated) else config(dated, weekly), wednesday)
            assertEquals("Christmas", plan.slots.single().playlist.name)
            assertEquals(listOf(SlotClash("10:00", "Christmas", "Weekly")), plan.clashes)
        }
        val plan = ScheduleEngine.plan(config(
            Schedule(playlistId = "sermon", name = "First", start = "10:00"),
            Schedule(playlistId = "special", name = "Second", start = "10:00")
        ), wednesday)
        assertEquals("Second", plan.slots.single().playlist.name)
    }

    @Test
    fun currentAndUpcomingSlotsCompareTimesNumerically() {
        val plan = ScheduleEngine.plan(config(
            Schedule(playlistId = "sermon", name = "Ten", start = "10:00"),
            Schedule(playlistId = "special", name = "Nine", start = "9:00"),
            Schedule(playlistId = "sermon", name = "Late", start = "22:30")
        ), wednesday)
        assertEquals(listOf("Nine", "Ten", "Late"), plan.slots.map { it.playlist.name })
        assertNull(plan.current(8 * 60 + 59))
        assertEquals("Nine", plan.current(9 * 60)!!.playlist.name)
        assertEquals("Ten", plan.current(10 * 60 + 30)!!.playlist.name)
        assertEquals(listOf("Ten", "Late"), plan.upcoming(9 * 60 + 15).map { it.playlist.name })
        assertEquals(6, plan.slots.first { it.playlist.name == "Late" }.index) // index in the playlist table
    }
}
