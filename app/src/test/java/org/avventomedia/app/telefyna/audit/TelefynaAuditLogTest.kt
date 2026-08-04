package org.avventomedia.app.telefyna.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelefynaAuditLogTest {

    @Test
    fun testFormatMessage() {
        val event = AuditLog.Event.CONFIGURATION
        val formatted = event.formatMessage()
        
        // It should contain the original message and the separator (\n\n)
        assertTrue(formatted.contains("Initialized configurations"))
        assertTrue(formatted.contains("\n\n"))
    }

    @Test
    fun testGetCategory() {
        // Test Admin Events
        assertEquals(AuditLog.Event.Category.ADMIN, AuditLog.Event.HEARTBEAT.getCategory())
        assertEquals(AuditLog.Event.Category.ADMIN, AuditLog.Event.CRASH.getCategory())
        
        // Test Broadcast Events
        assertEquals(AuditLog.Event.Category.BROADCAST, AuditLog.Event.PLAYLIST_PLAY.getCategory())
        assertEquals(AuditLog.Event.Category.BROADCAST, AuditLog.Event.LOWER_THIRD_ON.getCategory())
        
        // Test System Events
        assertEquals(AuditLog.Event.Category.SYSTEM, AuditLog.Event.NO_INTERNET.getCategory())
        assertEquals(AuditLog.Event.Category.SYSTEM, AuditLog.Event.EMAIL.getCategory())
    }
}
