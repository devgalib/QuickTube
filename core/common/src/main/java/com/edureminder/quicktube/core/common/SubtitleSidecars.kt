package com.edureminder.quicktube.core.common

import java.io.File
import java.util.Locale

/** Caption files saved beside a video: "Title(720p).srt" or "Title(720p).en.srt" (also .vtt). */
object SubtitleSidecars {
    private val EXTENSIONS = setOf("srt", "vtt")

    /** Each caption file of [video] with a readable name ("English", or the file's tag), language order kept stable. */
    fun find(video: File): List<Pair<String, File>> {
        val base = video.nameWithoutExtension
        return video.parentFile?.listFiles { f ->
            f.isFile && f.extension.lowercase() in EXTENSIONS && (f.nameWithoutExtension == base || f.nameWithoutExtension.startsWith("$base."))
        }.orEmpty().sortedBy { it.name }.map { f -> label(f.nameWithoutExtension.removePrefix(base).removePrefix(".")) to f }
    }

    private fun label(tag: String): String {
        if (tag.isBlank()) return "Subtitles"
        val name = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && it != tag }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: tag
    }

    /** MIME type for the player. */
    fun mimeOf(file: File) = if (file.extension.equals("vtt", true)) "text/vtt" else "application/x-subrip"
}
