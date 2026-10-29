/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.avventomedia.app.telefyna.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.extensions.connect
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import io.github.thibaultbee.srtdroid.core.models.SrtUrl
import org.avventomedia.app.telefyna.audit.AuditLog
import org.avventomedia.app.telefyna.audit.Logger
import java.io.IOException

@OptIn(UnstableApi::class)
class SrtDataSource : BaseDataSource(/*isNetwork*/ true) {

    companion object {
        private const val DEFAULT_PAYLOAD_SIZE = 1316
    }

    private var socket: SrtSocket? = null
    private var srtUrl: SrtUrl? = null
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val srtUrl = try {
            SrtUrl(uri)
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "Invalid SRT URI: $uri")
            throw IOException("Invalid SRT URI: $uri", e)
        }

        try {
            socket = SrtSocket().apply {
                Logger.log(AuditLog.Event.CONNECTING, "Connecting to SRT ${srtUrl.hostname}:${srtUrl.port}...")
                connect(srtUrl)
            }
            this.srtUrl = srtUrl
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            Logger.log(AuditLog.Event.ERROR, "Failed to connect to SRT stream: ${e.message}")
            close()
            throw IOException("SRT connection failed: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        val currentSocket = socket ?: throw IOException("SRT Socket is closed")

        if (pendingOffset >= pending.size) {
            // SRT live mode delivers whole messages: recv() must be asked for a full payload, never less,
            // or it fails with "Incorrect use of Message API". Smaller player reads are served from this buffer.
            pending = try {
                currentSocket.recv(DEFAULT_PAYLOAD_SIZE)
            } catch (e: Exception) {
                // A dropped live feed is an error, not a normal end: surface it so the player's recovery kicks in
                throw IOException("SRT receive failed: ${e.message}", e)
            }
            pendingOffset = 0
            if (pending.isEmpty()) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToCopy = (pending.size - pendingOffset).coerceAtMost(length)
        System.arraycopy(pending, pendingOffset, buffer, offset, bytesToCopy)
        pendingOffset += bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? {
        return srtUrl?.let { Uri.parse(it.srtUri.toString()) }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        } finally {
            socket = null
            srtUrl = null
            pending = ByteArray(0)
            pendingOffset = 0
            transferEnded()
        }
    }
}