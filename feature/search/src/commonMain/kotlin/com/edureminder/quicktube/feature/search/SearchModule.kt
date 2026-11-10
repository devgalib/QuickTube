package com.edureminder.quicktube.feature.search

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val searchModule = module {
    viewModel { SearchViewModel(get(), get()) }
    viewModel { SearchResultsViewModel(get()) }
}
