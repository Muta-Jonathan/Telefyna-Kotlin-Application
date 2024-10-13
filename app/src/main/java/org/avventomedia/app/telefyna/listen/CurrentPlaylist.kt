package org.avventomedia.app.telefyna.listen

import org.avventomedia.app.telefyna.modal.Playlist

data class CurrentPlaylist (
    val index: Int,
    val playlist: Playlist
)