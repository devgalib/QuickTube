package com.edureminder.quicktube.feature.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Last-resort video thumbnail for files MediaStore can't preview (MPEG-PS .mpg/.vob and other containers the platform
 * doesn't parse): Media3's extractors read the first key frame and the phone's decoder turns it into a small bitmap.
 * Returns null when the phone has no decoder for the video (then the grid shows a placeholder).
 */
@UnstableApi
internal object FrameGrabber {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_WAIT_MS = 2_500L

    fun grab(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val extractor = MediaExtractorCompat(context)
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, emptyMap())
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = openDecoder(format) ?: return null
            decodeFirstFrame(extractor, codec, sizePx)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** The first decoder for this video that actually starts: by type alone can pick one that fails to initialise. */
    private fun openDecoder(format: MediaFormat): MediaCodec? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val names = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { it.name }
        for (name in names) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return codec
            } catch (_: Exception) {
                codec.release()
            }
        }
        return null
    }

    private fun decodeFirstFrame(extractor: MediaExtractorCompat, codec: MediaCodec, sizePx: Int): Bitmap? {
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var inputDone = false
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex >= 0) {
                val image = codec.getOutputImage(outIndex)
                val bitmap = image?.let { img -> img.use { yuvToBitmap(it, sizePx) } }
                codec.releaseOutputBuffer(outIndex, false)
                if (bitmap != null || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return bitmap
            }
        }
        return null
    }

    /** YUV_420_888 to a small ARGB bitmap, sampling only the pixels the thumbnail needs (no full-size copy). */
    private fun yuvToBitmap(image: android.media.Image, sizePx: Int): Bitmap? {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null
        val step = max(1, minOf(w, h) / sizePx)
        val outW = w / step
        val outH = h / step
        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer; val u = uPlane.buffer; val v = vPlane.buffer
        val pixels = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val sy = crop.top + oy * step
            for (ox in 0 until outW) {
                val sx = crop.left + ox * step
                val yy = (y.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 0xFF) - 16
                val ci = (sy / 2) * uPlane.rowStride + (sx / 2) * uPlane.pixelStride
                val cu = (u.get(ci).toInt() and 0xFF) - 128
                val cv = (v.get((sy / 2) * vPlane.rowStride + (sx / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128
                val c = 1192 * yy.coerceAtLeast(0)
                val r = ((c + 1634 * cv) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * cv - 400 * cu) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * cu) shr 10).coerceIn(0, 255)
                pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
