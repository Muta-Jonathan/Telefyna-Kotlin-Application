package org.avventomedia.app.telefyna.modal

data class Graphics(
    var displayLogo: Boolean = false,
    var logoPosition: LogoPosition = LogoPosition.TOP,
    var news: News? = null,
    var lowerThirds: Array<LowerThird>? = null
) {
    companion object {
        const val MESSAGE_SPLITTER: String = "#"
    }

    enum class LogoPosition {
        TOP,
        BOTTOM
    }
}
