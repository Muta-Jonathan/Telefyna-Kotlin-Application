package org.avventomedia.app.telefyna.modal

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ScheduleTest {

    @Test
    fun testScheduleDeserializationLogic() {
        val schedule = Schedule(
            playlistId = "123e4567-e89b-12d3-a456-426614174000",
            active = true,
            start = "14:30",
            days = arrayOf(1, 2, 3, 4, 5, 6, 7),
            dates = null
        )

        assertEquals("123e4567-e89b-12d3-a456-426614174000", schedule.playlistId)
        assertTrue(schedule.active)
        assertEquals("14:30", schedule.start)
    }

    @Test
    fun testScheduledToday_active() {
        val schedule = Schedule(active = false)
        assertFalse("Should be false when inactive", schedule.scheduledToday())
    }

    @Test
    fun testScheduledToday_noDaysNoDates() {
        // If days is null and dates is null, it should run daily
        val schedule = Schedule(active = true, days = null, dates = null)
        assertTrue("Should run daily if days and dates are null", schedule.scheduledToday())
    }

    @Test
    fun testScheduledToday_specificDay() {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK)
        
        val schedule = Schedule(active = true, days = arrayOf(today), dates = null)
        assertTrue("Should run if today is in the days array", schedule.scheduledToday())
        
        val tomorrow = if (today == 7) 1 else today + 1
        val scheduleTomorrow = Schedule(active = true, days = arrayOf(tomorrow), dates = null)
        assertFalse("Should NOT run if today is not in the days array", scheduleTomorrow.scheduledToday())
    }

    @Test
    fun testScheduledToday_specificDate() {
        val cal = Calendar.getInstance()
        val todayDate = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault()).format(cal.time)
        
        val schedule = Schedule(active = true, days = null, dates = arrayOf(todayDate))
        assertTrue("Should run if today's date is in the dates array", schedule.scheduledToday())
        
        val scheduleOtherDate = Schedule(active = true, days = null, dates = arrayOf("01-01-2099"))
        assertFalse("Should NOT run if today's date is not in the dates array", scheduleOtherDate.scheduledToday())
    }
}
