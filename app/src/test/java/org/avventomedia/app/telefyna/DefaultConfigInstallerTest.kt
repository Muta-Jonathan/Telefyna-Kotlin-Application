package org.avventomedia.app.telefyna

import com.google.gson.Gson
import org.avventomedia.app.telefyna.modal.Config
import org.avventomedia.app.telefyna.modal.ConfigValidator
import org.avventomedia.app.telefyna.modal.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DefaultConfigInstallerTest {

    // Unit tests run from the module directory
    private val bundled = File("src/main/assets/${DefaultConfigInstaller.ASSET}")

    private fun withTempRoot(block: (File) -> Unit) {
        val root = File(Files.createTempDirectory("telefyna").toFile(), "telefyna")
        try {
            block(root)
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun installsConfigAndFoldersOnFirstRun() = withTempRoot { root ->
        assertTrue(DefaultConfigInstaller.installIfMissing(root) { bundled.inputStream() })
        assertEquals(bundled.readText(), File(root, "config.json").readText())
        DefaultConfigInstaller.FOLDERS.forEach { assertTrue("$it created", File(root, it).isDirectory) }
        assertFalse(File(root, "config.json.tmp").exists())
    }

    @Test
    fun neverOverwritesAnExistingConfig() = withTempRoot { root ->
        root.mkdirs()
        val existing = File(root, "config.json").apply { writeText("{\"name\":\"My Station\"}") }
        assertFalse(DefaultConfigInstaller.installIfMissing(root) { bundled.inputStream() })
        assertEquals("{\"name\":\"My Station\"}", existing.readText())
    }

    @Test
    fun bundledDefaultIsAValidGenericConfig() {
        val config = Gson().fromJson(bundled.readText(), Config::class.java)
        assertNull(ConfigValidator.problem(config))
        val playlists = config.playlists!!
        assertEquals(Playlist.Type.LOCAL_SEQUENCED, playlists[0].type)
        assertEquals(Playlist.Type.LOCAL_RANDOMIZED, playlists[1].type)
        // Its playlists point at folders the installer creates
        playlists.forEach { assertTrue(DefaultConfigInstaller.FOLDERS.contains("playlist/${it.urlOrFolder}")) }
        // Generic: no station-specific branding
        assertEquals("Telefyna", config.name)
    }

    @Test
    fun validatorRejectsUnusableConfigs() {
        assertEquals("no playlists", ConfigValidator.problem(null))
        assertTrue(ConfigValidator.problem(Config(playlists = arrayOf(Playlist())))!!.startsWith("at least 2"))
        val badTime = Config(playlists = arrayOf(Playlist(), Playlist(start = "8:30 AM")))
        assertEquals("start time '8:30 AM' is not HH:mm", ConfigValidator.problem(badTime))
    }
}
