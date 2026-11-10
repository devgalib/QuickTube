package com.edureminder.quicktube.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A photo post with music (TikTok photo mode, Instagram photos with a song) as an MP4: each picture shown in turn for an
 * equal share of the song, fitted into one frame size, with the song as the sound (H.264 + AAC, plays everywhere).
 */
@OptIn(UnstableApi::class)
internal object SlideshowMaker {
    /** The longest side of the video (posts are at most 1080 wide); still pictures need little bitrate. */
    private const val MAX_SIDE = 1080
    private const val BITRATE = 2_500_000

    suspend fun make(context: Context, pictures: List<File>, music: File, output: File): File {
        require(pictures.isNotEmpty()) { "No pictures" }
        // Pictures as JPEG (posts also use WebP/HEIF) at most MAX_SIDE, so every decoder takes them.
        val frames = pictures.mapIndexed { i, f -> toJpeg(f, File(output.parentFile, "${output.nameWithoutExtension}.frame$i.jpg")) }
        try {
            val musicMs = durationMs(music).takeIf { it > 0 } ?: (frames.size * 3000L)
            // Every picture gets an equal share of the song, at least 1.5 s each.
            val perMs = (musicMs / frames.size).coerceAtLeast(1500)
            val (w, h) = frameSize(frames.first())
            val fit = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
            val video = EditedMediaItemSequence.Builder(frames.map { f ->
                EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(f)).setImageDurationMs(perMs).build())
                    .setFrameRate(30).setEffects(fit).build()
            }).build()
            val audio = EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).setRemoveVideo(true).build(),
            ).build()
            output.delete()
            export(context, Composition.Builder(video, audio).build(), output)
            return output
        } finally {
            frames.forEach { it.delete() }
        }
    }

    private suspend fun export(context: Context, composition: Composition, output: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val encoders = androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                .build()
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(encoders)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(IOException("Couldn't make the video: ${exportException.message}", exportException))
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, output.absolutePath)
        }
    }

    private fun durationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(file.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { release() }
        }
    }.getOrDefault(0L)

    private fun toJpeg(src: File, dest: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("A picture of this post couldn't be read")
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        dest.outputStream().use { sized.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return dest
    }

    /** The first picture's shape, with even sides (H.264 needs them). */
    private fun frameSize(jpeg: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.path, o)
        return (o.outWidth / 2 * 2).coerceAtLeast(2) to (o.outHeight / 2 * 2).coerceAtLeast(2)
    }
}
