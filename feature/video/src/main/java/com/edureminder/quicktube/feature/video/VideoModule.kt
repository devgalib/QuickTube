package com.edureminder.quicktube.feature.video

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val videoModule = module {
    viewModel { VideoViewModel(get(), get(), get()) }
    viewModel { ListPageViewModel(get()) }
}
