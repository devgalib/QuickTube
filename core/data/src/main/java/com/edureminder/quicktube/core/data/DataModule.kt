package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.databaseModule
import com.edureminder.quicktube.core.datastore.datastoreModule
import com.edureminder.quicktube.core.extractor.extractorModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    includes(databaseModule, datastoreModule, extractorModule)
    // yt-dlp (self-updating) stands in when NewPipe can't read a video; resolved lazily, it isn't bound in tests.
    single<MediaRepository> { MediaRepositoryImpl(get()) { getKoin().getOrNull<com.edureminder.quicktube.core.extractor.UniversalExtractor>() } }
    single<LibraryRepository> { LibraryRepositoryImpl(get(), get(), androidContext()) { get<SettingsRepository>().current().downloadDir } }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
    single<BrowserRepository> { BrowserRepositoryImpl(get(), get(), get(), androidContext()) }
    single<SearchHistoryRepository> { SearchHistoryRepositoryImpl(get()) }
    single<GalleryRepository> { MediaStoreGalleryRepository(androidContext()) }
    single<PlaybackHistoryRepository> { PlaybackHistoryRepositoryImpl(get()) }
    single<SavedVideosRepository> { SavedVideosRepositoryImpl(get()) }
    single<LocalPlaylistRepository> { LocalPlaylistRepositoryImpl(get(), get()) }
    single<SubscriptionRepository> { SubscriptionRepositoryImpl(get(), get()) }
    single { ForYouFeed(get(), get(), get()) }
    single { BackupManager(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<GamesRepository> { RemoteConfigGamesRepository(androidContext()) }
}
