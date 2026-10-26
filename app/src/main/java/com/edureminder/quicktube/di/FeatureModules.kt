package com.edureminder.quicktube.di

import com.edureminder.quicktube.feature.browser.browserModule
import com.edureminder.quicktube.feature.home.homeModule
import com.edureminder.quicktube.feature.library.libraryModule
import com.edureminder.quicktube.feature.search.searchModule
import com.edureminder.quicktube.feature.settings.settingsModule
import com.edureminder.quicktube.feature.tools.toolsModule
import com.edureminder.quicktube.feature.video.videoModule

/** Add a feature's Koin module here when adding a new feature module. */
val featureModules = listOf(appModule, homeModule, searchModule, videoModule, libraryModule,
    browserModule, settingsModule, toolsModule,
)
