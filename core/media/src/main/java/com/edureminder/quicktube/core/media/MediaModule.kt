package com.edureminder.quicktube.core.media

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val mediaModule = module {
    single { PlayerController(androidContext()) }
}
