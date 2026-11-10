package com.edureminder.quicktube.core.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koin.android.ext.koin.androidContext
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

/** v2: downloads can be HLS playlists. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN protocol TEXT NOT NULL DEFAULT 'HTTP'")
    }
}

/** v3: "audio only" downloads from any site (download video, keep the audio track). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN extractAudio INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4: history of permanently deleted downloads ("Download again"). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deleted_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                "`pageUrl` TEXT, `url` TEXT NOT NULL, `thumbnail` TEXT, `mimeType` TEXT NOT NULL, `quality` TEXT, `sizeBytes` INTEGER NOT NULL, " +
                "`durationSec` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_deleted_history_deletedAt` ON `deleted_history` (`deletedAt`)")
    }
}

/** v5: playback history (resume) and "Watch later". */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `play_history` (`itemKey` TEXT NOT NULL, `title` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`itemKey`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_openedAt` ON `play_history` (`openedAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_videos` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnail` TEXT, `uploader` TEXT NOT NULL, " +
                "`durationSec` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_videos_savedAt` ON `saved_videos` (`savedAt`)")
    }
}

/** v6: downloads of a part of a video (cut). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN clipStartMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN clipEndMs INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE play_history ADD COLUMN thumbnail TEXT")
        db.execSQL("ALTER TABLE play_history ADD COLUMN uploader TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN slideImages TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS `bulk_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `folder` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bulk_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "`link` TEXT NOT NULL, `title` TEXT, `status` TEXT NOT NULL, `message` TEXT, `downloadId` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bulk_items_jobId` ON `bulk_items` (`jobId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `local_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isMusic` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_playlist_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "`url` TEXT NOT NULL, `title` TEXT NOT NULL, `thumbnail` TEXT, `uploader` TEXT, `durationSec` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_playlist_items_playlistId` ON `local_playlist_items` (`playlistId`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bulk_jobs ADD COLUMN quality TEXT NOT NULL DEFAULT 'P720'")
        db.execSQL("ALTER TABLE local_playlists ADD COLUMN source TEXT")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (`url` TEXT NOT NULL, `name` TEXT NOT NULL, `avatar` TEXT, " +
                "`subscribedAt` INTEGER NOT NULL, `lastVideo` TEXT, PRIMARY KEY(`url`))",
        )
        db.execSQL("ALTER TABLE downloads ADD COLUMN startAfter INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN wifiOnly INTEGER NOT NULL DEFAULT 0")
    }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), QuickTubeDatabase::class.java, "quicktube.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }
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
