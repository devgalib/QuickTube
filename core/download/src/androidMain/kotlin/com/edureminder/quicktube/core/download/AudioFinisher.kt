package com.edureminder.quicktube.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The last steps of an audio download: MP3 conversion when MP3 was chosen, then the cover picture, title and
 * artist written into the file. The cover is also kept in app storage, so the Library shows it offline and after
 * the site's picture link has expired.
 */
internal class AudioFinisher(private val context: Context, private val http: OkHttpClient) {
    /** Converts [file] (any audio the phone decodes) into the MP3 [target] wants; returns the MP3. */
    suspend fun toMp3(file: File, target: File, kbps: Int = 192): File {
        if (Mp3Encoder.isMp3(file)) return file
        val mp3 = File(target.parentFile ?: file.parentFile, target.nameWithoutExtension + ".encoding.mp3")
        val started = System.currentTimeMillis()
        Mp3Encoder.encode(file, mp3, kbps)
        Log.i(TAG, "MP3 conversion took ${System.currentTimeMillis() - started} ms")
        if (file != mp3) file.delete()
        return mp3
    }

    /**
     * Tags [file] and returns the saved cover's path (null when there's no picture). Never fails the download:
     * a song without a cover is still a song.
     */
    fun tag(id: Long, file: File, title: String, thumbnail: String?, headers: Map<String, String>): String? {
        val cover = thumbnail?.let { coverFor(id, it, headers) }
        runCatching { AudioTags.write(file, AudioTags.fromTitle(title, cover = cover?.readBytes())) }
            .onFailure { Log.w(TAG, "Tagging failed: $it") }
        return cover?.absolutePath
    }

    /** A square JPEG of the video's picture (the middle of a 16:9 thumbnail, like music apps show), at most 720 px. */
    private fun coverFor(id: Long, source: String, headers: Map<String, String>): File? = runCatching {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        val local = File(source)
        val bytes = if (local.isAbsolute && local.exists()) local.readBytes() else {
            val req = Request.Builder().url(source)
                .apply { headers.filterKeys { it == "User-Agent" || it == "Referer" }.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
        } ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = if (side > MAX_SIDE) Bitmap.createScaledBitmap(square, MAX_SIDE, MAX_SIDE, true) else square
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        out.writeBytes(jpeg)
        out
    }.onFailure { Log.w(TAG, "Cover failed: $it") }.getOrNull()

    private companion object {
        const val TAG = "AudioFinisher"
        const val MAX_SIDE = 720
    }
}
