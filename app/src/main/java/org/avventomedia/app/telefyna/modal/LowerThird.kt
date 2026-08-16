package org.avventomedia.app.telefyna.modal

import org.avventomedia.app.telefyna.Utils

data class LowerThird(
    var file: String? = null,
    var starts: String = "0",
    var replays: Int = 0
) {
    fun getStartsArray(): Array<Double> {
        val startTimes = mutableListOf<Double>()

        if (!starts.isNullOrBlank()) {
            starts.replace(Utils.COMMA_SPLITTER, ",")
                .replace(Graphics.MESSAGE_SPLITTER, ",")
                .replace("#", ",")
                .split(",")
                .forEach { start ->
                    if (start.isNotBlank()) {
                        start.trim().toDoubleOrNull()?.let {
                            startTimes.add(it)
                        }
                    }
                }
            startTimes.sort()
        }
        return startTimes.toTypedArray()
    }
}
