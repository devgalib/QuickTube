package com.edureminder.quicktube.core.ytdlp

/**
 * Binds the site engine (`UniversalExtractor`): fast native readers first, then yt-dlp for 1800+ sites. Android runs
 * yt-dlp in a bundled Python; desktop runs the official standalone binary.
 */
expect val ytDlpModule: org.koin.core.module.Module
