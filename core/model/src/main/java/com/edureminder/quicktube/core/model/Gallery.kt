package com.edureminder.quicktube.core.model

/** A photo or video from the device's media library (MediaStore), newest first in lists. */
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
)

/** A folder of media, like the system gallery's albums. [cover] is its newest item. */
data class GalleryAlbum(val bucketId: Long, val name: String, val count: Int, val cover: GalleryMedia)

/** What the gallery can show right now. [complete] is false while later pages are still loading. */
data class GalleryLibrary(val items: List<GalleryMedia>, val complete: Boolean)
