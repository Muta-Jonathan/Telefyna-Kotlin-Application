package org.avventomedia.app.telefyna.modal

data class Mailer(
    var email: String? = null,
    var pass: String? = null,
    var host: String = "smtp.gmail.com",
    var port: Int = 587
)
