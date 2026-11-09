package com.edureminder.quicktube.core.downloadsheet

import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.extractor.StreamResolver
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import com.edureminder.quicktube.core.extractor.WebMediaFinder
import com.edureminder.quicktube.core.model.ItemKind

/**
 * A link's download choices, from any site: YouTube through its extractor, sites with their own reader, the page's
 * own video data, then the site-specific engine. Shared by the formats sheet and bulk downloads.
 */
class LinkResolver(
    private val media: MediaRepository,
    private val web: WebMediaFinder,
    private val resolver: StreamResolver,
    private val universal: UniversalExtractor,
) {
    /** Never throws (except cancellation): a link that can't be read comes back with [SheetUi.error] set. */
    suspend fun resolve(url: String): SheetUi = try {
        if (media.linkKind(url) != ItemKind.VIDEO) loadWebPage(url)
        else {
            val d = media.details(url)
            SheetUi(
                loading = false, title = d.item.title, thumbnail = d.item.thumbnail, durationSec = d.item.durationSec,
                pageUrl = d.item.url, options = d.options, isLive = d.item.isLive,
                error = if (d.item.isLive) "Live streams can't be downloaded until they end." else null,
                cuttable = !d.item.isLive && d.item.durationSec > 1,
            )
        }
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        SheetUi(loading = false, pageUrl = url, error = e.message ?: "Couldn't load formats")
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
        // Sites with their own reader: the page's embedded data is a preview or one stream; the reader has every quality.
        if (com.edureminder.quicktube.core.extractor.BilibiliReader.isBilibili(url) ||
            com.edureminder.quicktube.core.extractor.DailymotionReader.videoId(url) != null) {
            val native = universal.extract(url)
            timing("site reader (${native?.options?.size ?: -1} options)")
            if (native != null && native.options.isNotEmpty()) return SheetUi(
                loading = false, title = native.title, thumbnail = native.thumbnail, pageUrl = url, options = native.options,
                headers = native.headers, durationSec = native.durationSec?.toLong() ?: -1,
            )
        }
        val page = web.find(url)
        if (page?.gone == true) return SheetUi(
            loading = false, pageUrl = url, title = page.title, gone = true,
            error = "This video was removed or is private. It can't be downloaded.",
        )
        // A photo post: the photos, plus a video of them with the post's music.
        if (page != null && page.photos.isNotEmpty()) return SheetUi(
            loading = false, title = page.title.orEmpty(), thumbnail = page.thumbnail ?: page.photos.first(), pageUrl = url,
            options = photoPostOptions(page.photos, page.music), headers = page.headers, items = true,
        )
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
}
