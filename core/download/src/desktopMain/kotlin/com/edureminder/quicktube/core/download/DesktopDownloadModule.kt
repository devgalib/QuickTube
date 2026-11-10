package com.edureminder.quicktube.core.download

import org.koin.dsl.module

actual val downloadModule: org.koin.core.module.Module = module {
    single<DownloadManager> { DesktopDownloadEngine(get(), get(), get(), get(), getOrNull()) }
}
