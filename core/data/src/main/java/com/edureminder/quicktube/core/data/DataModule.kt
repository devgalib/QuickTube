package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.database.databaseModule
import com.edureminder.quicktube.core.datastore.datastoreModule
import com.edureminder.quicktube.core.extractor.extractorModule
import org.koin.dsl.module

val dataModule = module {
    includes(databaseModule, datastoreModule, extractorModule)
    single<MediaRepository> { MediaRepositoryImpl(get()) }
    single<LibraryRepository> { LibraryRepositoryImpl(get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
    single<BrowserRepository> { BrowserRepositoryImpl(get(), get(), get()) }
    single<SearchHistoryRepository> { SearchHistoryRepositoryImpl(get()) }
}
