package org.avventomedia.app.telefyna.stream

import org.avventomedia.app.telefyna.modal.AudioStream
import org.avventomedia.app.telefyna.player.PcmConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceProtocolTest {

    private val icecast = AudioStream(enabled = true, host = "radio.example.org", port = 8000, mount = "/live",
        password = "hackme", name = "Telefyna TV", genre = "Gospel")

    @Test
    fun icecastRequestIsAnAuthenticatedPutToTheMount() {
        val request = IceProtocol.icecastRequest(icecast)
        assertTrue(request.startsWith("PUT /live HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: radio.example.org:8000\r\n"))
        assertTrue(request.contains("Authorization: Basic c291cmNlOmhhY2ttZQ==\r\n")) // source:hackme
        assertTrue(request.contains("Content-Type: audio/aac\r\n"))
        assertTrue(request.contains("Ice-Name: Telefyna TV\r\n"))
        assertTrue(request.contains("Ice-Public: 0\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun mountWithoutSlashIsFixed() {
        assertTrue(IceProtocol.icecastRequest(icecast.copy(mount = "live")).startsWith("PUT /live "))
    }

    @Test
    fun configValuesCannotInjectHeaders() {
        val request = IceProtocol.icecastRequest(icecast.copy(name = "Evil\r\nX-Injected: 1"))
        assertFalse(request.contains("\r\nX-Injected"))
    }

    @Test
    fun icecastReplies() {
        assertNull(IceProtocol.icecastRefusal("HTTP/1.0 200 OK"))
        assertEquals("wrong user or password", IceProtocol.icecastRefusal("HTTP/1.1 401 Unauthorized"))
        assertEquals("mount in use or not allowed", IceProtocol.icecastRefusal("HTTP/1.1 403 Forbidden"))
        assertTrue(IceProtocol.icecastRefusal(null)!!.startsWith("no reply"))
    }

    @Test
    fun shoutcastLoginAndHeaders() {
        val shoutcast = icecast.copy(server = AudioStream.Server.SHOUTCAST)
        assertEquals(8001, IceProtocol.shoutcastSourcePort(shoutcast))
        assertEquals("hackme\r\n", IceProtocol.shoutcastLogin(shoutcast))
        assertNull(IceProtocol.shoutcastRefusal("OK2"))
        assertEquals("server replied: invalid password", IceProtocol.shoutcastRefusal("invalid password"))
        val headers = IceProtocol.shoutcastHeaders(shoutcast)
        assertTrue(headers.startsWith("content-type:audio/aacp\r\n"))
        assertTrue(headers.contains("icy-br:128\r\n"))
        assertTrue(headers.endsWith("\r\n\r\n"))
    }

    @Test
    fun metadataUrls() {
        val (url, auth) = IceProtocol.metadataRequest(icecast, "Sunday Sermon - Grace & Faith")
        assertEquals("http://radio.example.org:8000/admin/metadata?mount=%2Flive&mode=updinfo&song=Sunday%20Sermon%20-%20Grace%20%26%20Faith", url)
        assertEquals("Basic c291cmNlOmhhY2ttZQ==", auth)
        val (scUrl, scAuth) = IceProtocol.metadataRequest(icecast.copy(server = AudioStream.Server.SHOUTCAST), "News")
        assertEquals("http://radio.example.org:8000/admin.cgi?pass=hackme&mode=updinfo&song=News", scUrl)
        assertNull(scAuth)
    }

    @Test
    fun adtsHeaderDescribesAacLcStereo44k() {
        val header = IceProtocol.adtsHeader(100)
        // syncword, MPEG-4 no CRC, LC / 44.1 kHz / 2 ch, frame length 107
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()), header)
    }

    @Test
    fun base64() {
        assertEquals("", IceProtocol.base64(""))
        assertEquals("YQ==", IceProtocol.base64("a"))
        assertEquals("YWI=", IceProtocol.base64("ab"))
        assertEquals("YWJj", IceProtocol.base64("abc"))
    }

    @Test
    fun ringDropsOldestWhenFullAndReadsWholeFrames() {
        val ring = PcmRing(4) // 4 stereo frames
        ring.write(shortArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5), 10)
        assertEquals(4, ring.availableFrames())
        val out = ShortArray(4)
        assertTrue(ring.read(out, 2))
        assertArrayEquals(shortArrayOf(2, 2, 3, 3), out)
        assertFalse(ring.read(ShortArray(6), 3))
        ring.trimTo(1)
        assertTrue(ring.read(out, 1))
        assertEquals(5.toShort(), out[0])
    }

    @Test
    fun converterKeepsRateAndDuplicatesMono() {
        val converter = PcmConverter()
        val n = converter.convert(shortArrayOf(10, 20, 30), 3, 1, 44_100)
        assertArrayEquals(shortArrayOf(10, 10, 20, 20, 30, 30), converter.output.copyOf(n))
    }

    @Test
    fun converterResamples48kTo44kWithoutLosingTime() {
        val converter = PcmConverter()
        var produced = 0
        repeat(100) { // 100 buffers of 480 frames = 1 s at 48 kHz
            val stereo = ShortArray(480 * 2) { 1000 }
            produced += converter.convert(stereo, 480, 2, 48_000) / 2
        }
        assertTrue("got $produced frames", produced in 44_099..44_101)
    }

    @Test
    fun converterTakesFrontPairOfSurround() {
        val converter = PcmConverter()
        val n = converter.convert(shortArrayOf(1, 2, 3, 4, 5, 6), 1, 6, 44_100)
        assertArrayEquals(shortArrayOf(1, 2), converter.output.copyOf(n))
    }
}
