package com.edureminder.quicktube.core.common

import java.io.File

/** Where the desktop app keeps its files, following each OS's convention. */
object DesktopDirs {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = "win" in os
    val isMac = "mac" in os
    private val home = File(System.getProperty("user.home"))

    /** Database, settings, yt-dlp: ~/.local/share/QuickTube (Linux), %APPDATA%\QuickTube (Windows), ~/Library/Application Support/QuickTube (macOS). */
    val data: File by lazy {
        val base = when {
            isWindows -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            isMac -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
        }
        File(base, "QuickTube").apply { mkdirs() }
    }

    /** Download parts while they're fetched and merged. */
    val parts: File get() = File(data, "parts").apply { mkdirs() }

    /** The default download folder: ~/Downloads/QuickTube (the user's Downloads folder on every OS). */
    val downloads: File get() = File(xdgDownloads() ?: File(home, "Downloads"), "QuickTube")

    /** Linux desktops may move Downloads (and translate its name); user-dirs.dirs says where. */
    private fun xdgDownloads(): File? {
        if (isWindows || isMac) return null
        val conf = File(home, ".config/user-dirs.dirs").takeIf { it.isFile } ?: return null
        val line = conf.readLines().firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") } ?: return null
        val path = line.substringAfter('=').trim('"').replace("\$HOME", home.path)
        return File(path).takeIf { it.isDirectory }
    }
}
