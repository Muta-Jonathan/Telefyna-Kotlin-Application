package org.avventomedia.app.telefyna.modal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TelefynaLowerThirdTest {

    @Test
    fun testGetStartsArray() {
        // 1. Test comma-separated string
        var lowerThird = LowerThird(starts = "10, 20.5, 30")
        var starts = lowerThird.getStartsArray()
        assertEquals(3, starts.size)
        assertArrayEquals(arrayOf(10.0, 20.5, 30.0), starts)

        // 2. Test hash-separated string (since Graphics.MESSAGE_SPLITTER is #)
        lowerThird = LowerThird(starts = "5.5#15#25.0")
        starts = lowerThird.getStartsArray()
        assertEquals(3, starts.size)
        assertArrayEquals(arrayOf(5.5, 15.0, 25.0), starts)

        // 3. Test mixed or poorly formatted spaces
        lowerThird = LowerThird(starts = "  12.0  , 4.5# 8.0 ")
        starts = lowerThird.getStartsArray()
        assertEquals(3, starts.size)
        // Note: The method sorts the array! So it should be 4.5, 8.0, 12.0
        assertArrayEquals(arrayOf(4.5, 8.0, 12.0), starts)

        // 4. Test single value
        lowerThird = LowerThird(starts = "45")
        starts = lowerThird.getStartsArray()
        assertEquals(1, starts.size)
        assertArrayEquals(arrayOf(45.0), starts)

        // 5. Test empty or blank
        lowerThird = LowerThird(starts = "")
        starts = lowerThird.getStartsArray()
        assertEquals(0, starts.size)
        
        lowerThird = LowerThird(starts = "   ")
        starts = lowerThird.getStartsArray()
        assertEquals(0, starts.size)
    }
}
