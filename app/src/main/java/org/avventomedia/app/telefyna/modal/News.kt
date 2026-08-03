package org.avventomedia.app.telefyna.modal

data class News(
    var messages: String? = null,
    var startMinute: Double = 0.0,
    var showTime: Boolean = true
) {

    fun getMessagesArray(): Array<String> {
        val mess = mutableListOf<String>()

        if (messages?.isNotBlank() == true) {
            messages!!.split(Graphics.MESSAGE_SPLITTER).forEach { m ->
                if (m.isNotBlank()) {
                    mess.add(m.trim())
                }
            }
        }
        return mess.toTypedArray()
    }
}
