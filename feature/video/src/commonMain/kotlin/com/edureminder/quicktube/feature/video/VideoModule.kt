package com.edureminder.quicktube.feature.video

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The video feature's screens shared by Android and desktop: channels, playlists, subscriptions, history, saved. */
val videoListsModule = module {
    viewModel { ListPageViewModel(get()) }
    viewModel { SavedVideosViewModel(get()) }
    viewModel { WatchHistoryViewModel(get(), get()) }
    viewModel { SubscriptionsViewModel(get()) }
    viewModel { p -> LocalPlaylistsViewModel(p.get(), get()) }
    viewModel { p -> LocalPlaylistViewModel(p.get(), get()) }
}
