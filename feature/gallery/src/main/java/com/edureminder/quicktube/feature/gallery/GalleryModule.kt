package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.feature.gallery.player.GalleryPlayerViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val galleryModule = module {
    viewModel { (bucketId: Long) -> GalleryViewModel(bucketId, get()) }
    viewModel { (mediaId: Long) -> GalleryPlayerViewModel(androidApplication(), mediaId, get(), get()) }
}
