package com.edureminder.quicktube.feature.library

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val libraryModule = module {
    viewModel { LibraryViewModel(get(), get(), get(), get()) }
}
