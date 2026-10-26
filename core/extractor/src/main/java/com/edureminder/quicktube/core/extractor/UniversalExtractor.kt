package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.DownloadOption

/** Everything needed to download a video from an arbitrary web page. */
data class ExtractedPage(
    val title: String,
    val thumbnail: String?,
    val durationSec: Double?,
    val options: List<DownloadOption>,
    val headers: Map<String, String>,
)

/**
 * Site-specific extraction for "the rest of the web" (1800+ sites). Implemented by the yt-dlp engine;
 * kept behind an interface so the engine can be swapped or disabled per platform.
 */
interface UniversalExtractor {
    /** False while the engine is still unpacking on first launch, or on platforms without it. */
    val isReady: Boolean
    suspend fun extract(url: String, headers: Map<String, String> = emptyMap()): ExtractedPage?
    /** Pulls the latest site support (like SnapTube's server-side rules). Returns the new version, if any. */
    suspend fun update(): String?
}
