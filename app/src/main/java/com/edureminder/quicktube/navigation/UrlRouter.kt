package com.edureminder.quicktube.navigation

import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.navigation.BrowserRoute
import com.edureminder.quicktube.core.navigation.ChannelRoute
import com.edureminder.quicktube.core.navigation.PlaylistRoute
import com.edureminder.quicktube.core.navigation.StatusSaverRoute
import com.edureminder.quicktube.core.navigation.VideoRoute

/** Decides which screen opens a URL: native pages for supported links, the browser otherwise. */
class UrlRouter(private val media: MediaRepository, private val sheet: DownloadSheetController) {
    /**
     * A link the user pasted or shared means "download this": native pages open with the format sheet on top,
     * any other site opens in the browser, which pops the sheet as soon as it finds the page's video.
     */
    fun routeForPasted(raw: String): Any {
        val url = Urls.normalize(raw)
        return when (val r = routeFor(url)) {
            is VideoRoute -> r.also { sheet.open(url) }
            is BrowserRoute -> r.copy(autoDownload = true)
            else -> r
        }
    }

    fun routeFor(raw: String): Any {
        if (raw == "quicktube://status-saver") return StatusSaverRoute
        val url = Urls.normalize(raw)
        return when (media.linkKind(url)) {
            ItemKind.VIDEO -> VideoRoute(url)
            ItemKind.PLAYLIST -> PlaylistRoute(url)
            ItemKind.CHANNEL -> ChannelRoute(url)
            null -> BrowserRoute(url)
        }
    }
}
