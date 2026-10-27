package com.edureminder.quicktube.core.download

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val downloadModule = module {
    single { NetworkMonitor(androidContext()) }
    single { DownloadNotifier(androidContext(), get(), get()) }
    single<DownloadManager> { DownloadEngine(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), getOrNull()) }
}
