package com.edureminder.quicktube.feature.browser

import androidx.lifecycle.ViewModel
import com.edureminder.quicktube.core.common.Urls
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.BrowserRepository
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.downloadsheet.SheetTarget
import com.edureminder.quicktube.core.extractor.InstagramReader
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
    /** The video's own page (/video/…, /reel/…, /p/…) when it plays inside a feed or profile; null otherwise. */
    val link: String? = null,
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
    /** [pageFetch] with request headers. */
    var apiFetch: (suspend (String, Map<String, String>) -> String?)? = null

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
        // The automatic download is for the shared link's own page: once the user (or the page) goes elsewhere —
        // a reel or feed video under a photo post — it would take that video instead.
        if (autoPage != null && pathOf(url) != autoPage) autoDownload = false
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
        // Redirects (a share link → the post) are done by now: this is the page the link stands for.
        if (autoDownload && autoPage == null && !isRedirector(url)) autoPage = pathOf(url)
        _ui.update { it.copy(title = title.orEmpty()) }
        if (recordHistory) viewModelScope.launch { repo.recordVisit(title.orEmpty(), url) }
        // Shared or pasted Instagram post: its photos and videos come from the post itself, no need to wait for playback.
        if (autoDownload && !autoDone && InstagramReader.shortcode(url) != null) viewModelScope.launch {
            if (!autoDone && openInstagram(url)) autoDone = true
            else instagramFailed = url
            maybeAutoDownload()
        }
    }

    /** The Instagram post whose embed couldn't be read: the usual video detection takes over for it. */
    private var instagramFailed: String? = null

    /**
     * Instagram post, reel or IGTV: reads the post's embed page through the open page (the user's own session) and
     * offers every item: the video, or each photo and video of a carousel. False when it couldn't be read.
     */
    private suspend fun openInstagram(url: String): Boolean {
        val code = InstagramReader.shortcode(url) ?: return false
        val fetch = pageFetch ?: return false
        // Signed in: the app's own API has every item at full size and a photo post's song. Signed out: the embed page.
        val post = apiFetch?.invoke(InstagramReader.apiUrl(code), InstagramReader.API_HEADERS)?.let { json ->
            withContext(Dispatchers.Default) { InstagramReader.parseApi(json) }
        } ?: fetch(InstagramReader.embedUrl(code))?.let { html ->
            withContext(Dispatchers.Default) { InstagramReader.parseEmbed(html) }
        } ?: return false
        val single = post.items.size == 1
        val photos = post.items.filter { !it.isVideo }.map { it.url }
        // Photos with a song: also as one video with that song, the way Instagram plays them.
        val slideshow = post.music?.takeIf { photos.isNotEmpty() }?.let { com.edureminder.quicktube.core.downloadsheet.photoPostOptions(photos, it).first() }
        val options = listOfNotNull(slideshow) + post.items.mapIndexed { i, item ->
            val n = if (single) "" else " ${i + 1}"
            val size = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else ""
            if (item.isVideo) DownloadOption(
                "ig$i", FormatKind.VIDEO, "Video$n", size.ifBlank { "Video" }, "mp4", "video/mp4", item.url,
                height = minOf(item.width, item.height).takeIf { it > 0 } ?: 0, thumbnail = item.thumbnail,
            ) else DownloadOption("ig$i", FormatKind.IMAGE, "Photo$n", size.ifBlank { "Photo" }, "jpg", "image/jpeg", item.url, thumbnail = item.thumbnail)
        }
        sheet.openDirect(
            SheetTarget.Direct(
                title = post.title, pageUrl = url, thumbnail = post.thumbnail, options = options,
                headers = sessionHeaders(post.items.first().url), items = true,
            ),
        )
        return true
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

    /** Path of the page the shared link opened (after redirects); null until it has loaded. */
    private var autoPage: String? = null

    /** Short links that only send the browser on to the real page. */
    private fun isRedirector(url: String) = pathOf(url).let { p ->
        "/share/" in "$p/" || listOf("fb.watch", "l.facebook.com", "lm.facebook.com", "youtu.be", "t.co", "vt.tiktok.com", "vm.tiktok.com").any { p.startsWith("$it/") || p == it }
    }

    private fun pathOf(url: String) = runCatching { java.net.URI(url).let { "${it.host?.removePrefix("www.")?.removePrefix("m.")}${it.path?.trimEnd('/')}" } }.getOrDefault(url)
    private var autoDone = false

    private fun maybeAutoDownload() {
        if (!autoDownload || autoDone) return
        val s = _ui.value
        // An Instagram post is read from the post itself (onPageFinished); the video detection is its fallback.
        if (InstagramReader.shortcode(s.url) != null && instagramFailed != s.url) return
        if (s.pageMedia == null && s.mainVideo == null) return
        autoDone = true
        download(isNativePage = false)
    }

    fun toggleBookmark() = viewModelScope.launch { repo.toggleBookmark(_ui.value.title, _ui.value.url) }

    /** Download button: native extraction for supported pages, otherwise the video that is playing. */
    fun download(isNativePage: Boolean) {
        val s = _ui.value
        if (InstagramReader.shortcode(s.url) != null && pageFetch != null) {
            viewModelScope.launch { if (!openInstagram(s.url)) downloadPlaying(isNativePage) }
            return
        }
        downloadPlaying(isNativePage)
    }

    /** Opens a page in the WebView (set by the screen). */
    var openPage: ((String) -> Unit)? = null

    private fun downloadPlaying(isNativePage: Boolean) {
        val s = _ui.value
        val main = s.mainVideo
        // A video playing in a feed or profile (TikTok, Instagram…): the page's data and title belong to the whole page,
        // so open the video's own page and download from there, as for a shared link.
        val own = main?.link?.takeIf { !Urls.namesOneMedia(s.url) && Urls.namesOneMedia(it) && Urls.host(it) == Urls.host(s.url) }
        val open = openPage
        if (own != null && open != null && !isNativePage) {
            autoDownload = true; autoDone = false; autoPage = null; instagramFailed = null
            open(own)
            return
        }
        // Facebook's page data lists the first post it loaded, not the reel or feed video being watched now
        // (reels are a reel's own address: the sheet reads that post directly).
        val declared = s.pageMedia.takeUnless { com.edureminder.quicktube.core.extractor.FacebookReader.isFacebook(s.url) }
        // A photo post (TikTok photo mode): its photos, plus a video of them with the post's music.
        if (declared != null && declared.photos.isNotEmpty()) {
            sheet.openDirect(SheetTarget.Direct(
                title = declared.title?.takeIf { it.isNotBlank() } ?: s.title, thumbnail = declared.thumbnail ?: declared.photos.first(),
                pageUrl = s.url, options = com.edureminder.quicktube.core.downloadsheet.photoPostOptions(declared.photos, declared.music),
                headers = sessionHeaders(declared.photos.first()), items = true,
            ))
            return
        }
        if (isNativePage || main == null && declared == null) {
            sheet.open(s.url)
            return
        }
        val direct = main?.sources?.filter { it.startsWith("http") }?.distinct().orEmpty()
        val playing = direct.map { StreamCandidate(it) }
        // Streams the page actually loaded: always offered as a fallback (newest first) in case declared data is stale or blocked.
        val sniffed = s.sniffed.reversed().map { StreamCandidate(it.url) }
        // A feed video without a page of its own (a TikTok ad, a sponsored clip): the page's data is the feed's first
        // post, so the file the playing video uses comes first.
        val feedOnly = main != null && playing.isNotEmpty() && !Urls.namesOneMedia(s.url)
        val candidates = (if (feedOnly) playing + declared?.candidates.orEmpty() + sniffed else declared?.candidates.orEmpty() + playing + sniffed)
            .distinctBy { it.url }
        sheet.openDirect(
            SheetTarget.Sniffed(
                // The page's title and picture are its first post's: not this feed video's.
                title = declared?.title?.takeIf { it.isNotBlank() && !feedOnly } ?: s.title.ifBlank { "video" },
                pageUrl = s.url,
                thumbnail = (if (feedOnly) main?.poster?.takeIf { it.startsWith("http") } else null)
                    ?: declared?.thumbnail ?: main?.poster?.takeIf { it.startsWith("http") } ?: s.pageImage,
                candidates = candidates,
                // The video playing first: on feeds (Facebook) the page's declared data can belong to another post.
                durationSec = main?.duration?.takeIf { it > 0 && it.isFinite() } ?: declared?.durationSec,
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
