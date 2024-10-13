package org.avventomedia.app.telefyna.listen.modal

import javax.mail.internet.InternetAddress

data class Draft(
    var from: String = "",
    var pass: String = "",
    var bcc: MutableList<InternetAddress> = mutableListOf(),
    var subject: String = "",
    var body: String = "",
    var allowsAttachments: Boolean = false
)
