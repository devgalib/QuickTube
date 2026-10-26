package com.edureminder.quicktube.core.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Container-level tools built on the platform muxer (no re-encoding). */
object MediaMerger {

    /** Merges a video-only MP4 with an M4A audio track into [output]. */
    fun merge(video: File, audio: File, output: File) {
        mux(output, listOf(video to "video/", audio to "audio/"))
    }

    /** Copies the audio track of a media file into an .m4a container. */
    fun extractAudio(input: File, output: File) {
        mux(output, listOf(input to "audio/"))
    }

    /** Re-packages every audio/video track (e.g. an HLS .ts stream) into a normal MP4. */
    fun remux(input: File, output: File) {
        val ex = MediaExtractor().apply { setDataSource(input.absolutePath) }
        val mimes = (0 until ex.trackCount).mapNotNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        ex.release()
        val inputs = listOfNotNull(
            mimes.firstOrNull { it.startsWith("video/") }?.let { input to "video/" },
            mimes.firstOrNull { it.startsWith("audio/") }?.let { input to "audio/" },
        )
        require(inputs.isNotEmpty()) { "No playable tracks" }
        mux(output, inputs)
    }

    private fun mux(output: File, inputs: List<Pair<File, String>>) {
        output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<Triple<MediaExtractor, Int, Int>>()
        try {
            for ((file, prefix) in inputs) {
                val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: run { ex.release(); error("No $prefix track in ${file.name}") }
                ex.selectTrack(track)
                val outTrack = muxer.addTrack(ex.getTrackFormat(track))
                extractors += Triple(ex, track, outTrack)
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((ex, _, outTrack) in extractors) {
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(outTrack, buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { it.first.release() }
        }
    }
}
