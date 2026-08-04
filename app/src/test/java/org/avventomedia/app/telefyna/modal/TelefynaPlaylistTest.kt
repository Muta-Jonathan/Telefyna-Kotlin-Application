package org.avventomedia.app.telefyna.modal

import org.avventomedia.app.telefyna.modal.Playlist
import org.avventomedia.app.telefyna.modal.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelefynaPlaylistTest {

    @Test
    fun testIsResuming() {
        // Test non-resuming types
        var playlist = Playlist().apply { type = Playlist.Type.ONLINE }
        assertFalse("ONLINE should not be resuming", playlist.isResuming())

        playlist = Playlist().apply { type = Playlist.Type.LOCAL_SEQUENCED }
        assertFalse("LOCAL_SEQUENCED should not be resuming", playlist.isResuming())

        playlist = Playlist().apply { type = Playlist.Type.LOCAL_RANDOMIZED }
        assertFalse("LOCAL_RANDOMIZED should not be resuming", playlist.isResuming())

        // Test resuming types
        playlist = Playlist().apply { type = Playlist.Type.LOCAL_RESUMING }
        assertTrue("LOCAL_RESUMING should be resuming", playlist.isResuming())

        playlist = Playlist().apply { type = Playlist.Type.LOCAL_RESUMING_SAME }
        assertTrue("LOCAL_RESUMING_SAME should be resuming", playlist.isResuming())

        playlist = Playlist().apply { type = Playlist.Type.LOCAL_RESUMING_NEXT }
        assertTrue("LOCAL_RESUMING_NEXT should be resuming", playlist.isResuming())

        playlist = Playlist().apply { type = Playlist.Type.LOCAL_RESUMING_ONE }
        assertTrue("LOCAL_RESUMING_ONE should be resuming", playlist.isResuming())
    }

    @Test
    fun testScheduleInheritance() {
        val parentPlaylist = Playlist().apply { active = false }
        val schedule = Playlist().apply { active = true }
        
        // When parent playlist is inactive, the child schedule should become inactive after inheritance
        schedule.schedule(parentPlaylist)
        assertFalse("Schedule should be inactive when parent playlist is inactive", schedule.active)
    }
}
