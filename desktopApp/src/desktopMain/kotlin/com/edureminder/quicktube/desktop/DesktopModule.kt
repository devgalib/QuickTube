package com.edureminder.quicktube.desktop

import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.desktop.player.DesktopMusic
import com.edureminder.quicktube.desktop.screens.VideoPageViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The desktop app's own pieces: the libVLC player and the screens that differ from the phone's. */
val desktopModule = module {
    single { DesktopPlayer(get(), get()) }
    single { DesktopMusic(get(), get()) }
    viewModel { (url: String) -> VideoPageViewModel(url, get()) }
}
