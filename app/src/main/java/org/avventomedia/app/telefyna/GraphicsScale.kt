package org.avventomedia.app.telefyna

import kotlin.math.roundToInt

/**
 * On-air graphics are sized like broadcast graphics: as a share of the picture, designed on a 1920×1080 frame and
 * scaled to the screen. Density-independent units would make them a different share of the picture on every device
 * (small on a low-density TV box, large on a phone or emulator).
 */
object GraphicsScale {
    const val REFERENCE_WIDTH = 1920f
    const val REFERENCE_HEIGHT = 1080f

    // Sizes in pixels on a 1080p picture; margins keep graphics inside the 5% title-safe area
    // Logo area is wider than tall so wordmarks read well; the image keeps its shape inside it
    const val LOGO_WIDTH = 300
    const val LOGO_HEIGHT = 140
    const val LOGO_TOP = 48
    const val LOGO_END = 72
    const val LOGO_ABOVE_TICKER = 112
    const val WATERMARK_WIDTH = 220
    const val WATERMARK_HEIGHT = 110
    const val WATERMARK_START = 72
    const val WATERMARK_TOP = 48
    const val LOWER_THIRD_HEIGHT = 200
    const val TICKER_BOTTOM = 48
    const val TICKER_TEXT = 32
    const val TICKER_CLOCK_TEXT = 30
    const val TICKER_START = 72
    const val TICKER_TEXT_END = 150
    const val TICKER_CLOCK_OVERLAP = 300
    const val TICKER_CLOCK_PADDING_START = 18
    const val TICKER_CLOCK_PADDING_END = 15
    const val TICKER_STRIP_WIDTH = 6

    /** Scale of a [widthPx]×[heightPx] screen against 1080p; fits the 16:9 reference inside it. */
    fun of(widthPx: Int, heightPx: Int): Float {
        if (widthPx <= 0 || heightPx <= 0) return 1f
        return minOf(widthPx / REFERENCE_WIDTH, heightPx / REFERENCE_HEIGHT)
    }

    /** A 1080p pixel size at [scale], at least 1 px for anything non-zero. */
    fun px(reference: Int, scale: Float): Int {
        if (reference == 0) return 0
        val value = (reference * scale).roundToInt()
        return if (reference > 0) maxOf(1, value) else minOf(-1, value)
    }
}
