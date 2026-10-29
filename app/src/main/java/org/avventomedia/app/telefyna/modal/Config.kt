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
    // days of daily audit logs to keep on the device; 0 keeps everything
    var auditRetentionDays: Int = 60,
    // Level loudness between programmes, bumpers and streams (automatic gain + limiter)
    var loudnessLeveling: Boolean = false,
    // Target programme level in dBFS RMS (about -23 LUFS for typical speech/music at -20)
    var loudnessTargetDbfs: Double = -20.0,
    var alerts: Alerts? = null,
    var playlists: Array<Playlist>? = null,
    var schedules: Array<Schedule>? = null
) {
    val isAutomationDisabled: Boolean
            get() = automationDisabled

    val isNotificationsDisabled: Boolean
        get() = notificationsDisabled
}
