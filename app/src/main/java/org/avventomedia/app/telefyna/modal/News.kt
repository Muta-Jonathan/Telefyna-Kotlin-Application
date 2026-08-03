package org.avventomedia.app.telefyna.modal

data class News(
    var messages: String? = null,
    // minutes to start ticker at during program play,  2#6#8 means start and 2nd, 6th and 8th second, This time includes bumpers
    var starts: String = "0",
    var showTime: Boolean = true
) {

    fun getStartMinute(): Double {
        return try {
            if (starts.isNotBlank()) starts.trim().toDouble() else 0.0
        } catch (e: NumberFormatException) {
            0.0
        }
    }

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
