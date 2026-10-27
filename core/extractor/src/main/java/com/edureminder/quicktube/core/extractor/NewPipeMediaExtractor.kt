package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.ChannelDetails
import com.edureminder.quicktube.core.model.Comment
import com.edureminder.quicktube.core.model.DownloadOption
import com.edureminder.quicktube.core.model.FormatKind
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.MediaSection
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.PlaylistDetails
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.model.SectionStyle
import com.edureminder.quicktube.core.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.Page as NpPage
import org.schabi.newpipe.extractor.MediaFormat as NpFormat

/** YouTube (and other NewPipe services) implementation built on NewPipeExtractor. */
class NewPipeMediaExtractor(
    client: OkHttpClient,
    language: String = java.util.Locale.getDefault().language.ifBlank { "en" },
    country: String = java.util.Locale.getDefault().country.ifBlank { "US" },
) : MediaExtractor {

    private val yt: StreamingService = ServiceList.YouTube

    init {
        NewPipe.init(OkHttpDownloader(client), Localization(language, country), ContentCountry(country))
    }

    fun setRegion(language: String, country: String) {
        NewPipe.setupLocalization(Localization(language, country), ContentCountry(country))
    }

    private fun serviceFor(url: String): StreamingService =
        runCatching { NewPipe.getServiceByUrl(url) }.getOrDefault(yt)

    /**
     * Only sites with a real native page. (NewPipe's PeerTube handler matches any "/videos/" URL on any domain,
     * which would hijack ordinary websites.)
     */
    private fun isNativeHost(url: String): Boolean {
        val host = com.edureminder.quicktube.core.common.Urls.host(url) ?: return false
        return NATIVE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    override fun canHandle(url: String): Boolean = isNativeHost(url) && runCatching { NewPipe.getServiceByUrl(url); true }.getOrDefault(false)

    override fun linkKind(url: String): ItemKind? = if (com.edureminder.quicktube.core.common.Urls.youtubeVideoId(url) != null) ItemKind.VIDEO
    else if (!isNativeHost(url)) null else runCatching {
        when (NewPipe.getServiceByUrl(url).getLinkTypeByUrl(url)) {
            StreamingService.LinkType.STREAM -> ItemKind.VIDEO
            StreamingService.LinkType.CHANNEL -> ItemKind.CHANNEL
            StreamingService.LinkType.PLAYLIST -> ItemKind.PLAYLIST
            else -> null
        }
    }.getOrNull()

    override suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList() else yt.suggestionExtractor.suggestionList(query)
    }

    override suspend fun search(query: String, filter: SearchFilter, page: Any?): Page<MediaItem> = io {
        val contentFilter = when (filter) {
            SearchFilter.ALL -> YoutubeSearchQueryHandlerFactory.ALL
            SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
            SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
            SearchFilter.CHANNELS -> YoutubeSearchQueryHandlerFactory.CHANNELS
            SearchFilter.MUSIC -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        }
        val handler = yt.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        if (page == null) {
            val info = SearchInfo.getInfo(yt, handler)
            Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
        } else {
            val more = SearchInfo.getMoreItems(yt, handler, page as NpPage)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        }
    }

    /** Paging cursor across several kiosks (music, gaming, live…). */
    private data class MultiPage(val pages: Map<String, NpPage>)

    override suspend fun trending(page: Any?): Page<MediaItem> = coroutineScope {
        val kiosks = yt.kioskList
        val ids = kiosks.availableKiosks.toList()
        val results = if (page == null) {
            ids.map { id ->
                async {
                    runCatching {
                        val url = kiosks.getListLinkHandlerFactoryByType(id).fromId(id).url
                        val info = io { KioskInfo.getInfo(yt, url) }
                        Triple(id, info.relatedItems.mapNotNull { it.toItem() }, info.nextPage)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        } else {
            (page as MultiPage).pages.map { (id, p) ->
                async {
                    runCatching {
                        val url = kiosks.getListLinkHandlerFactoryByType(id).fromId(id).url
                        val more = io { KioskInfo.getMoreItems(yt, url, p) }
                        Triple(id, more.items.mapNotNull { it.toItem() }, more.nextPage)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (page == null && results.isEmpty()) error("Couldn't load trending videos")
        // Interleave so the feed mixes categories instead of showing one block per kiosk.
        val lists = results.map { it.second.filter { item -> !item.isShort } }
        val mixed = buildList {
            var i = 0
            while (lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let(::add) }; i++ }
        }
        val next = results.mapNotNull { (id, _, np) -> np?.let { id to it } }.toMap()
        Page(mixed.distinctBy { it.url }, if (next.isEmpty()) null else MultiPage(next))
    }

    override suspend fun musicHome(): List<MediaSection> = coroutineScope {
        val queries = listOf(
            "Top hits" to SearchFilter.MUSIC,
            "Trending community playlists" to SearchFilter.PLAYLISTS,
            "Bollywood hits" to SearchFilter.PLAYLISTS,
            "Romantic songs" to SearchFilter.MUSIC,
            "Lofi & chill" to SearchFilter.PLAYLISTS,
            "Ghazal & Sufi essentials" to SearchFilter.PLAYLISTS,
            "Workout music" to SearchFilter.PLAYLISTS,
        )
        queries.map { (title, filter) ->
            async {
                runCatching {
                    val items = search(title, filter).items.take(12)
                    MediaSection(title, items, SectionStyle.ROW)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    /** Strips playlist/radio/tracking parameters: watch?v=ID&list=…&pp=… → watch?v=ID. */
    private fun canonicalStreamUrl(url: String): String {
        com.edureminder.quicktube.core.common.Urls.youtubeVideoId(url)?.let { return "https://www.youtube.com/watch?v=$it" }
        val service = serviceFor(url)
        return runCatching { service.streamLHFactory.let { f -> f.fromId(f.getId(url)).url } }.getOrDefault(url)
    }

    override suspend fun details(url: String): VideoDetails = io {
        val service = serviceFor(url)
        val info = StreamInfo.getInfo(service, canonicalStreamUrl(url))
        val isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val progressive = info.videoStreams.filter { it.isUrl && !it.isVideoOnly }
        val bestProgressive = progressive.filter { it.format == NpFormat.MPEG_4 }.maxByOrNull { it.height }
            ?: progressive.maxByOrNull { it.height }
        val bestAudio = info.audioStreams.filter { it.isUrl }.bestM4a()
        val item = MediaItem(
            url = info.url,
            title = info.name,
            thumbnail = info.thumbnails.best(),
            uploader = info.uploaderName.orEmpty(),
            uploaderUrl = info.uploaderUrl,
            uploaderAvatar = info.uploaderAvatars.best(),
            durationSec = info.duration,
            viewCount = info.viewCount,
            uploadDate = info.uploadDate?.offsetDateTime()?.let(::relativeTime) ?: info.textualUploadDate,
            isLive = isLive,
        )
        VideoDetails(
            item = item,
            description = info.description?.content.orEmpty(),
            likeCount = info.likeCount,
            subscriberCount = info.uploaderSubscriberCount,
            uploadDate = info.uploadDate?.offsetDateTime()?.let(::relativeTime) ?: info.textualUploadDate,
            playbackUrl = bestProgressive?.content,
            playbackMime = bestProgressive?.format?.mimeType,
            hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() },
            dashUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() },
            audioPlaybackUrl = bestAudio?.content,
            options = if (isLive) emptyList() else buildOptions(info),
            related = info.relatedItems.mapNotNull { it.toItem() },
            commentsUrl = info.url,
        )
    }

    private fun buildOptions(info: StreamInfo): List<DownloadOption> {
        val options = mutableListOf<DownloadOption>()
        val audios = info.audioStreams.filter { it.isUrl }
        val m4a = audios.filter { it.format == NpFormat.M4A }.sortedBy { it.averageBitrate }
        val opus = audios.filter { it.format == NpFormat.WEBMA || it.format == NpFormat.WEBMA_OPUS || it.format == NpFormat.OPUS }.sortedBy { it.averageBitrate }
        m4a.lastOrNull()?.let { options += it.toOption("Fast", "M4A (${it.kbps()}K), best for mobile play", null) }
        m4a.firstOrNull()?.takeIf { m4a.size > 1 }?.let {
            options += it.toOption("M4A (${it.kbps()}K)", "Smaller AAC audio, supported everywhere", "Low")
        }
        opus.lastOrNull()?.let {
            options += it.toOption("Opus (${it.kbps()}K)", "High efficiency audio for Bluetooth speakers, phones, cars", null)
        }

        // Muxed (audio+video) streams are ready to play as-is.
        val muxed = info.videoStreams.filter { it.isUrl && !it.isVideoOnly && it.format == NpFormat.MPEG_4 }
        val bestAudio = m4a.lastOrNull()
        // H.264 at every height it exists; VP9 (and AV1 where the phone plays it) for the heights it doesn't, such as
        // 1440p/4K. They're merged into MP4 by Media3, which takes VP9/AV1 on any Android version.
        fun rank(v: org.schabi.newpipe.extractor.stream.VideoStream): Int {
            val c = v.codec?.lowercase().orEmpty()
            return when {
                c.startsWith("avc") || c.isEmpty() && v.format == NpFormat.MPEG_4 -> 0
                c.startsWith("vp9") || c.startsWith("vp09") -> 1
                c.startsWith("av01") && com.edureminder.quicktube.core.common.DeviceCodecs.canDecodeAv1 -> 2
                else -> 9
            }
        }
        val videoOnly = info.videoOnlyStreams.filter { it.isUrl && rank(it) < 9 }
        val byHeight = linkedMapOf<Int, DownloadOption>()
        muxed.forEach { v -> byHeight[v.height] = v.toOption(null, 0) }
        if (bestAudio != null) {
            videoOnly.groupBy { it.height }.forEach { (h, list) ->
                if (h in byHeight) return@forEach
                val v = list.sortedWith(compareBy({ rank(it) }, { -it.fps })).first()
                byHeight[h] = v.toOption(bestAudio, bestAudio.size())
            }
        }
        byHeight.values.sortedBy { it.height }.forEach { options += it }
        return options
    }

    private fun AudioStream.toOption(title: String, info: String, label: String?) = DownloadOption(
        id = "a$itag$averageBitrate${format?.suffix}",
        kind = FormatKind.AUDIO,
        title = title,
        info = info,
        ext = if (format == NpFormat.M4A) "m4a" else "webm",
        mimeType = if (format == NpFormat.M4A) "audio/mp4" else "audio/webm",
        url = content,
        sizeBytes = size(),
        bitrate = averageBitrate,
        label = label,
    )

    private fun VideoStream.toOption(audio: AudioStream?, audioSize: Long): DownloadOption {
        // Vertical videos (Shorts) are named by their short side, like the YouTube app: 1080x1920 is 1080p.
        val res = if (width in 1 until height) width else height
        val q = "${res}p"
        val title = when {
            res <= 360 -> "Fast ($q)"
            res <= 480 -> "Standard ($q)"
            res <= 720 -> "High quality ($q)"
            res <= 1080 -> "Full HD ($q)"
            res <= 1440 -> "2K ($q)"
            else -> "4K ($q)"
        }
        val desc = when {
            res <= 240 -> "Poor video quality"
            res <= 360 -> "Normal quality, saves data"
            res <= 480 -> "Good quality"
            res <= 720 -> "HD quality"
            else -> "Best quality, larger file"
        }
        return DownloadOption(
            id = "v$itag$height",
            kind = FormatKind.VIDEO,
            title = title,
            info = desc + (if (fps > 30) " · ${fps}fps" else ""),
            ext = "mp4",
            mimeType = "video/mp4",
            url = content,
            audioUrl = audio?.content,
            sizeBytes = size(),
            audioSizeBytes = if (audio != null) audioSize else -1,
            height = res,
            label = when {
                res <= 240 -> "Low"
                res >= 1080 -> "HD"
                else -> null
            },
        )
    }

    private fun AudioStream.kbps() = averageBitrate.takeIf { it > 0 } ?: (bitrate / 1000)
    private fun AudioStream.size(): Long = itagItem?.contentLength?.takeIf { it > 0 } ?: -1
    private fun VideoStream.size(): Long = itagItem?.contentLength?.takeIf { it > 0 } ?: -1
    private fun List<AudioStream>.bestM4a() =
        filter { it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate } ?: maxByOrNull { it.averageBitrate }

    override suspend fun comments(url: String, page: Any?): Page<Comment> = io {
        val service = serviceFor(url)
        @Suppress("NAME_SHADOWING") val url = canonicalStreamUrl(url)
        if (page == null) {
            val info = CommentsInfo.getInfo(service, url)
            Page(info.relatedItems.map { it.toComment() }, info.nextPage)
        } else {
            val info = CommentsInfo.getInfo(service, url)
            val more = CommentsInfo.getMoreItems(service, info, page as NpPage)
            Page(more.items.map { it.toComment() }, more.nextPage)
        }
    }

    private fun org.schabi.newpipe.extractor.comments.CommentsInfoItem.toComment() = Comment(
        author = uploaderName.orEmpty(),
        authorAvatar = uploaderAvatars.best(),
        text = WebMediaFinder.decodeEntities(commentText?.content.orEmpty().replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")),
        likeCount = likeCount,
        date = textualUploadDate,
        replyCount = replyCount,
    )

    override suspend fun channel(url: String, page: Any?): ChannelDetails = io {
        val service = serviceFor(url)
        val info = ChannelInfo.getInfo(service, url)
        val item = MediaItem(
            url = info.url, title = info.name, thumbnail = info.avatars.best(), kind = ItemKind.CHANNEL,
            subscriberCount = info.subscriberCount, uploader = info.name,
        )
        val tab = info.tabs.firstOrNull()
        val videos = if (tab == null) Page(emptyList()) else if (page == null) {
            val tabInfo = ChannelTabInfo.getInfo(service, tab)
            Page(tabInfo.relatedItems.mapNotNull { it.toItem() }, tabInfo.nextPage)
        } else {
            val more = ChannelTabInfo.getMoreItems(service, tab, page as NpPage)
            Page(more.items.mapNotNull { it.toItem() }, more.nextPage)
        }
        ChannelDetails(item, info.banners.best(), info.description, videos)
    }

    override suspend fun playlist(url: String, page: Any?): PlaylistDetails = io {
        val service = serviceFor(url)
        if (page == null) {
            val info = PlaylistInfo.getInfo(service, url)
            PlaylistDetails(
                MediaItem(
                    url = info.url, title = info.name, thumbnail = info.thumbnails.best(),
                    uploader = info.uploaderName.orEmpty(), kind = ItemKind.PLAYLIST, streamCount = info.streamCount,
                ),
                Page(info.relatedItems.mapNotNull { it.toItem() }, info.nextPage),
            )
        } else {
            val more = PlaylistInfo.getMoreItems(service, url, page as NpPage)
            PlaylistDetails(MediaItem(url = url, title = "", kind = ItemKind.PLAYLIST), Page(more.items.mapNotNull { it.toItem() }, more.nextPage))
        }
    }

    private fun InfoItem.toItem(): MediaItem? = when (this) {
        is StreamInfoItem -> MediaItem(
            url = url,
            title = name,
            thumbnail = thumbnails.best(),
            uploader = uploaderName.orEmpty(),
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatars.best(),
            durationSec = duration,
            viewCount = viewCount,
            uploadDate = textualUploadDate,
            isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
            isShort = isShortFormContent,
        )
        is ChannelInfoItem -> MediaItem(
            url = url, title = name, thumbnail = thumbnails.best(), kind = ItemKind.CHANNEL,
            subscriberCount = subscriberCount, streamCount = streamCount, description = description,
        )
        is PlaylistInfoItem -> MediaItem(
            url = url, title = name, thumbnail = thumbnails.best(), kind = ItemKind.PLAYLIST,
            uploader = uploaderName.orEmpty(), streamCount = streamCount,
        )
        else -> null
    }

    private fun List<Image>.best(): String? =
        maxByOrNull { if (it.height > 0) it.height else it.estimatedResolutionLevel.ordinal * 100 }?.url

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun relativeTime(t: java.time.OffsetDateTime): String {
        val d = java.time.Duration.between(t, java.time.OffsetDateTime.now())
        val days = d.toDays()
        fun p(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            d.isNegative -> "Upcoming"
            d.toMinutes() < 60 -> p(d.toMinutes().coerceAtLeast(1), "minute")
            d.toHours() < 24 -> p(d.toHours(), "hour")
            days < 7 -> p(days, "day")
            days < 30 -> p(days / 7, "week")
            days < 365 -> p(days / 30, "month")
            else -> p(days / 365, "year")
        }
    }

    private companion object {
        val NATIVE_HOSTS = listOf("youtube.com", "youtu.be", "soundcloud.com", "bandcamp.com", "media.ccc.de")
    }
}
