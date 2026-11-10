package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.SubscriptionDao
import com.edureminder.quicktube.core.database.SubscriptionEntity
import com.edureminder.quicktube.core.model.ChannelTab
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Subscription
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Channels followed on this phone (no account, like NewPipe), and the feed of their newest uploads. */
interface SubscriptionRepository {
    val subscriptions: Flow<List<Subscription>>
    fun isSubscribed(url: String): Flow<Boolean>
    suspend fun subscribe(channel: MediaItem)
    suspend fun unsubscribe(url: String)
    suspend fun all(): List<Subscription>

    /** The newest uploads of every followed channel together, newest first (Shorts and live streams left out). */
    suspend fun feed(perChannel: Int = 12): List<MediaItem>

    /** Uploads published since the last check, one list per channel (the first check only remembers the newest). */
    suspend fun newUploads(): List<Pair<Subscription, List<MediaItem>>>
}

internal class SubscriptionRepositoryImpl(private val dao: SubscriptionDao, private val media: MediaRepository) : SubscriptionRepository {
    // A channel can be saved under two links (its /channel/ID and its @handle page): shown once, by name.
    override val subscriptions = dao.observe().map { l -> l.map { it.asModel() }.distinctBy { it.key } }
    override fun isSubscribed(url: String) = dao.observeIs(url).map { it > 0 }
    override suspend fun all() = dao.all().map { it.asModel() }.distinctBy { it.key }

    override suspend fun subscribe(channel: MediaItem) {
        val url = channel.uploaderUrl.takeIf { channel.kind != ItemKind.CHANNEL } ?: channel.url
        val name = channel.title.ifBlank { channel.uploader }
        // Followed before through its other link: that entry gives way to this one.
        dao.all().filter { it.url != url && it.asModel().key == keyOf(name) }.forEach { dao.delete(it.url) }
        dao.upsert(SubscriptionEntity(url, channel.title.ifBlank { channel.uploader }, channel.thumbnail ?: channel.uploaderAvatar, System.currentTimeMillis()))
    }

    override suspend fun unsubscribe(url: String) {
        val all = dao.all().map { it.asModel() }
        val key = all.firstOrNull { it.url == url }?.key
        // Every link saved for this channel goes.
        all.filter { it.url == url || (key != null && it.key == key) }.forEach { dao.delete(it.url) }
    }

    private fun keyOf(name: String) = name.trim().lowercase()
    private val Subscription.key get() = keyOf(name)

    private suspend fun uploads(s: Subscription, count: Int): List<MediaItem> =
        media.channelTab(s.url, ChannelTab.VIDEOS).items
            .filter { it.kind == ItemKind.VIDEO && !it.isShort && !it.isLive }.take(count)
            .map { it.copy(uploader = it.uploader.ifBlank { s.name }, uploaderUrl = it.uploaderUrl ?: s.url, uploaderAvatar = it.uploaderAvatar ?: s.avatar) }

    override suspend fun feed(perChannel: Int): List<MediaItem> = coroutineScope {
        // A few channels at a time: quick, without a burst of requests.
        val gate = Semaphore(4)
        val lists = all().map { s -> async { gate.withPermit { runCatching { uploads(s, perChannel) }.getOrDefault(emptyList()) } } }.awaitAll()
        newestFirst(lists)
    }

    override suspend fun newUploads(): List<Pair<Subscription, List<MediaItem>>> = coroutineScope {
        val gate = Semaphore(3)
        dao.all().map { e ->
            async {
                gate.withPermit {
                    val latest = runCatching { uploads(e.asModel(), 5) }.getOrNull() ?: return@withPermit null
                    val newest = latest.firstOrNull() ?: return@withPermit null
                    dao.setLastVideo(e.url, newest.url)
                    // First check after subscribing: nothing is "new" yet.
                    val fresh = if (e.lastVideo == null) emptyList() else latest.takeWhile { it.url != e.lastVideo }
                    fresh.takeIf { it.isNotEmpty() }?.let { e.asModel() to it }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun SubscriptionEntity.asModel() = Subscription(url, name, avatar, subscribedAt)

    companion object {
        /** All channels' uploads merged, newest first; ones without a date keep their channel's order at the end. */
        fun newestFirst(lists: List<List<MediaItem>>): List<MediaItem> =
            lists.flatten().distinctBy { it.url }.sortedWith(compareByDescending<MediaItem> { it.publishedAt > 0 }.thenByDescending { it.publishedAt })
    }
}
