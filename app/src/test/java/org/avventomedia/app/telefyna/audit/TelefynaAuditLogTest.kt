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

    @Test
    fun testPruneOldLogsKeepsRecentAndUnrelatedFiles() {
        val dir = java.nio.file.Files.createTempDirectory("audit").toFile()
        try {
            val today = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 29) }
            listOf("2026-10-29.log", "2026-09-29.log", "2026-08-30.log", "2026-08-29.log", "2025-01-01.log",
                "config.json", "notes.log", "init.txt").forEach { java.io.File(dir, it).writeText("x") }

            val deleted = Logger.pruneOldLogs(dir, 60, today).map { it.name }.sorted()

            assertEquals(listOf("2025-01-01.log", "2026-08-29.log"), deleted)
            assertEquals(
                listOf("2026-08-30.log", "2026-09-29.log", "2026-10-29.log", "config.json", "init.txt", "notes.log"),
                dir.list()!!.sorted()
            )
            assertTrue(Logger.pruneOldLogs(dir, 0, today).isEmpty()) // 0 = keep everything
        } finally {
            dir.deleteRecursively()
        }
    }
}
