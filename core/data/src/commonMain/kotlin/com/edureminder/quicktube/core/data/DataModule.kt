package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.databaseModule
import com.edureminder.quicktube.core.datastore.datastoreModule
import com.edureminder.quicktube.core.extractor.extractorModule
import org.koin.dsl.module

val dataModule = module {
    includes(databaseModule, datastoreModule, extractorModule, platformDataModule)
    // yt-dlp (self-updating) stands in when NewPipe can't read a video; resolved lazily, it isn't bound in tests.
    single<MediaRepository> { MediaRepositoryImpl(get()) { getKoin().getOrNull<com.edureminder.quicktube.core.extractor.UniversalExtractor>() } }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
    single<SearchHistoryRepository> { SearchHistoryRepositoryImpl(get()) }
    single<PlaybackHistoryRepository> { PlaybackHistoryRepositoryImpl(get()) }
    single<SavedVideosRepository> { SavedVideosRepositoryImpl(get()) }
    single<LocalPlaylistRepository> { LocalPlaylistRepositoryImpl(get(), get()) }
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get(), get()) }
    single { ForYouFeed(get(), get(), get()) }
    single { BackupManager(get(), get(), get(), get(), get(), get(), get(), get()) }
}
