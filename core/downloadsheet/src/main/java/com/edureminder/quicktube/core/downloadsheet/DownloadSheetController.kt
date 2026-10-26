package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.extractor.StreamCandidate
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the sheet should show. */
sealed interface SheetTarget {
    /** A page URL the extractor can resolve (YouTube etc.). */
    data class Page(val url: String, val preview: MediaItem? = null) : SheetTarget

    /** Media found directly (browser sniffing, status saver): options are already known. */
    data class Direct(
        val title: String,
        val pageUrl: String?,
        val thumbnail: String?,
        val options: List<DownloadOption>,
        val headers: Map<String, String> = emptyMap(),
    ) : SheetTarget

    /** Streams a web page loaded for the video being watched; resolved to the matching one(s) when the sheet opens. */
    data class Sniffed(
        val title: String,
        val pageUrl: String,
        val thumbnail: String?,
        val candidates: List<StreamCandidate>,
        val durationSec: Double?,
        val headers: Map<String, String>,
        /** Fetches a URL from inside the open page (its cookies/session); null outside the browser. */
        val pageFetch: (suspend (String) -> String?)? = null,
    ) : SheetTarget
}

/** App-wide trigger for the download sheet; any screen can call [open]. */
class DownloadSheetController {
    private val _target = MutableStateFlow<SheetTarget?>(null)
    val target: StateFlow<SheetTarget?> = _target

    fun open(url: String, preview: MediaItem? = null) {
        _target.value = SheetTarget.Page(url, preview)
    }

    fun open(item: MediaItem) = open(item.url, item)

    fun openDirect(target: SheetTarget) {
        _target.value = target
    }

    fun dismiss() {
        _target.value = null
    }
}
