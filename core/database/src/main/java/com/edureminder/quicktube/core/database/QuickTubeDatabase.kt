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
    ],
    version = 6,
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

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), QuickTubeDatabase::class.java, "quicktube.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }
    single { get<QuickTubeDatabase>().downloadDao() }
    single { get<QuickTubeDatabase>().siteDao() }
    single { get<QuickTubeDatabase>().historyDao() }
    single { get<QuickTubeDatabase>().bookmarkDao() }
    single { get<QuickTubeDatabase>().searchHistoryDao() }
    single { get<QuickTubeDatabase>().deletedHistoryDao() }
    single { get<QuickTubeDatabase>().playHistoryDao() }
    single { get<QuickTubeDatabase>().savedVideoDao() }
}
