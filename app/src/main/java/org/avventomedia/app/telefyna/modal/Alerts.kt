package org.avventomedia.app.telefyna.modal

data class Alerts(
    var mailer: Mailer? = null,
    var subscribers: Array<Receivers>? = null,
    var enabled: Boolean = true
) {
    val isEnabled: Boolean
        get() = enabled
}
