package org.avventomedia.app.telefyna.modal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TelefynaNewsTest {

    @Test
    fun testGetMessagesArray() {
        // Test normal multi-line string
        var news = News().apply { 
            messages = "Breaking News 1#Breaking News 2#Weather Update" 
        }
        var expected = arrayOf("Breaking News 1", "Breaking News 2", "Weather Update")
        assertArrayEquals(expected, news.getMessagesArray())

        // Test with trailing splitters
        news = News().apply { 
            messages = "Message 1#Message 2##" 
        }
        expected = arrayOf("Message 1", "Message 2")
        assertArrayEquals(expected, news.getMessagesArray())

        // Test with null messages
        news = News().apply { 
            messages = null 
        }
        expected = emptyArray()
        assertArrayEquals(expected, news.getMessagesArray())
        
        // Test with empty string
        news = News().apply { 
            messages = "" 
        }
        // split on empty string gives an array with one empty string, but dropLastWhile removes it
        expected = emptyArray()
        assertArrayEquals(expected, news.getMessagesArray())
    }
    
    @Test
    fun testStartMinuteDefault() {
        // Test that startMinute defaults to 0.0
        val news = News()
        assertEquals(0.0, news.startMinute, 0.001)
    }
}
