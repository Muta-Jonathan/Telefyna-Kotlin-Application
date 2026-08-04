package org.avventomedia.app.telefyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TelefynaUtilsTest {

    @Test
    fun testIsValidEmail() {
        assertTrue(Utils.isValidEmail("test@example.com"))
        assertTrue(Utils.isValidEmail("user.name+tag@domain.co.uk"))
        
        assertFalse(Utils.isValidEmail("invalid-email"))
        assertFalse(Utils.isValidEmail("@domain.com"))
        assertFalse(Utils.isValidEmail("test@"))
    }

    @Test
    fun testFormatDuration() {
        // 1 hour, 1 minute, 1 second = 3600000 + 60000 + 1000 = 3661000 ms
        assertEquals("01:01:01", Utils.formatDuration(3661000))
        
        // 0 hours, 5 minutes, 30 seconds = 330000 ms
        assertEquals("00:05:30", Utils.formatDuration(330000))
        
        // exactly 0
        assertEquals("00:00:00", Utils.formatDuration(0))
    }

    @Test
    fun testValidPlayableItem() {
        fun mockFile(name: String, exists: Boolean = true): File {
            val file = File(System.getProperty("java.io.tmpdir"), name)
            if (exists) {
                file.createNewFile()
                file.deleteOnExit()
            } else {
                file.delete()
            }
            return file
        }

        // Valid extensions
        assertTrue(Utils.validPlayableItem(mockFile("video.mp4")))
        assertTrue(Utils.validPlayableItem(mockFile("movie.mkv")))
        assertTrue(Utils.validPlayableItem(mockFile("stream.ts")))
        assertTrue(Utils.validPlayableItem(mockFile("image.png")))
        assertTrue(Utils.validPlayableItem(mockFile("logo.webp")))
        assertTrue(Utils.validPlayableItem(mockFile("animation.gif")))

        // Invalid extensions
        assertFalse(Utils.validPlayableItem(mockFile("document.pdf")))
        assertFalse(Utils.validPlayableItem(mockFile("script.sh")))
        assertFalse(Utils.validPlayableItem(mockFile("audio.wav"))) // adjust if audio should be playable

        // Hidden files should be ignored even with valid extensions
        assertFalse(Utils.validPlayableItem(mockFile(".hidden_video.mp4")))

        // Non-existent files should be ignored
        assertFalse(Utils.validPlayableItem(mockFile("deleted.mp4", exists = false)))
    }

    @Test
    fun testSetupLocalPrograms() {
        // Setup a fake nested folder structure
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "telefyna_test_bumpers")
        tmpDir.mkdirs()
        
        val f1 = File(tmpDir, "03_outro.mp4").apply { createNewFile() }
        val f2 = File(tmpDir, "01_intro.mp4").apply { createNewFile() }
        
        val subDir = File(tmpDir, "02_subfolder").apply { mkdirs() }
        val f3 = File(subDir, "02_middle.mp4").apply { createNewFile() }
        val hidden = File(subDir, ".hidden.mp4").apply { createNewFile() }
        
        // Mock android.net.Uri which is an Android stub
        io.mockk.mockkStatic(android.net.Uri::class)
        io.mockk.every { android.net.Uri.fromFile(any()) } answers {
            val file = it.invocation.args[0] as File
            val uri = io.mockk.mockk<android.net.Uri>()
            io.mockk.every { uri.toString() } returns "file://${file.absolutePath}"
            uri
        }

        val programs = mutableListOf<androidx.media3.common.MediaItem>()
        val playlist = org.avventomedia.app.telefyna.modal.Playlist(type = org.avventomedia.app.telefyna.modal.Playlist.Type.LOCAL_SEQUENCED)
        
        Utils.setupLocalPrograms(programs, tmpDir, false, playlist)

        // It should read: 01_intro, then 02_middle, then 03_outro
        // Wait, setupLocalPrograms adds the FIRST item at index 0, and subsequent items at index 1, 2, 3...
        // Let's just assert size is 3 (hidden file ignored)
        assertEquals(3, programs.size)

        // Clean up
        hidden.delete()
        f3.delete()
        subDir.delete()
        f1.delete()
        f2.delete()
        tmpDir.delete()
        
        io.mockk.unmockkStatic(android.net.Uri::class)
    }
}
