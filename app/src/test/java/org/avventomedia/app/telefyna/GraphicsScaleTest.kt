package org.avventomedia.app.telefyna

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphicsScaleTest {

    @Test
    fun fullHdTvIsTheReference() {
        assertEquals(1f, GraphicsScale.of(1920, 1080), 0.0001f)
        assertEquals(GraphicsScale.LOGO, GraphicsScale.px(GraphicsScale.LOGO, GraphicsScale.of(1920, 1080)))
    }

    @Test
    fun sameShareOfThePictureOn720pAnd4k() {
        val small = GraphicsScale.of(1280, 720)
        val big = GraphicsScale.of(3840, 2160)
        assertEquals(0.6667f, small, 0.001f)
        assertEquals(2f, big, 0.0001f)
        // Logo is the same fraction of the picture height everywhere
        assertEquals(GraphicsScale.px(GraphicsScale.LOGO, small) / 720f, GraphicsScale.px(GraphicsScale.LOGO, big) / 2160f, 0.002f)
    }

    @Test
    fun wideTallScreensUseTheLimitingSide() {
        // A phone in landscape (2340×1080) is limited by height, a portrait screen by width
        assertEquals(1f, GraphicsScale.of(2340, 1080), 0.0001f)
        assertEquals(1080 / 1920f, GraphicsScale.of(1080, 2340), 0.0001f)
    }

    @Test
    fun negativeMarginsScaleAndNothingVanishes() {
        assertEquals(-200, GraphicsScale.px(-GraphicsScale.TICKER_CLOCK_OVERLAP, 0.6667f))
        assertEquals(1, GraphicsScale.px(1, 0.1f))
        assertEquals(0, GraphicsScale.px(0, 2f))
        assertEquals(1f, GraphicsScale.of(0, 0), 0f)
    }
}
