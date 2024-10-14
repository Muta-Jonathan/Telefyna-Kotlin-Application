package org.avventomedia.app.telefyna.modal

/**
 * By default this is set to 3ABN TV (Telefyna)
 */
data class Config(
    /**
     * Update whenever a change to your configuration is made so you can track your configurations well
     */
    var version: String? = null,
    var name: String? = null,
    var automationDisabled: Boolean = false,
    var notificationsDisabled: Boolean = true,
    // seconds to keep checking on player, wait on internet
    var wait: Int = 30,
    var alerts: Alerts? = null,
    var playlists: Array<Playlist>? = null
) {
    val isAutomationDisabled: Boolean
            get() = automationDisabled

    val isNotificationsDisabled: Boolean
        get() = notificationsDisabled
}
