package com.edureminder.quicktube.core.downloadsheet

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val downloadSheetModule = module {
    single { DownloadSheetController() }
    viewModel { DownloadSheetViewModel(get(), get(), get(), get(), get(), get()) }
}
