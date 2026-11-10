package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.BookmarkDao
import com.edureminder.quicktube.core.database.BookmarkEntity
import com.edureminder.quicktube.core.database.HistoryDao
import com.edureminder.quicktube.core.database.HistoryEntity
import com.edureminder.quicktube.core.database.SearchHistoryDao
import com.edureminder.quicktube.core.database.SearchQueryEntity
import com.edureminder.quicktube.core.database.SiteDao
import com.edureminder.quicktube.core.database.SiteEntity
import com.edureminder.quicktube.core.database.asModel
import com.edureminder.quicktube.core.datastore.SettingsDataSource
import com.edureminder.quicktube.core.model.AppSettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart

internal class SettingsRepositoryImpl(private val source: SettingsDataSource) : SettingsRepository {
    override val settings = source.settings
    override suspend fun current() = source.current()
    override suspend fun update(transform: (AppSettings) -> AppSettings) = source.update(transform)
    override val defaultDownloadDir get() = source.defaultDownloadDir
}

internal class BrowserRepositoryImpl(
    private val siteDao: SiteDao,
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    /** One-time flags kept beside the site list ("site_bilibili": the default added once to older lists). */
    private val flags: OneTimeFlags,
) : BrowserRepository {
    override val sites = siteDao.observe()
        .onStart {
            if (siteDao.count() == 0) siteDao.insertAll(DEFAULT_SITES)
            // Lists from before the built-in YouTube: add it on top and name the website "YouTube Web" (once; a
            // user who deletes either later keeps it deleted).
            else if (siteDao.countUrlOrName(YOUTUBE_URL, YOUTUBE_WEB) == 0) {
                siteDao.renameBuiltIn(YOUTUBE_WEB_URL, YOUTUBE_WEB)
                siteDao.insert(DEFAULT_SITES.first { it.url == YOUTUBE_URL })
            }
            // Sites added to the defaults later appear once in lists made before them (not again if deleted).
            if (!flags.isSet("site_bilibili")) {
                if (siteDao.countUrlOrName(BILIBILI_URL, "Bilibili") == 0 && siteDao.count() > 0) siteDao.insert(DEFAULT_SITES.first { it.url == BILIBILI_URL })
                flags.set("site_bilibili")
            }
        }
        .map { list -> list.map { it.asModel() } }
    override val history = historyDao.observe().map { list -> list.map { it.asModel() } }
    override val bookmarks = bookmarkDao.observe().map { list -> list.map { it.asModel() } }
    override fun isBookmarked(url: String) = bookmarkDao.observeIsSaved(url)

    override suspend fun addSite(name: String, url: String) {
        siteDao.insert(SiteEntity(name = name, url = url, builtIn = false, position = 100))
    }

    override suspend fun deleteSites(ids: List<Long>) = siteDao.delete(ids)

    override suspend fun recordVisit(title: String, url: String) {
        if (url.startsWith("about:") || url.startsWith("data:")) return
        historyDao.deleteUrl(url)
        historyDao.insert(HistoryEntity(title = title.ifBlank { url }, url = url, visitedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteHistory(id: Long) = historyDao.delete(id)
    override suspend fun clearHistory() = historyDao.clear()

    override suspend fun toggleBookmark(title: String, url: String) {
        val saved = bookmarks_contains(url)
        if (saved) bookmarkDao.deleteUrl(url)
        else bookmarkDao.insert(BookmarkEntity(title = title.ifBlank { url }, url = url, createdAt = System.currentTimeMillis()))
    }

    private suspend fun bookmarks_contains(url: String): Boolean =
        bookmarkDao.observeIsSaved(url).first()

    override suspend fun removeBookmark(url: String) = bookmarkDao.deleteUrl(url)

    companion object {
        const val STATUS_SAVER_URL = "quicktube://status-saver"
        /** The app's own YouTube (the YouTube tab), recommended over the website. */
        const val YOUTUBE_URL = com.edureminder.quicktube.core.model.BUILT_IN_YOUTUBE_URL
        private const val YOUTUBE_WEB_URL = "https://m.youtube.com/"
        private const val YOUTUBE_WEB = "YouTube Web"
        val DEFAULT_SITES = listOf(
            SiteEntity(name = "YouTube", url = YOUTUBE_URL, builtIn = true, position = -1),
            SiteEntity(name = YOUTUBE_WEB, url = YOUTUBE_WEB_URL, builtIn = true, position = 0),
            SiteEntity(name = "Instagram", url = "https://www.instagram.com/", builtIn = true, position = 1),
            SiteEntity(name = "Facebook", url = "https://m.facebook.com/", builtIn = true, position = 2),
            SiteEntity(name = "TikTok", url = "https://www.tiktok.com/", builtIn = true, position = 3),
            SiteEntity(name = "Status Saver", url = STATUS_SAVER_URL, builtIn = true, position = 4),
            SiteEntity(name = "Twitter/X", url = "https://x.com/", builtIn = true, position = 5),
            SiteEntity(name = "Dailymotion", url = "https://www.dailymotion.com/", builtIn = true, position = 6),
            SiteEntity(name = "Vimeo", url = "https://vimeo.com/", builtIn = true, position = 7),
            SiteEntity(name = "SoundCloud", url = "https://m.soundcloud.com/", builtIn = true, position = 8),
            SiteEntity(name = "Bilibili", url = BILIBILI_URL, builtIn = true, position = 9),
        )
        private const val BILIBILI_URL = "https://m.bilibili.com/"
    }
}

internal class SearchHistoryRepositoryImpl(private val dao: SearchHistoryDao) : SearchHistoryRepository {
    override val recent = dao.observe().map { list -> list.map { it.query } }
    override suspend fun add(query: String) {
        if (query.isNotBlank()) dao.upsert(SearchQueryEntity(query.trim(), System.currentTimeMillis()))
    }
    override suspend fun remove(query: String) = dao.delete(query)
    override suspend fun clear() = dao.clear()
}
