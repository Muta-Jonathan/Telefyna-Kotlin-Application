package org.avventomedia.app.telefyna.stream

import org.avventomedia.app.telefyna.modal.AudioStream
import java.net.URLEncoder

/** Source-client protocol for Icecast (HTTP PUT) and Shoutcast (v1 / v2 legacy source), plus ADTS framing. Pure. */
object IceProtocol {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    private const val USER_AGENT = "Telefyna"

    fun icecastRequest(s: AudioStream): String = buildString {
        append("PUT ${clean(s.mount).let { if (it.startsWith("/")) it else "/$it" }} HTTP/1.1\r\n")
        append("Host: ${clean(s.host)}:${s.port}\r\n")
        append("Authorization: Basic ${base64("${clean(s.user)}:${clean(s.password)}")}\r\n")
        append("User-Agent: $USER_AGENT\r\n")
        append("Content-Type: audio/aac\r\n")
        append("Ice-Public: ${if (s.publicListing) 1 else 0}\r\n")
        s.name?.let { append("Ice-Name: ${clean(it)}\r\n") }
        s.description?.let { append("Ice-Description: ${clean(it)}\r\n") }
        s.genre?.let { append("Ice-Genre: ${clean(it)}\r\n") }
        s.url?.let { append("Ice-Url: ${clean(it)}\r\n") }
        append("Ice-Audio-Info: ice-bitrate=${s.bitrateKbps};ice-samplerate=$SAMPLE_RATE;ice-channels=$CHANNELS\r\n")
        append("\r\n")
    }

    /** Null when the server accepted the source, otherwise why not. */
    fun icecastRefusal(statusLine: String?): String? {
        val code = statusLine?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: return "no reply: ${statusLine.orEmpty()}"
        return when (code) {
            200 -> null
            401 -> "wrong user or password"
            403 -> "mount in use or not allowed"
            else -> "server replied: $statusLine"
        }
    }

    /** Shoutcast sources connect to the listener port + 1. */
    fun shoutcastSourcePort(s: AudioStream): Int = s.port + 1

    fun shoutcastLogin(s: AudioStream): String = "${clean(s.password)}\r\n"

    fun shoutcastRefusal(reply: String?): String? =
        if (reply?.trim()?.startsWith("OK") == true) null else "server replied: ${reply.orEmpty().ifBlank { "nothing" }}"

    fun shoutcastHeaders(s: AudioStream): String = buildString {
        append("content-type:audio/aacp\r\n")
        append("icy-name:${clean(s.name)}\r\n")
        append("icy-genre:${clean(s.genre)}\r\n")
        append("icy-url:${clean(s.url)}\r\n")
        append("icy-pub:${if (s.publicListing) 1 else 0}\r\n")
        append("icy-br:${s.bitrateKbps}\r\n")
        append("\r\n")
    }

    /** URL that sets the stream title (now playing) and, for Icecast, the Authorization header to send with it. */
    fun metadataRequest(s: AudioStream, title: String): Pair<String, String?> = when (s.server) {
        AudioStream.Server.ICECAST ->
            "http://${clean(s.host)}:${s.port}/admin/metadata?mount=${enc(s.mount)}&mode=updinfo&song=${enc(title)}" to
                    "Basic ${base64("${clean(s.user)}:${clean(s.password)}")}"
        AudioStream.Server.SHOUTCAST ->
            "http://${clean(s.host)}:${s.port}/admin.cgi?pass=${enc(s.password.orEmpty())}&mode=updinfo&song=${enc(title)}" to null
    }

    /** 7-byte ADTS header for one AAC-LC frame of [payloadLength] bytes at 44.1 kHz stereo. */
    fun adtsHeader(payloadLength: Int): ByteArray {
        val frameLength = payloadLength + 7
        val profile = 1 // AAC LC (object type 2) - 1
        val frequencyIndex = 4 // 44100 Hz
        val channelConfig = CHANNELS
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (frequencyIndex shl 2) or (channelConfig shr 2)).toByte(),
            (((channelConfig and 3) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength and 0x7FF) shr 3).toByte(),
            (((frameLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    // Header values come from config: never let them break out of their line
    private fun clean(value: String?): String = value.orEmpty().replace("\r", "").replace("\n", "")

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // java.util.Base64 needs API 26 and android.util.Base64 isn't available to unit tests
    fun base64(text: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < bytes.size) alphabet[((b1 and 0xF) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}

/** Fixed-size buffer of interleaved stereo samples between the player (writer) and the encoder (reader). */
class PcmRing(capacityFrames: Int) {
    private val data = ShortArray(capacityFrames * IceProtocol.CHANNELS)
    private var readPos = 0
    private var size = 0

    @Synchronized fun write(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            if (size == data.size) { // full: drop the oldest so the stream stays live
                readPos = (readPos + 1) % data.size
                size--
            }
            data[(readPos + size) % data.size] = samples[i]
            size++
        }
    }

    /** Fills [dst] with [frames] stereo frames; false (and nothing taken) when not enough are buffered. */
    @Synchronized fun read(dst: ShortArray, frames: Int): Boolean {
        val count = frames * IceProtocol.CHANNELS
        if (size < count) return false
        for (i in 0 until count) {
            dst[i] = data[readPos]
            readPos = (readPos + 1) % data.size
        }
        size -= count
        return true
    }

    @Synchronized fun availableFrames(): Int = size / IceProtocol.CHANNELS

    /** Drops the oldest frames so at most [frames] remain (keeps latency bounded when the clocks drift). */
    @Synchronized fun trimTo(frames: Int) {
        val keep = frames * IceProtocol.CHANNELS
        if (size > keep) {
            readPos = (readPos + size - keep) % data.size
            size = keep
        }
    }
}
