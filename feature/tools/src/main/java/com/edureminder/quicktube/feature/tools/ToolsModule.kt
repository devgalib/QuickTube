package com.edureminder.quicktube.feature.tools

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val toolsModule = module {
    viewModel { RecycleBinViewModel(get()) }
    viewModel { DeletedHistoryViewModel(get(), get(), get()) }
    viewModel { VaultViewModel(get(), get(), get()) }
    viewModel { StatusSaverViewModel(get(), get(), get()) }
    viewModel { LargeFilesViewModel(get()) }
    viewModel { AppManagerViewModel(get()) }
    viewModel { StorageCleanViewModel(get(), get(), get()) }
}
