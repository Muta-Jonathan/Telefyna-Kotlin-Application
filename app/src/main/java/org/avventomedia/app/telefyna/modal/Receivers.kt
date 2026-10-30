package org.avventomedia.app.telefyna.modal

import org.avventomedia.app.telefyna.audit.AuditLog

data class Receivers(
    // emails separated by #
    var emails: String,
    var attachConfig: Boolean = false,

    // days whose audit logs should be added backwards starting today
    var attachAuditLog: Int = 0,

    // attach broadcast reports (PDF) when they are generated
    var attachReports: Boolean = false,

    var eventCategory: AuditLog.Event.Category
) {
    val isAttachConfig: Boolean
        get() = attachConfig
}
