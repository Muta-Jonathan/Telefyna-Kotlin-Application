package org.avventomedia.app.telefyna.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, path: String, text: String, mtime: Long = 1_700_000_000_000L): File =
        File(root, path).apply { parentFile?.mkdirs(); writeText(text); setLastModified(mtime) }

    @Test
    fun copiesNewFilesAndKeepsTheFolderLayout() {
        val drive = tmp.newFolder("drive"); val box = tmp.newFolder("box")
        file(drive, "playlist/sermons/ep1.mp4", "one")
        file(drive, "config.json", "{}")
        val result = DriveImporter.sync(drive, box)
        assertEquals(2, result.copied)
        assertTrue(result.configChanged)
        assertEquals("one", File(box, "playlist/sermons/ep1.mp4").readText())
    }

    @Test
    fun skipsUnchangedAndCopiesChangedFiles() {
        val drive = tmp.newFolder("drive"); val box = tmp.newFolder("box")
        file(drive, "playlist/a.mp4", "same"); file(box, "playlist/a.mp4", "same")
        file(drive, "playlist/b.mp4", "longer now"); file(box, "playlist/b.mp4", "old")
        file(drive, "playlist/c.mp4", "edit", mtime = 1_700_000_100_000L); file(box, "playlist/c.mp4", "edxt")
        val result = DriveImporter.sync(drive, box)
        assertEquals(2, result.copied)
        assertEquals(1, result.unchanged)
        assertFalse(result.configChanged)
        assertEquals("longer now", File(box, "playlist/b.mp4").readText())
        assertEquals("edit", File(box, "playlist/c.mp4").readText())
    }

    @Test
    fun neverDeletesFilesThatAreOnlyOnTheBox() {
        val drive = tmp.newFolder("drive"); val box = tmp.newFolder("box")
        file(drive, "playlist/new.mp4", "n")
        file(box, "playlist/kept.mp4", "k")
        DriveImporter.sync(drive, box)
        assertTrue(File(box, "playlist/kept.mp4").exists())
    }

    @Test
    fun ignoresHiddenAndSystemFolders() {
        val drive = tmp.newFolder("drive"); val box = tmp.newFolder("box")
        file(drive, ".Trashes/x.mp4", "x")
        file(drive, "System Volume Information/IndexerVolumeGuid", "x")
        file(drive, "playlist/._ep1.mp4", "mac resource fork")
        file(drive, "telefynaAudit/2026-10-30.log", "box logs are not content")
        assertEquals(0, DriveImporter.sync(drive, box).copied)
    }

    @Test
    fun leavesNoPartialFiles() {
        val drive = tmp.newFolder("drive"); val box = tmp.newFolder("box")
        file(drive, "playlist/ep1.mp4", "one")
        DriveImporter.sync(drive, box)
        assertTrue(box.walkTopDown().none { it.name.endsWith(".part") })
        assertTrue(DriveImporter.pending(drive, box).isEmpty())
    }
}
