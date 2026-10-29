package org.avventomedia.app.telefyna.modal

object ConfigValidator {
    // The first playlist is the default, the second the fillers/offline fallback
    private const val REQUIRED_PLAYLISTS = 2

    /** Why [config] can't be aired, or null when it's usable. */
    fun problem(config: Config?): String? {
        val playlists = config?.playlists ?: return "no playlists"
        if (playlists.size < REQUIRED_PLAYLISTS) {
            return "at least 2 playlists are required (first default and fillers)"
        }
        val badStart = (playlists.mapNotNull { it.start } + config.schedules.orEmpty().mapNotNull { it.start })
            .firstOrNull { it.isNotBlank() && Playlist(start = it).parseStart() == null }
        return badStart?.let { "start time '$it' is not HH:mm" }
    }
}
