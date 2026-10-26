package com.edureminder.quicktube.core.analytics

/**
 * Product analytics and crash reporting, kept behind an interface so features never touch a vendor SDK.
 * Only coarse, non-identifying events are sent: never URLs, site names, titles, search terms or file names.
 */
interface Analytics {
    fun screen(name: String)
    fun event(name: String, params: Map<String, Any?> = emptyMap())
    /** Records a handled problem for crash reporting (no-op when reporting is off). */
    fun recordError(t: Throwable)
    /** The user's choice in Settings → Privacy. */
    fun setEnabled(enabled: Boolean)
}

/** Event names, so they stay consistent across features. */
object Events {
    const val DOWNLOAD_STARTED = "download_started"
    const val DOWNLOAD_COMPLETED = "download_completed"
    const val DOWNLOAD_FAILED = "download_failed"
    const val SHARE_TO_DOWNLOAD = "share_to_download"
    const val SEARCH = "search"
    const val THEME_CHANGED = "theme_changed"
}

/** Used when Firebase isn't configured (e.g. a contributor's fork without google-services.json). */
object NoOpAnalytics : Analytics {
    override fun screen(name: String) = Unit
    override fun event(name: String, params: Map<String, Any?>) = Unit
    override fun recordError(t: Throwable) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
}
