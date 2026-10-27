package com.edureminder.quicktube.core.downloadsheet

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val downloadSheetModule = module {
    single { DownloadSheetController() }
    single { BatchDownloader(get(), get(), get()) }
    viewModel { DownloadSheetViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
