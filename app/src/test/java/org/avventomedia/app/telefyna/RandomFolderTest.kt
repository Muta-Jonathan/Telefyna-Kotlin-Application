package org.avventomedia.app.telefyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class RandomFolderTest {

    private fun withLibrary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("series").toFile()
        try {
            fun f(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("x") }
            f("Series A/02-episode.mp4"); f("Series A/01-episode.mp4"); f("Series A/notes.txt")
            f("Series B/Season 1/01-pilot.mp4"); f("Series B/Season 1/02-second.mp4"); f("Series B/Season 2/01-return.mp4")
            File(root, "Empty Series").mkdirs()
            f("Docs Only/readme.txt")
            f("loose-file.mp4")
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyFoldersWithMediaAreCandidates() = withLibrary { root ->
        assertEquals(listOf("Series A", "Series B"), Utils.programFolders(listOf(root)).map { it.name })
    }

    @Test
    fun folderPlaysInOrderIncludingSubFolders() = withLibrary { root ->
        assertEquals(listOf("01-episode.mp4", "02-episode.mp4"), Utils.playableFiles(File(root, "Series A")).map { it.name })
        assertEquals(listOf("01-pilot.mp4", "02-second.mp4", "01-return.mp4"), Utils.playableFiles(File(root, "Series B")).map { it.name })
    }

    @Test
    fun neverPicksTheSameFolderTwiceInARowWhenThereIsAChoice() = withLibrary { root ->
        val folders = Utils.programFolders(listOf(root))
        var last: String? = null
        repeat(50) { seed ->
            val picked = Utils.pickFolder(folders, last, Random(seed))!!
            assertTrue(picked.absolutePath != last)
            last = picked.absolutePath
        }
    }

    @Test
    fun singleFolderIsPickedEvenIfItWasLast() = withLibrary { root ->
        val only = listOf(File(root, "Series A"))
        assertEquals("Series A", Utils.pickFolder(only, File(root, "Series A").absolutePath)!!.name)
        assertNull(Utils.pickFolder(emptyList(), null))
    }

    @Test
    fun picksAreSpreadAcrossFolders() = withLibrary { root ->
        val folders = Utils.programFolders(listOf(root))
        val counts = (0 until 200).groupingBy { Utils.pickFolder(folders, null, Random(it))!!.name }.eachCount()
        assertTrue(counts.keys == setOf("Series A", "Series B") && counts.values.all { it > 50 })
    }

    @Test
    fun intelligentSortAirsLongProgrammesBeforeShortClipsKeepingTheShuffle() {
        val durations = mapOf("promo" to 30_000L, "sermon" to 3_600_000L, "jingle" to 10_000L, "film" to 5_400_000L, "clip" to 59_999L)
        val shuffled = listOf("promo", "sermon", "jingle", "film", "clip")
        assertEquals(listOf("sermon", "film", "promo", "jingle", "clip"), Utils.longFirst(shuffled) { durations.getValue(it) })
    }
}
