package com.edureminder.quicktube.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.BrowserRepository
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.downloadsheet.SheetTarget
import com.edureminder.quicktube.core.extractor.PageMedia
import com.edureminder.quicktube.core.extractor.PageMediaParser
import com.edureminder.quicktube.core.extractor.StreamCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.edureminder.quicktube.core.extractor.StreamResolver
import com.edureminder.quicktube.core.extractor.WebMediaFinder
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A media request the page made (network sniffing), newest last. */
data class SniffedMedia(val url: String, val seenAt: Long = System.currentTimeMillis())

/** A <video> element as reported by the page script. */
data class PageVideo(
    val sources: List<String>,
    /** Playback began right after the user touched the page (a tap on play), as opposed to autoplay. */
    val userStarted: Boolean = false,
    val playing: Boolean,
    val currentTime: Double,
    val duration: Double,
    val muted: Boolean,
    val loop: Boolean,
    val width: Double,
    val viewportWidth: Double,
    val poster: String?,
)

data class BrowserUi(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    /** The video the user is actually watching; null on listing pages or before playback starts. */
    val mainVideo: PageVideo? = null,
    /** The video the page declares in its player data (known before playback starts). */
    val pageMedia: PageMedia? = null,
    val pageImage: String? = null,
    val sniffed: List<SniffedMedia> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModel(
    private val repo: BrowserRepository,
    private val sheet: DownloadSheetController,
) : ViewModel() {
    private val _ui = MutableStateFlow(BrowserUi())
    val ui: StateFlow<BrowserUi> = _ui
    private val currentUrl = MutableStateFlow("")
    /** Fetch through the open page (set by the screen while the WebView is alive). */
    var pageFetch: (suspend (String) -> String?)? = null

    /** The WebView's own User-Agent; media servers often check it matches the session's cookies. */
    var userAgent: String? = null

    val bookmarked = currentUrl.flatMapLatest { repo.isBookmarked(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun sessionHeaders(mediaUrl: String): Map<String, String> = buildMap {
        put("Referer", _ui.value.url)
        userAgent?.let { put("User-Agent", it) }
        android.webkit.CookieManager.getInstance().getCookie(mediaUrl)?.let { put("Cookie", it) }
    }

    fun onPageStarted(url: String) = onNavigated(url)

    fun onUrlChanged(url: String, title: String?) {
        onNavigated(url)
        if (title != null) _ui.update { it.copy(title = title) }
    }

    /** New page: forget the previous page's video, keep only requests from the last moments (SPA navigations). */
    private fun onNavigated(url: String) {
        currentUrl.value = url
        _ui.update { s ->
            if (s.url.substringBefore('#') == url.substringBefore('#')) s.copy(url = url)
            else {
                val recent = System.currentTimeMillis() - 3000
                frames.clear()
                s.copy(url = url, mainVideo = null, pageMedia = null, pageImage = null, sniffed = s.sniffed.filter { it.seenAt >= recent })
            }
        }
    }

    /** False for the invisible browser behind the share sheet: those pages weren't visited by the user. */
    var recordHistory = true

    fun onPageFinished(url: String, title: String?) {
        _ui.update { it.copy(title = title.orEmpty()) }
        if (recordHistory) viewModelScope.launch { repo.recordVisit(title.orEmpty(), url) }
    }

    fun onProgress(p: Int, canGoBack: Boolean) = _ui.update { it.copy(progress = p, canGoBack = canGoBack) }

    /** Network-level sniffing: playlists and media files the page requests. */
    fun onMediaRequest(url: String) {
        if (!url.startsWith("http") || url.contains("googlevideo.com") || StreamResolver.isSegment(url)) return
        _ui.update { s ->
            if (s.sniffed.any { it.url == url }) s
            else s.copy(sniffed = (s.sniffed + SniffedMedia(url)).takeLast(40))
        }
    }

    /** Latest <video> report per frame (top page + iframes), with arrival time. */
    private val frames = mutableMapOf<String, Pair<Long, List<PageVideo>>>()

    /** Page-script report of <video> elements from one frame. */
    fun onPageReport(frameId: String, isTop: Boolean, videos: List<PageVideo>, title: String?, image: String?) {
        val now = System.currentTimeMillis()
        frames[frameId] = now to videos
        frames.entries.removeAll { now - it.value.first > 6000 }
        val all = frames.values.flatMap { it.second }
        val main = all.filter(::isMainVideo).maxByOrNull { if (it.duration > 0) it.duration else 1e9 }
        _ui.update { s ->
            s.copy(
                // Once a video is confirmed it stays until navigation (pausing shouldn't hide the button).
                mainVideo = main ?: s.mainVideo,
                title = title?.takeIf { it.isNotBlank() } ?: s.title,
                pageImage = image ?: s.pageImage,
            )
        }
        maybeAutoDownload()
    }

    /**
     * A "real" video: playback started, and either the user started it (tapped play), or it autoplays big,
     * with sound or long enough not to be a preview. Listing pages autoplay small, muted, looping previews — those never qualify.
     */
    private fun isMainVideo(v: PageVideo): Boolean {
        if (!(v.playing || v.currentTime > 0.5)) return false
        if (v.width < 80) return false
        val big = v.viewportWidth > 0 && v.width >= v.viewportWidth * 0.6
        if (v.userStarted && (big || !v.muted)) return true
        val knownDuration = v.duration > 0
        if (v.muted && v.loop) return false
        if (knownDuration && v.duration < 10) return false
        if (v.muted && knownDuration && v.duration < 45) return false
        return big || !v.muted
    }

    /** Rendered page HTML (with the user's cookies/age gate passed) → declared video sources. */
    fun onPageHtml(url: String, html: String) {
        viewModelScope.launch {
            val media = withContext(Dispatchers.Default) { runCatching { PageMediaParser.parse(html, url) }.getOrNull() } ?: return@launch
            _ui.update { s -> if (s.url.substringBefore('#') != url.substringBefore('#')) s else s.copy(pageMedia = media) }
            maybeAutoDownload()
        }
    }

    /** Set when the browser was opened from a pasted/shared link: show the download sheet as soon as possible, once. */
    var autoDownload = false
    private var autoDone = false

    private fun maybeAutoDownload() {
        if (!autoDownload || autoDone) return
        val s = _ui.value
        if (s.pageMedia == null && s.mainVideo == null) return
        autoDone = true
        download(isNativePage = false)
    }

    fun toggleBookmark() = viewModelScope.launch { repo.toggleBookmark(_ui.value.title, _ui.value.url) }

    /** Download button: native extraction for supported pages, otherwise the video that is playing. */
    fun download(isNativePage: Boolean) {
        val s = _ui.value
        val main = s.mainVideo
        val declared = s.pageMedia
        if (isNativePage || main == null && declared == null) {
            sheet.open(s.url)
            return
        }
        val direct = main?.sources?.filter { it.startsWith("http") }?.distinct().orEmpty()
        val playing = direct.map { StreamCandidate(it) }
        // Streams the page actually loaded: always offered as a fallback (newest first) in case declared data is stale or blocked.
        val sniffed = s.sniffed.reversed().map { StreamCandidate(it.url) }
        val candidates = (declared?.candidates.orEmpty() + playing + sniffed).distinctBy { it.url }
        sheet.openDirect(
            SheetTarget.Sniffed(
                title = declared?.title?.takeIf { it.isNotBlank() } ?: s.title.ifBlank { "video" },
                pageUrl = s.url,
                thumbnail = declared?.thumbnail ?: main?.poster?.takeIf { it.startsWith("http") } ?: s.pageImage,
                candidates = candidates,
                durationSec = declared?.durationSec ?: main?.duration?.takeIf { it > 0 },
                headers = sessionHeaders(candidates.firstOrNull()?.url ?: s.url),
                pageFetch = pageFetch,
            ),
        )
    }

    fun downloadFile(url: String, mime: String?, fileName: String?) {
        val m = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: WebMediaFinder.mimeFor(url)
        val ext = fileName?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 } ?: WebMediaFinder.extFor(m)
        sheet.openDirect(
            SheetTarget.Direct(
                title = fileName?.substringBeforeLast('.') ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download" },
                pageUrl = _ui.value.url, thumbnail = null,
                options = listOf(DownloadOption("f", if (m.startsWith("audio")) FormatKind.AUDIO else FormatKind.VIDEO, "Download file", ext.uppercase(), ext, m, url)),
                headers = sessionHeaders(url),
            ),
        )
    }
}

class SitesViewModel(private val repo: BrowserRepository) : ViewModel() {
    val sites = repo.sites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = repo.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun add(name: String, url: String) = viewModelScope.launch { repo.addSite(name, url) }
    fun delete(ids: List<Long>) = viewModelScope.launch { repo.deleteSites(ids) }
    fun deleteHistory(id: Long) = viewModelScope.launch { repo.deleteHistory(id) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
    fun removeBookmark(url: String) = viewModelScope.launch { repo.removeBookmark(url) }
}
