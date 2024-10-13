package org.avventomedia.app.telefyna.audit

import org.avventomedia.app.telefyna.modal.Alerts

data class AuditAlert (
    var alerts: Alerts,
    var event: AuditLog.Event,
    var message: String
)