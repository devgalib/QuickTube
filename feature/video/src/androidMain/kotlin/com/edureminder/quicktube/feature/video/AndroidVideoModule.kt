package com.edureminder.quicktube.feature.video

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Android: the video page (Media3 player) plus the shared list screens. */
val videoModule = module {
    includes(videoListsModule)
    viewModel { VideoViewModel(get(), get(), get(), get(), get()) }
}
