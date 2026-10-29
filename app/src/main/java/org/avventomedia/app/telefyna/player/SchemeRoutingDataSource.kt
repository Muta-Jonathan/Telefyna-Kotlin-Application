package org.avventomedia.app.telefyna.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Picks the transport per URI when the player opens it: srt:// goes to [SrtDataSource], everything else
 * (file, http(s), rtmp via the media3 extension, ...) to [DefaultDataSource].
 * Lets plain MediaItems play SRT through the player's default media source factory.
 */
@UnstableApi
class SchemeRoutingDataSource(private val defaultFactory: DataSource.Factory) : DataSource {

    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val dataSource = if (SCHEME_SRT.equals(dataSpec.uri.scheme, ignoreCase = true)) {
            SrtDataSource()
        } else {
            defaultFactory.createDataSource()
        }
        transferListeners.forEach { dataSource.addTransferListener(it) }
        delegate = dataSource
        return dataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return checkNotNull(delegate) { "read() before open()" }.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(
            context,
            // Stream URLs often redirect http -> https (or back)
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        )

        override fun createDataSource(): DataSource = SchemeRoutingDataSource(defaultFactory)
    }

    companion object {
        private const val SCHEME_SRT = "srt"
    }
}
