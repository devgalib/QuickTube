package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.model.ChannelTab
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.PlayHistoryEntry
import com.edureminder.quicktube.core.model.SearchFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The YouTube tab's topics: "For you" (from the watch history), or one subject. */
enum class FeedCategory(val label: String, val query: String?) {
    FOR_YOU("For you", null),
    SUBSCRIPTIONS("Subscriptions", null),
    MUSIC("Music", "new music video"),
    GAMING("Gaming", "gaming"),
    NEWS("News", "news today"),
    SPORTS("Sports", "sports highlights"),
    LEARNING("Learning", "explained"),
    COMEDY("Comedy", "comedy"),
    TECH("Tech", "tech review"),
    MOVIES("Movies", "movie trailer"),
    FOOD("Food", "recipe"),
    TRAVEL("Travel", "travel vlog"),
    CARS("Cars", "cars review"),
}

/**
 * A personal YouTube feed made on the phone from the local watch history (no account, nothing sent anywhere but the
 * usual YouTube requests), the way NewPipe, LibreTube and Flow do it: videos related to what was watched lately
 * (recent and fully watched ones count more), new uploads of the channels watched most, and searches for the topics
 * that keep coming up in watched titles. Already-watched videos and Shorts are left out. With no history yet it is
 * YouTube's trending list.
 */
class ForYouFeed(
    private val media: MediaRepository,
    private val history: PlaybackHistoryRepository,
    private val subscriptions: SubscriptionRepository,
) {
    /** What's left to load for "more": further searches, then trending pages. */
    private class More(val tasks: List<suspend () -> Page<MediaItem>>, val trending: Any?, val watched: Set<String>, val seen: Set<String>)

    suspend fun load(category: FeedCategory, page: Any? = null): Page<MediaItem> {
        val more = page as? More
        if (more != null) return loadMore(more)
        if (category.query != null) return topicPage(category, page)
        if (category == FeedCategory.SUBSCRIPTIONS) return Page(subscriptions.feed())
        val entries = runCatching { history.all() }.getOrDefault(emptyList()).filter { Urls.youtubeVideoId(it.pageUrl) != null }
        // Channels followed here count too: a few of them each time, so the feed changes.
        val followed = runCatching { subscriptions.all() }.getOrDefault(emptyList()).shuffled().take(SUBSCRIBED).map { it.url }
        if (entries.isEmpty() && followed.isEmpty()) return media.trending()
        val watched = entries.filter { it.progress > WATCHED }.mapNotNull { Urls.youtubeVideoId(it.pageUrl) }.toSet()
        val seeds = seeds(entries, System.currentTimeMillis())
        return coroutineScope {
            // Related videos of each seed (their details also name the channel).
            val details = seeds.map { s -> async { runCatching { media.details(s.pageUrl!!) }.getOrNull() } }.awaitAll().filterNotNull()
            val related = details.map { it.related }
            val channels = (details.mapNotNull { it.item.uploaderUrl }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(CHANNELS).map { it.key } + followed).distinct()
            val uploads = channels.map { c -> async { runCatching { media.channelTab(c, ChannelTab.VIDEOS).items.take(8) }.getOrDefault(emptyList()) } }
            val words = topics(entries.map { it.title })
            val firstSearches = words.take(2).map { w -> async { runCatching { media.search(w, SearchFilter.VIDEOS).items }.getOrDefault(emptyList()) } }
            val fromChannels = uploads.awaitAll()
            val fromTopics = firstSearches.awaitAll()
            val mixed = mix(related + fromChannels + fromTopics, watched)
            // Counts only (no titles or addresses): seeds, related, channel uploads, topic searches, result.
            com.edureminder.quicktube.core.common.QtLog.i("ForYouFeed", "seeds ${seeds.size}/${details.size}, related ${related.sumOf { it.size }}, " +
                "channels ${channels.size}:${fromChannels.sumOf { it.size }}, topics ${words.size}:${fromTopics.sumOf { it.size }}, mixed ${mixed.size}")
            if (mixed.isEmpty()) return@coroutineScope media.trending()
            val laterSearches = words.drop(2).map { w -> suspend { media.search(w, SearchFilter.VIDEOS) } }
            Page(mixed, More(laterSearches, TRENDING_START, watched, mixed.map { key(it) }.toSet()))
        }
    }

    private suspend fun loadMore(m: More): Page<MediaItem> {
        // Searches for the remaining topics first, two at a time; then trending, so the feed never runs dry.
        if (m.tasks.isNotEmpty()) {
            val now = coroutineScope { m.tasks.take(2).map { t -> async { runCatching { t().items }.getOrDefault(emptyList()) } }.awaitAll() }
            val items = mix(now, m.watched).filter { key(it) !in m.seen }
            return Page(items, More(m.tasks.drop(2), m.trending, m.watched, m.seen + items.map { key(it) }))
        }
        val p = media.trending(m.trending.takeUnless { it === TRENDING_START })
        val items = p.items.filter { key(it) !in m.seen && key(it) !in m.watched }
        return Page(items, p.nextPage?.let { More(emptyList(), it, m.watched, m.seen + items.map { i -> key(i) }) })
    }

    /** One subject: YouTube's search for it, videos only (no Shorts). */
    private suspend fun topicPage(category: FeedCategory, page: Any?): Page<MediaItem> {
        val p = media.search(category.query!!, SearchFilter.VIDEOS, page)
        return Page(p.items.filter { it.kind == ItemKind.VIDEO && !it.isShort }, p.nextPage)
    }

    companion object {
        private const val SEEDS = 6
        private const val CHANNELS = 3
        private const val SUBSCRIBED = 4
        private const val WATCHED = 0.9f
        private val TRENDING_START = Any()

        private fun key(item: MediaItem) = Urls.youtubeVideoId(item.url) ?: item.url

        /**
         * The history entries to base the feed on: newest count most (half the weight after a day), and a video
         * watched to the end counts about twice one left after a few seconds.
         */
        fun seeds(entries: List<PlayHistoryEntry>, now: Long, count: Int = SEEDS): List<PlayHistoryEntry> =
            entries.sortedByDescending { e ->
                val days = (now - e.openedAt).coerceAtLeast(0) / 86_400_000.0
                (0.5 + e.progress) / (1 + days)
            }.distinctBy { Urls.youtubeVideoId(it.pageUrl) }.take(count)

        private val STOP = (
            "the a an and or of to in on for with at by from is are was be this that it its my your our you me we i " +
                "official video music lyrics lyric audio hd 4k full song new feat ft vs part episode ep live remix " +
                "how what why when who best top most first ever day year years time official_video mv shorts short tiktok"
            ).split(' ').toSet()

        /**
         * The words that keep coming up in watched titles ("pomeranian", "iphone", "minecraft"), most frequent first.
         * A word must appear in two titles; hashtags count as words.
         */
        fun topics(titles: List<String>, count: Int = 5): List<String> {
            val perTitle = titles.map { t ->
                t.lowercase().replace(Regex("""[^\p{L}\p{N}#\s]"""), " ").split(Regex("""\s+"""))
                    .map { it.removePrefix("#") }
                    .filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) }
                    .toSet()
            }
            return perTitle.flatten().groupingBy { it }.eachCount().filter { it.value >= 2 }.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(count).map { it.key }
        }

        /** Takes from each list in turn (so no one source fills the screen), once per video, without watched videos and Shorts. */
        fun mix(lists: List<List<MediaItem>>, watched: Set<String>): List<MediaItem> {
            val seen = HashSet<String>()
            val out = ArrayList<MediaItem>()
            val iters = lists.map { l -> l.filter { it.kind == ItemKind.VIDEO && !it.isShort && !it.isLive }.iterator() }
            while (iters.any { it.hasNext() }) {
                for (it in iters) {
                    if (!it.hasNext()) continue
                    val item = it.next()
                    val k = key(item)
                    if (k in watched || !seen.add(k)) continue
                    out += item
                }
            }
            return out
        }
    }
}
