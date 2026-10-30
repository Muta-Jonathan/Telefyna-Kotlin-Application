package org.avventomedia.app.telefyna

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeResolverTest {

    private val series = listOf("ep1.mp4", "ep2.mp4", "ep3.mp4", "ep4.mp4")

    @Test
    fun fileInsertedBeforeTheResumePointKeepsTheSameEpisode() {
        // ep2 was playing at position 1; a new intro was added at the front
        val now = listOf("0-intro.mp4") + series
        assertEquals(Pair(2, true), ResumeResolver.resolve(1, "ep2.mp4", now, willAdvance = false))
        assertEquals(Pair(2, true), ResumeResolver.resolve(1, "ep2.mp4", now, willAdvance = true))
    }

    @Test
    fun unchangedFolderResolvesToTheSamePosition() {
        assertEquals(Pair(1, true), ResumeResolver.resolve(1, "ep2.mp4", series, willAdvance = false))
    }

    @Test
    fun removedFileContinuesWithTheFileNowInItsPlace() {
        // ep2 (position 1) was deleted; ep3 now sits at position 1 and should play next, from the start
        val now = series - "ep2.mp4"
        assertEquals(Pair(1, false), ResumeResolver.resolve(1, "ep2.mp4", now, willAdvance = false))
        // advancing callers add one, so step back to land on ep3 rather than skip it
        assertEquals(Pair(0, false), ResumeResolver.resolve(1, "ep2.mp4", now, willAdvance = true))
    }

    @Test
    fun removedLastFileIsClampedToThePlaylist() {
        val now = series - "ep4.mp4"
        assertEquals(Pair(2, false), ResumeResolver.resolve(3, "ep4.mp4", now, willAdvance = false))
        assertEquals(Pair(2, false), ResumeResolver.resolve(3, "ep4.mp4", now, willAdvance = true))
    }

    @Test
    fun legacyResumePointsWithoutIdentityUseThePosition() {
        assertEquals(Pair(2, true), ResumeResolver.resolve(2, null, series, willAdvance = false))
    }

    @Test
    fun startOverAndEmptyPlaylistsAreLeftAlone() {
        assertEquals(Pair(-1, true), ResumeResolver.resolve(-1, "ep2.mp4", series, willAdvance = true))
        assertEquals(Pair(1, true), ResumeResolver.resolve(1, "ep2.mp4", emptyList(), willAdvance = false))
    }
}
