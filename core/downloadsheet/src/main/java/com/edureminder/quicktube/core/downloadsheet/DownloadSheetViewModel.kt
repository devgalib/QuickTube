package com.edureminder.quicktube.core.downloadsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.extractor.StreamCandidate
import com.edureminder.quicktube.core.extractor.StreamResolver
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import com.edureminder.quicktube.core.extractor.WebMediaFinder
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.DownloadRequest
import com.edureminder.quicktube.core.model.FormatKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

data class SheetUi(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val pageUrl: String? = null,
    val options: List<DownloadOption> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean = false,
    /** Diagnostics shown under "Technical details" when nothing could be found. */
    val report: List<String> = emptyList(),
)

class DownloadSheetViewModel(
    private val media: MediaRepository,
    private val downloads: DownloadManager,
    private val web: WebMediaFinder,
    private val resolver: StreamResolver,
    private val universal: UniversalExtractor,
    private val analytics: com.edureminder.quicktube.core.analytics.Analytics,
    private val http: okhttp3.OkHttpClient,
) : ViewModel() {
    private val _ui = MutableStateFlow(SheetUi())

    init {
        // Every format shows its size: ask the server for any the site didn't report (one tiny request each).
        viewModelScope.launch {
            _ui.collect { s ->
                val missing = s.options.filter { it.protocol == com.edureminder.quicktube.core.model.StreamProtocol.HTTP &&
                    (it.sizeBytes <= 0 || it.audioUrl != null && it.audioSizeBytes <= 0) && it.id !in sizeChecked }
                if (missing.isEmpty()) return@collect
                sizeChecked += missing.map { it.id }
                val sized = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    missing.map { o ->
                        async {
                            o.copy(
                                sizeBytes = o.sizeBytes.takeIf { it > 0 } ?: remoteSize(o.url, s.headers),
                                audioSizeBytes = o.audioUrl?.let { u -> o.audioSizeBytes.takeIf { it > 0 } ?: remoteSize(u, s.headers) }
                                    ?: o.audioSizeBytes,
                            )
                        }
                    }.awaitAll().associateBy { it.id }
                }
                _ui.update { cur -> cur.copy(options = cur.options.map { sized[it.id] ?: it }) }
            }
        }
    }

    private val sizeChecked = mutableSetOf<String>()

    private fun remoteSize(url: String, headers: Map<String, String>): Long = runCatching {
        val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body.contentLength().takeIf { r.code == 200 }
        } ?: -1L
    }.getOrDefault(-1L)
    val ui: StateFlow<SheetUi> = _ui
    private var job: Job? = null
    private var loadedFor: SheetTarget? = null

    fun load(target: SheetTarget) {
        if (loadedFor == target && _ui.value.error == null) return
        loadedFor = target
        job?.cancel()
        when (target) {
            is SheetTarget.Direct -> _ui.value = SheetUi(
                loading = false, title = target.title, thumbnail = target.thumbnail,
                pageUrl = target.pageUrl, options = target.options, headers = target.headers,
            )
            is SheetTarget.Sniffed -> {
                _ui.value = SheetUi(loading = true, title = target.title, thumbnail = target.thumbnail, pageUrl = target.pageUrl,
                    durationSec = target.durationSec?.toLong() ?: -1)
                job = viewModelScope.launch {
                    val r = runCatching { resolver.resolveWithReport(target.candidates, target.headers, target.durationSec, target.pageFetch) }
                        .getOrElse { StreamResolver.Result(emptyList(), listOf("resolver: ${it.message}")) }
                    var options = r.options
                    var headers = target.headers
                    var report = r.report
                    var title = _ui.value.title
                    var thumb = _ui.value.thumbnail
                    if (options.isEmpty()) {
                        // Site-specific engine (1800+ sites) with the page's session.
                        val page = universal.extract(target.pageUrl, target.headers)
                        report = report + "yt-dlp → " + (page?.let { "${it.options.size} formats" } ?: "no result")
                        if (page != null && page.options.isNotEmpty()) {
                            options = page.options
                            headers = target.headers + page.headers
                            if (title.isBlank() || title == "video") title = page.title
                            thumb = thumb ?: page.thumbnail
                        }
                    }
                    _ui.value = _ui.value.copy(
                        loading = false, options = options, headers = headers, report = report, title = title, thumbnail = thumb,
                        error = if (options.isEmpty()) "Couldn't get this video. It may be protected (DRM) or still loading — let it play a few seconds and try again." else null,
                    )
                }
            }
            is SheetTarget.Page -> {
                _ui.value = SheetUi(loading = true, title = target.preview?.title.orEmpty(), thumbnail = target.preview?.thumbnail, pageUrl = target.url)
                job = viewModelScope.launch {
                    _ui.value = try {
                        if (media.linkKind(target.url) != com.edureminder.quicktube.core.model.ItemKind.VIDEO) {
                            loadWebPage(target.url)
                        } else {
                        val d = media.details(target.url)
                        SheetUi(
                            loading = false, title = d.item.title, thumbnail = d.item.thumbnail, durationSec = d.item.durationSec,
                            pageUrl = d.item.url, options = d.options, isLive = d.item.isLive,
                            error = if (d.item.isLive) "Live streams can't be downloaded until they end." else null,
                        )
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        _ui.value.copy(loading = false, error = e.message ?: "Couldn't load formats")
                    }
                }
            }
        }
    }

    private suspend fun loadWebPage(url: String): SheetUi {
        val started = System.currentTimeMillis()
        fun timing(stage: String) = android.util.Log.i("SheetTiming", "$stage at ${System.currentTimeMillis() - started} ms")
        // Facebook: its own reader gives every resolution; the generic page reader would only find SD/HD.
        if (com.edureminder.quicktube.core.extractor.FacebookReader.isFacebook(url)) {
            val fb = universal.extract(url)
            timing("facebook (${fb?.options?.size ?: -1} options)")
            if (fb != null && fb.options.isNotEmpty()) return SheetUi(
                loading = false, title = fb.title, thumbnail = fb.thumbnail, pageUrl = url, options = fb.options, headers = fb.headers,
                durationSec = fb.durationSec?.toLong() ?: -1,
            )
        }
        val page = web.find(url)
        timing("page read (${page?.candidates?.size ?: -1} candidates)")
        val r = if (page != null && page.candidates.isNotEmpty()) resolver.resolveWithReport(page.candidates, page.headers, page.durationSec)
        else StreamResolver.Result(emptyList(), listOf("page: " + if (page == null) "couldn't load" else "no video data"))
        timing("resolved (${r.options.size} options)")
        if (r.options.isNotEmpty() && page != null) return SheetUi(
            loading = false, title = page.title, thumbnail = page.thumbnail, pageUrl = url, options = r.options, headers = page.headers,
            durationSec = page.durationSec?.toLong() ?: -1, report = r.report,
        )
        // Fall back to the site-specific engine (1800+ sites).
        val x = universal.extract(url)
        timing("yt-dlp (${x?.options?.size ?: -1} options)")
        val report = r.report + "yt-dlp → " + (x?.let { "${it.options.size} formats" } ?: "no result")
        if (x != null && x.options.isNotEmpty()) return SheetUi(
            loading = false, title = x.title, thumbnail = x.thumbnail, pageUrl = url, options = x.options, headers = x.headers,
            durationSec = x.durationSec?.toLong() ?: -1, report = report,
        )
        return SheetUi(
            loading = false, title = page?.title.orEmpty(), thumbnail = page?.thumbnail, pageUrl = url, report = report,
            error = "No video found. Try opening the page in the QuickTube browser and play the video.",
        )
    }

    fun retry() {
        val t = loadedFor ?: return
        loadedFor = null
        load(t)
    }

    /** The same video in this quality already downloaded or downloading (for the "download again?" question). */
    suspend fun existing(option: DownloadOption): com.edureminder.quicktube.core.model.DownloadTask? {
        val s = _ui.value
        return runCatching { downloads.findExisting(option.toRequest(s.title, s.pageUrl, s.thumbnail, s.durationSec, s.headers)) }.getOrNull()
    }

    fun download(option: DownloadOption) {
        val s = _ui.value
        analytics.event(
            com.edureminder.quicktube.core.analytics.Events.DOWNLOAD_STARTED,
            mapOf("kind" to option.kind.name.lowercase(), "protocol" to option.protocol.name.lowercase(), "audio_only" to option.extractAudio),
        )
        // Not viewModelScope: the share sheet's activity finishes the moment a format is tapped, which would
        // cancel the request before it's saved.
        downloads.submit(option.toRequest(s.title, s.pageUrl, s.thumbnail, s.durationSec, s.headers))
    }
}
