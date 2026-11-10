package com.edureminder.quicktube.feature.home

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val homeModule = module {
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { GamesViewModel(get()) }
    viewModel { YouTubeSearchViewModel(get(), get()) }
    viewModel { MusicViewModel(get()) }
    viewModel { BulkViewModel(get(), get(), get()) }
    viewModel { p -> BulkJobViewModel(p.get(), get(), get()) }
}
