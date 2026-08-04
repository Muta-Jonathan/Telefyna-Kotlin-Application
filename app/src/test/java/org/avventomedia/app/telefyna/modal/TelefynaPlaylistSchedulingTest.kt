package org.avventomedia.app.telefyna.modal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar

class TelefynaPlaylistSchedulingTest {

    @Test
    fun testIsStarted() {
        val now = Calendar.getInstance()
        val hour = now[Calendar.HOUR_OF_DAY]
        val min = now[Calendar.MINUTE]

        // 1. Started exactly now
        var playlist = Playlist().apply { start = String.format("%02d:%02d", hour, min) }
        assertTrue(playlist.isStarted())

        // 2. Started an hour ago
        val pastHour = if (hour > 0) hour - 1 else 23
        playlist = Playlist().apply { start = String.format("%02d:%02d", pastHour, min) }
        if (hour > 0) {
            assertTrue(playlist.isStarted())
        } else {
            assertFalse(playlist.isStarted()) // Because if it's midnight now, 23:xx is considered future today
        }

        // 3. Starts an hour in the future
        val futureHour = if (hour < 23) hour + 1 else 0
        playlist = Playlist().apply { start = String.format("%02d:%02d", futureHour, min) }
        if (hour < 23) {
            assertFalse(playlist.isStarted())
        }
    }

    @Test
    fun testScheduledToday() {
        val now = Calendar.getInstance()
        val todayDayOfWeek = now[Calendar.DAY_OF_WEEK]
        val tomorrowDayOfWeek = if (todayDayOfWeek < 7) todayDayOfWeek + 1 else 1

        val dateFormat = SimpleDateFormat("dd-MM-yyyy")
        val todayString = dateFormat.format(now.time)
        now.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrowString = dateFormat.format(now.time)
        now.add(Calendar.DAY_OF_YEAR, -1) // reset

        // 1. Inactive playlist
        var playlist = Playlist(active = false, start = "10:00")
        assertFalse(playlist.scheduledToday())

        // 2. No start time
        playlist = Playlist(active = true, start = null)
        assertFalse(playlist.scheduledToday())

        // 3. Daily (days array is null/empty)
        playlist = Playlist(active = true, start = "10:00", days = null)
        assertTrue(playlist.scheduledToday())

        // 4. Scheduled for today (by day of week)
        playlist = Playlist(active = true, start = "10:00", days = arrayOf(todayDayOfWeek))
        assertTrue(playlist.scheduledToday())

        // 5. Scheduled for tomorrow (by day of week)
        playlist = Playlist(active = true, start = "10:00", days = arrayOf(tomorrowDayOfWeek))
        assertFalse(playlist.scheduledToday())

        // 6. Scheduled for today (by exact date string)
        playlist = Playlist(active = true, start = "10:00", days = arrayOf(tomorrowDayOfWeek), dates = arrayOf(todayString))
        assertTrue(playlist.scheduledToday())

        // 7. Not scheduled for today (neither day nor date match)
        playlist = Playlist(active = true, start = "10:00", days = arrayOf(tomorrowDayOfWeek), dates = arrayOf(tomorrowString))
        assertFalse(playlist.scheduledToday())
    }

    @Test
    fun testGetScheduledTime() {
        val playlist = Playlist(start = "14:30")
        val timeInMillis = playlist.getScheduledTime()

        // It should match 14:30:00.000 of today
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeInMillis

        assertEquals(14, cal[Calendar.HOUR_OF_DAY])
        assertEquals(30, cal[Calendar.MINUTE])
        assertEquals(0, cal[Calendar.SECOND])
        assertEquals(0, cal[Calendar.MILLISECOND])

        // Test midnight buffering logic (adds 5 seconds to 00:00)
        val midnightPlaylist = Playlist(start = "00:00")
        val midnightTimeInMillis = midnightPlaylist.getScheduledTime()
        val midnightCal = Calendar.getInstance()
        midnightCal.timeInMillis = midnightTimeInMillis

        assertEquals(0, midnightCal[Calendar.HOUR_OF_DAY])
        assertEquals(0, midnightCal[Calendar.MINUTE])
        assertEquals(5, midnightCal[Calendar.SECOND]) // The 5-second maintenance offset
        assertEquals(0, midnightCal[Calendar.MILLISECOND])
    }
}
