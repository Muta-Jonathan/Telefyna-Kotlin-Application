package org.avventomedia.app.telefyna.modal

data class LowerThird(
    var file: String? = null,
    var starts: String = "0",
    var replays: Int = 0
) {
    fun getStartsArray(): Array<Double> {
        val startTimes = mutableListOf<Double>()

        if (starts.isNotBlank()) {
            starts.split(Graphics.MESSAGE_SPLITTER).forEach { start ->
                if (start.isNotBlank()) {
                    startTimes.add(start.trim().toDouble())
                } else {
                    // If no splitter, treat the entire string as a single start time
                    startTimes.add(starts.trim().toDouble())
                }
            }
            startTimes.sort()
        }
        return startTimes.toTypedArray()
    }
}
