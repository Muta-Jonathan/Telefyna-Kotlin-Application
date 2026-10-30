package org.avventomedia.app.telefyna

/**
 * Where a resuming playlist should pick up, by file identity rather than by position, so adding or removing
 * other files in the folder doesn't reset the series or shift it to the wrong episode.
 */
object ResumeResolver {

    /**
     * @param storedIndex position saved last time (-1 = start over)
     * @param storedId mediaId of the file playing at that position, null for resume points saved before
     *   identities were stored (then the position is used as-is)
     * @param currentIds mediaIds of the playlist as it is now
     * @param willAdvance the caller will move on to the item after the returned one (RESUMING_NEXT/ONE on a new day)
     * @return the position to resume from, and whether it is still the same file (so the saved seek still applies)
     */
    fun resolve(storedIndex: Int, storedId: String?, currentIds: List<String>, willAdvance: Boolean): Pair<Int, Boolean> {
        if (storedId == null || storedIndex < 0 || currentIds.isEmpty()) return Pair(storedIndex, true)
        val found = currentIds.indexOf(storedId)
        if (found >= 0) return Pair(found, true)
        // The remembered file was removed: carry on with the file now in its place. When the caller advances
        // by one, step back one so it lands on that file instead of skipping it.
        val fallback = if (willAdvance) storedIndex - 1 else storedIndex
        val lowest = if (willAdvance) -1 else 0
        return Pair(fallback.coerceIn(lowest, currentIds.size - 1), false)
    }
}
