package com.edureminder.quicktube.core.common

/** What QuickTube writes into saved files: the end of every file name, and the files' description / tags. */
object Branding {
    const val APP = "QuickTube"
    const val DOMAIN = "edureminder.com"
    const val URL = "https://edureminder.com"

    /**
     * Added to the end of every file name, before the extension. Letters, digits, spaces, a dash and dots only, so the
     * name stays valid on every phone, SD card (FAT32/exFAT), computer and cloud drive.
     */
    const val FILE_SUFFIX = " - $APP $DOMAIN"

    const val DESCRIPTION = "Downloaded with $APP by edureminder · $URL"
    const val ENCODER = "$APP ($DOMAIN)"

    val KEYWORDS = listOf(
        APP, "edureminder", DOMAIN, "QuickTube downloader", "video downloader", "YouTube downloader", "YouTube to MP3",
        "YouTube to MP4", "Facebook video downloader", "Instagram reels downloader", "TikTok downloader without watermark",
        "4K video downloader", "HD video downloader", "music downloader", "MP3 converter", "offline video player",
        "free video downloader", "status saver", "edureminder QuickTube",
    )

    /** "Song (720p)" → "Song (720p) - QuickTube edureminder.com"; a name that already ends with it is left alone. */
    fun fileName(base: String): String = if (base.endsWith(FILE_SUFFIX)) base else base + FILE_SUFFIX
}
