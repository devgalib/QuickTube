package com.edureminder.quicktube.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * YouTube's CDN throttles open-ended range requests to roughly real-time speed and then stalls.
 * This source turns one logical read into consecutive bounded range requests, which stream at full speed.
 * Other hosts pass straight through.
 */
@UnstableApi
class ChunkedHttpDataSource(
    private val upstream: HttpDataSource,
    private val chunkSize: Long = 4L * 1024 * 1024,
) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunking = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunking = dataSpec.uri.host?.endsWith("googlevideo.com") == true
        if (!chunking) return upstream.open(dataSpec)
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        return if (end != C.LENGTH_UNSET.toLong()) end - position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val s = spec!!
        val len = if (end == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, end - position)
        upstream.open(s.buildUpon().setPosition(position).setLength(len).build())
        if (end == C.LENGTH_UNSET.toLong()) {
            upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()?.substringAfter('/')?.toLongOrNull()?.let { end = it }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunking) return upstream.read(buffer, offset, length)
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            upstream.close()
            if (end == C.LENGTH_UNSET.toLong() || position >= end) return C.RESULT_END_OF_INPUT
            openChunk()
            n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        position += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()

    class Factory(private val http: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedHttpDataSource(http.createDataSource())
    }
}
