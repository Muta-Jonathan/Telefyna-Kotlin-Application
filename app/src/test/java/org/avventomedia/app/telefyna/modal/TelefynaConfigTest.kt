package org.avventomedia.app.telefyna.modal

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelefynaConfigTest {

    @Test
    fun testBooleanMappings() {
        // Automation check
        var config = Config(automationDisabled = true)
        assertTrue(config.isAutomationDisabled)

        config = Config(automationDisabled = false)
        assertFalse(config.isAutomationDisabled)

        // Notifications check
        config = Config(notificationsDisabled = true)
        assertTrue(config.isNotificationsDisabled)

        config = Config(notificationsDisabled = false)
        assertFalse(config.isNotificationsDisabled)
    }

    @Test
    fun testJsonDeserialization() {
        val json = """
            {
              "name": "Test Config",
              "automationDisabled": false,
              "playlists": [
                {
                  "id": "abc-123",
                  "name": "Morning Praise",
                  "active": true
                }
              ],
              "schedules": [
                {
                  "playlistId": "abc-123",
                  "active": true,
                  "start": "08:00"
                }
              ]
            }
        """.trimIndent()

        val gson = Gson()
        val config = gson.fromJson(json, Config::class.java)

        assertEquals("Test Config", config.name)
        assertFalse(config.isAutomationDisabled)
        
        assertEquals(1, config.playlists?.size)
        assertEquals("abc-123", config.playlists?.get(0)?.id)
        
        assertEquals(1, config.schedules?.size)
        assertEquals("abc-123", config.schedules?.get(0)?.playlistId)
        assertEquals("08:00", config.schedules?.get(0)?.start)
    }
}
