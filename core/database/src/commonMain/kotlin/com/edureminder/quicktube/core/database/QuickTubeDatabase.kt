package com.edureminder.quicktube.core.database

import androidx.room.Database
import androidx.room.ConstructedBy
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import org.koin.dsl.module

@Database(
    entities = [
        DownloadEntity::class, SiteEntity::class, HistoryEntity::class, BookmarkEntity::class, SearchQueryEntity::class,
        DeletedHistoryEntity::class, PlayHistoryEntity::class, SavedVideoEntity::class,
        BulkJobEntity::class, BulkItemEntity::class, LocalPlaylistEntity::class, LocalPlaylistItemEntity::class,
        SubscriptionEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
@ConstructedBy(QuickTubeDatabaseConstructor::class)
abstract class QuickTubeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun siteDao(): SiteDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun deletedHistoryDao(): DeletedHistoryDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun savedVideoDao(): SavedVideoDao
    abstract fun bulkDao(): BulkDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun subscriptionDao(): SubscriptionDao
}

/** Room's generated constructor for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object QuickTubeDatabaseConstructor : RoomDatabaseConstructor<QuickTubeDatabase> {
    override fun initialize(): QuickTubeDatabase
}

/** Opens the app database: Android keeps its file and migrations, desktop stores it in the app's data folder. */
internal expect fun buildDatabase(scope: org.koin.core.scope.Scope): QuickTubeDatabase

val databaseModule = module {
    single { buildDatabase(this) }
    single { get<QuickTubeDatabase>().downloadDao() }
    single { get<QuickTubeDatabase>().bulkDao() }
    single { get<QuickTubeDatabase>().localPlaylistDao() }
    single { get<QuickTubeDatabase>().subscriptionDao() }
    single { get<QuickTubeDatabase>().siteDao() }
    single { get<QuickTubeDatabase>().historyDao() }
    single { get<QuickTubeDatabase>().bookmarkDao() }
    single { get<QuickTubeDatabase>().searchHistoryDao() }
    single { get<QuickTubeDatabase>().deletedHistoryDao() }
    single { get<QuickTubeDatabase>().playHistoryDao() }
    single { get<QuickTubeDatabase>().savedVideoDao() }
}
