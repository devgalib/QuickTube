package com.edureminder.quicktube.core.model

/** A photo, video or audio file from the device's media library (MediaStore), newest first in lists. */
data class GalleryMedia(
    val id: Long,
    val isVideo: Boolean,
    val mimeType: String,
    /** When it was taken (EXIF / recording time), or the file's modified time when that is missing. */
    val dateMs: Long,
    /** Changes when the file is edited, so thumbnails can be re-generated. */
    val modifiedSec: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bucketId: Long,
    val bucketName: String,
    val name: String,
    val sizeBytes: Long,
    val path: String,
    val isAudio: Boolean = false,
    /** Audio only: the track's artist, when the file has one. */
    val artist: String = "",
) {
    val isPhoto: Boolean get() = !isVideo && !isAudio
}

/**
 * A folder of media, like the system gallery's albums. [cover] is its newest item (null for an empty virtual album).
 * [virtual] albums (All photos / All videos / All audios) gather one kind of media from every folder.
 */
data class GalleryAlbum(val bucketId: Long, val name: String, val count: Int, val cover: GalleryMedia?, val virtual: Boolean = false)

/** What the gallery can show right now. [complete] is false while later pages are still loading. */
data class GalleryLibrary(val items: List<GalleryMedia>, val complete: Boolean)
