package org.avventomedia.app.telefyna.modal

data class Graphics(
    var displayLogo: Boolean = false,
    var logoPosition: LogoPosition = LogoPosition.TOP,
    var displayLiveLogo: Boolean = false,
    var displayRepeatWatermark: Boolean = false,
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
