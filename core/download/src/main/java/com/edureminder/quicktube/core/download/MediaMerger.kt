package com.edureminder.quicktube.core.download

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The tracks can't be stored in any container this Android version can write (e.g. AV1 before Android 14).
 * [definite] is false when it's the platform muxer refusing a track rather than a known codec limit.
 */
class UnsupportedCodecException(message: String, val definite: Boolean = true) : IOException(message)

/** Container-level tools built on the platform muxer (no re-encoding). */
object MediaMerger {

    /**
     * Merges a video-only stream with an audio stream. The container follows the codecs: MP4 for
     * H.264/H.265/AV1 + AAC, WebM for VP8/VP9 + Opus/Vorbis. Returns the written file, whose extension may
     * differ from [output] when WebM was needed.
     */
    fun merge(video: File, audio: File, output: File): File = mux(output, listOf(video to "video/", audio to "audio/"))

    /** Copies the audio track of a media file into .m4a (AAC) or .webm (Opus/Vorbis). Returns the written file. */
    fun extractAudio(input: File, output: File): File = mux(output, listOf(input to "audio/"))

    /** Re-packages every audio/video track (e.g. an HLS .ts stream) into a normal MP4 or WebM. */
    fun remux(input: File, output: File): File {
        val inputs = listOf("video/", "audio/").filter { prefix ->
            runCatching { Media3TrackReader.open(input, prefix).close() }.isSuccess ||
                runCatching { PlatformTrackReader(input, prefix).close() }.isSuccess
        }.map { input to it }
        if (inputs.isEmpty()) throw IOException("No playable tracks")
        return mux(output, inputs)
    }

    /** MP4 or WebM for this set of track mimes, or null if neither can hold all of them. */
    internal fun containerFor(mimes: List<String>, sdk: Int = Build.VERSION.SDK_INT): Int? {
        val mp4 = setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263, MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        ) + (if (sdk >= 34) setOf("video/av01") else emptySet())
        val webm = setOf(
            MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS,
        )
        return when {
            mimes.all { it in mp4 } -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            mimes.all { it in webm } -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> null
        }
    }

    /**
     * Fastest path first: Media3's extractors and MP4 muxer, all in this process. Then Media3 extractors with
     * the platform muxer (needed for WebM), then the platform extractor and muxer as the last resort.
     */
    private fun mux(requested: File, inputs: List<Pair<File, String>>): File {
        val attempts = listOf<Pair<String, () -> File>>(
            "media3" to { muxMedia3(requested, inputs) ?: muxWith(requested, inputs) { f, p -> Media3TrackReader.open(f, p) } },
            "platform" to { muxWith(requested, inputs) { f, p -> PlatformTrackReader(f, p) } },
        )
        var last: Exception? = null
        for ((name, attempt) in attempts) {
            try {
                return attempt()
            } catch (e: Exception) {
                if (e is UnsupportedCodecException && e.definite) throw e
                Log.w(TAG, "Merge via $name failed: $e")
                last = e
            }
        }
        throw last!!
    }

    /** Media3 extractors + Media3 Mp4Muxer. Null when the output must be WebM or a codec isn't supported there. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun muxMedia3(requested: File, inputs: List<Pair<File, String>>): File? {
        val readers = mutableListOf<Media3TrackReader>()
        var out: File? = null
        try {
            for ((file, prefix) in inputs) readers += Media3TrackReader.open(file, prefix)
            val mimes = readers.map { it.media3Format.sampleMimeType.orEmpty() }
            // Media3's muxer writes AV1/VP9/H.265/H.264 with AAC/Opus into MP4 on any Android version. A WebM
            // request (VP9 + Opus) stays WebM via the platform muxer.
            if (requested.extension == "webm") return null
            if (!mimes.all { it in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES || it in Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES }) return null
            val target = outputFor(requested, if (mimes.all { it.startsWith("audio/") }) "m4a" else "mp4")
            out = target
            FileOutputStream(target).use { stream ->
                val muxer = Mp4Muxer.Builder(stream).build()
                try {
                    val tracks = readers.map { muxer.addTrack(it.media3Format) }
                    readers.firstOrNull { it.media3Format.sampleMimeType!!.startsWith("video/") }
                        ?.rotation?.takeIf { it != 0 }?.let { muxer.addMetadataEntry(Mp4OrientationData(it)) }
                    while (true) {
                        val i = nextReader(readers) ?: break
                        val (time, flags, data) = readers[i].readSample()
                        val keyFrame = if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(tracks[i], ByteBuffer.wrap(data), androidx.media3.muxer.BufferInfo(time, data.size, keyFrame))
                    }
                } finally {
                    muxer.close()
                }
            }
            return target
        } catch (e: Throwable) {
            out?.delete()
            throw e
        } finally {
            readers.forEach { runCatching { it.close() } }
        }
    }

    /** Index of the reader whose next sample comes first, or null when all are finished. */
    private fun nextReader(readers: List<TrackReader>): Int? {
        var best: Int? = null
        var bestTime = Long.MAX_VALUE
        for (i in readers.indices) {
            val t = readers[i].nextTime() ?: continue
            if (best == null || t < bestTime) { best = i; bestTime = t }
        }
        return best
    }

    /** [requested], or a free sibling with extension [ext] (never overwriting a file the user already has). */
    private fun outputFor(requested: File, ext: String): File {
        if (requested.extension == ext) return requested.also { it.delete() }
        val base = requested.nameWithoutExtension
        var f = File(requested.parentFile, "$base.$ext")
        var n = 1
        while (f.exists()) f = File(requested.parentFile, "$base (${n++}).$ext")
        return f
    }

    private const val TAG = "MediaMerger"

    private fun muxWith(requested: File, inputs: List<Pair<File, String>>, open: (File, String) -> TrackReader): File {
        val readers = mutableListOf<TrackReader>()
        var output: File? = null
        var muxer: MediaMuxer? = null
        try {
            for ((file, prefix) in inputs) readers += open(file, prefix)
            val mimes = readers.map { it.format.getString(MediaFormat.KEY_MIME).orEmpty() }
            val container = containerFor(mimes)
                ?: throw UnsupportedCodecException("This format (${mimes.joinToString(" + ") { it.substringAfter('/') }}) can't be saved on this Android version")
            val ext = when {
                container == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM -> "webm"
                mimes.all { it.startsWith("audio/") } -> "m4a"
                else -> "mp4"
            }
            val out = outputFor(requested, ext)
            output = out
            val m = MediaMuxer(out.absolutePath, container).also { muxer = it }
            val tracks = readers.map { r -> m.addTrack(r.format) }
            readers.firstOrNull { it.format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                ?.rotation?.takeIf { it != 0 }?.let { m.setOrientationHint(it) }
            m.start()
            var buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val sized = { n: Int -> if (n > buffer.capacity()) ByteBuffer.allocateDirect(n + n / 4).also { buffer = it } else buffer }
            val info = MediaCodec.BufferInfo()
            // Interleave by timestamp so the muxer writes sequentially instead of buffering a whole track.
            while (true) {
                val best = nextReader(readers) ?: break
                val data = readers[best].read(sized, info)
                if (info.size < 0) continue
                m.writeSampleData(tracks[best], data, info)
            }
            m.stop()
            return out
        } catch (e: Throwable) {
            output?.delete()
            if (e is IllegalStateException || e is IllegalArgumentException) {
                // The platform muxer's own errors ("Failed to add the track to the muxer", "Failed to stop the muxer").
                throw UnsupportedCodecException("This format can't be saved on this Android version", definite = false)
            }
            throw e
        } finally {
            runCatching { muxer?.release() }
            readers.forEach { runCatching { it.close() } }
        }
    }
}

