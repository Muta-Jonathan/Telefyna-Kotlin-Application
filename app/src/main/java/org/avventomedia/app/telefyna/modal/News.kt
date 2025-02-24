package org.avventomedia.app.telefyna.modal

data class News(
    var messages: String? = null,
    // minutes to start ticker at during program play,  2#6#8 means start and 2nd, 6th and 8th second, This time includes bumpers
    var starts: String = "0",
    var showTime: Boolean = true,
    var speed: Speed = Speed.SLOW
) {

    fun getStartsArray(): Array<Double> {
        val startTimes = mutableListOf<Double>()

        if (starts.isNotBlank()) {
            starts.split(Graphics.MESSAGE_SPLITTER).forEach { start ->
                if (start.isNotBlank()) {
                    startTimes.add(start.trim().toDouble())
                }
            }
            startTimes.sort()
        }
        return startTimes.toTypedArray()
    }

    fun getMessagesArray(): Array<String> {
        val mess = mutableListOf<String>()

        if (messages?.isNotBlank() == true) {
            messages?.split(Graphics.MESSAGE_SPLITTER)?.forEach { m ->
                if (m.isNotBlank()) {
                    mess.add(m.trim())
                }
            }
        }
        return mess.toTypedArray()
    }

    enum class Speed {
        SLOW, FAST, VERY_FAST;

        fun getDisplacement(): Int {
            return when (this) {
                FAST -> 50
                VERY_FAST -> 100
                else -> 1
            }
        }
    }
}
