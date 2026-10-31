package org.avventomedia.app.telefyna.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import org.avventomedia.app.telefyna.modal.AudioStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Streams the programme audio to an Icecast or Shoutcast server: AAC-LC (ADTS) at 44.1 kHz stereo, paced by the
 * wall clock. Silence is sent while nothing plays, so listeners stay connected through switches and the slate.
 * Reconnects with backoff (2 s doubling to 60 s) and keeps the now-playing title up to date.
 */
class AudioStreamer(private val context: Context, val settings: AudioStream) {
    companion object {
        private const val FRAME = 1024 // samples per channel in one AAC frame
        private const val FRAME_NS = FRAME * 1_000_000_000L / IceProtocol.SAMPLE_RATE
        private const val MAX_LATENCY_FRAMES = IceProtocol.SAMPLE_RATE * 2
        private const val TRIMMED_LATENCY_FRAMES = IceProtocol.SAMPLE_RATE / 2
        private const val MAX_BACKOFF_S = 60
        private const val TIMEOUT_MS = 10_000
    }

    private val ring = PcmRing(IceProtocol.SAMPLE_RATE * 3)
    private val metadataSender = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var title: String? = null
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    /** Called by the audio tap on the playback thread. */
    fun write(samples: ShortArray, count: Int) = ring.write(samples, count)

    fun start() {
        running = true
        thread = Thread(::run, "telefyna-audio-stream").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        metadataSender.shutdownNow()
        Logger.logWithContext(context, AuditLog.Event.STREAM_STOPPED, settings.label())
    }

    fun nowPlaying(newTitle: String) {
        if (newTitle == title) return
        title = newTitle
        if (connected) sendMetadata(newTitle)
    }

    private fun run() {
        var backoff = 2
        while (running) {
            try {
                session()
                backoff = 2
            } catch (e: Exception) {
                connected = false
                if (!running) break
                Logger.logWithContext(context, AuditLog.Event.STREAM_LOST, settings.label(), e.message ?: e.javaClass.simpleName, backoff)
                try { Thread.sleep(backoff * 1000L) } catch (_: InterruptedException) { break }
                backoff = minOf(backoff * 2, MAX_BACKOFF_S)
            }
        }
    }

    private fun session() {
        val port = if (settings.server == AudioStream.Server.SHOUTCAST) IceProtocol.shoutcastSourcePort(settings) else settings.port
        Socket().use { s ->
            socket = s
            s.connect(InetSocketAddress(settings.host, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            s.keepAlive = true
            s.tcpNoDelay = true
            val out = BufferedOutputStream(s.getOutputStream())
            val input = s.getInputStream()
            when (settings.server) {
                AudioStream.Server.ICECAST -> {
                    out.write(IceProtocol.icecastRequest(settings).toByteArray()); out.flush()
                    IceProtocol.icecastRefusal(readLine(input))?.let { throw IOException(it) }
                    while (readLine(input)?.isNotEmpty() == true) { /* rest of the reply headers */ }
                }
                AudioStream.Server.SHOUTCAST -> {
                    out.write(IceProtocol.shoutcastLogin(settings).toByteArray()); out.flush()
                    IceProtocol.shoutcastRefusal(readLine(input))?.let { throw IOException(it) }
                    out.write(IceProtocol.shoutcastHeaders(settings).toByteArray()); out.flush()
                }
            }
            connected = true
            Logger.logWithContext(context, AuditLog.Event.STREAM_CONNECTED, settings.label(), settings.bitrateKbps)
            title?.let { sendMetadata(it) }
            encode(out)
        }
    }

    private fun encode(out: OutputStream) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, IceProtocol.SAMPLE_RATE, IceProtocol.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * IceProtocol.CHANNELS * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val pcm = ShortArray(FRAME * IceProtocol.CHANNELS)
        val info = MediaCodec.BufferInfo()
        var frameBytes = ByteArray(2048) // one AAC frame at up to 320 kbps fits; grown if ever needed
        var presentationUs = 0L
        var next = System.nanoTime()
        try {
            while (running) {
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                else if (wait < -1_000_000_000L) next = System.nanoTime() // fell far behind (e.g. a stall): resync
                next += FRAME_NS

                if (ring.availableFrames() > MAX_LATENCY_FRAMES) ring.trimTo(TRIMMED_LATENCY_FRAMES)
                if (!ring.read(pcm, FRAME)) pcm.fill(0) // nothing playing (yet): send silence

                val inIndex = codec.dequeueInputBuffer(TIMEOUT_MS * 1000L)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    buffer.clear()
                    buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
                    codec.queueInputBuffer(inIndex, 0, pcm.size * 2, presentationUs, 0)
                    presentationUs += FRAME * 1_000_000L / IceProtocol.SAMPLE_RATE
                }
                var outIndex = codec.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        if (frameBytes.size < info.size) frameBytes = ByteArray(info.size)
                        codec.getOutputBuffer(outIndex)!!.apply { position(info.offset); get(frameBytes, 0, info.size) }
                        out.write(IceProtocol.adtsHeader(info.size))
                        out.write(frameBytes, 0, info.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
                out.flush()
            }
        } catch (_: InterruptedException) {
            // stopped
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun sendMetadata(text: String) {
        if (!settings.metadata) return
        runCatching {
            metadataSender.execute {
                val (url, authorization) = IceProtocol.metadataRequest(settings, text)
                runCatching {
                    (URL(url).openConnection() as HttpURLConnection).run {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Telefyna)") // Shoutcast's admin.cgi wants a browser agent
                        authorization?.let { setRequestProperty("Authorization", it) }
                        responseCode
                        disconnect()
                    }
                }
            }
        }
    }

    // Reply lines are ASCII; stops at CRLF or LF, null at end of stream
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }
}
