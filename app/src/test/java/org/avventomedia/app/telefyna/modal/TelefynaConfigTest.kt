package org.avventomedia.app.telefyna.modal

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
}
