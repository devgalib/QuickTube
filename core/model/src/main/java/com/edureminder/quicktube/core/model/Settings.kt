package com.edureminder.quicktube.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent palettes the user can pick in Settings → Appearance. */
enum class ThemeAccent(val label: String) {
    MINT("Mint"), OCEAN("Ocean"), VIOLET("Violet"), SUNSET("Sunset"), ROSE("Rose"), LIME("Lime"), GOLD("Gold")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: ThemeAccent = ThemeAccent.MINT,
    /** Pure black backgrounds in dark mode (saves battery on OLED screens). */
    val amoled: Boolean = false,
    /** Wallpaper-based colours on Android 12+. */
    val dynamicColor: Boolean = false,
    val downloadDir: String = "",
    val maxTasksWifi: Int = 4,
    val maxTasksMobile: Int = 2,
    /** KB/s, 0 = unlimited. */
    val speedLimitKbps: Int = 0,
    val allowMobileData: Boolean = true,
    val notifyProgress: Boolean = true,
    val notifyCompleted: Boolean = true,
    val notifyRecommended: Boolean = true,
    val notifyTools: Boolean = true,
    val toolbarNotification: Boolean = false,
    val vaultPin: String? = null,
    val language: String = "en",
    val contentRegion: String = "",
    val playbackSpeed: Float = 1f,
    val onboarded: Boolean = false,
    /** Crash reports + anonymous usage stats (official builds only). */
    val analyticsEnabled: Boolean = true,
)

data class Site(
    val id: Long = 0,
    val name: String,
    val url: String,
    val builtIn: Boolean = false,
    val position: Int = 0,
)

data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)
