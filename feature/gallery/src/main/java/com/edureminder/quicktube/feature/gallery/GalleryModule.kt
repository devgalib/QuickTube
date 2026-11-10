package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.feature.gallery.player.GalleryPlayerViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val galleryModule = module {
    viewModel { p -> GalleryViewModel(p.get(), get(), p.getOrNull() ?: false) }
    viewModel { (mediaId: Long) -> GalleryPlayerViewModel(androidApplication(), mediaId, get(), get(), get()) }
}
